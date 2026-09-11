import { assert, assertEquals } from "@std/assert"
import { createHandler } from "../app.ts"
import { mintToken, signingKey } from "../auth.ts"
import { Obj } from "../wire.ts"

const secret = "test-signing-secret-at-least-32-bytes-long"
async function fixture(options: { deny?: string; stream?: string; upstreamStatus?: number } = {}) {
  const calls: { name: string; params: Obj }[] = []
  const requests: Request[] = []
  const token =
    (await mintToken("00000000-0000-0000-0000-000000000001", signingKey(secret))).access_token
  const handler = createHandler({
    sessionUser: async (t) => t === "boss-session" ? "00000000-0000-0000-0000-000000000001" : null,
    secret: (name) => name === "BOSS_AI_SIGNING_SECRET" ? secret : "upstream-secret",
    async rpc(name, params) {
      calls.push({ name, params })
      if (name === "boss_ai_catalog") return [{ id: "boss-test" }]
      if (name === "boss_ai_reserve") {
        return options.deny ? { error: options.deny } : {
          model: {
            id: "boss-test",
            upstream_model: "private",
            context_length: 4096,
            max_output_tokens: 512,
            capabilities: ["text"],
          },
          connection: {
            base_url: "https://upstream.example/v1",
            api_type: "openai_chat",
            api_key_secret: "BOSS_AI_TEST",
          },
        }
      }
      return null
    },
    fetch: async (url, init) => {
      requests.push(new Request(url, init))
      return new Response(
        options.stream ??
          JSON.stringify({
            choices: [{
              message: { role: "assistant", content: "hello" },
              finish_reason: "stop",
              index: 0,
            }],
            usage: { prompt_tokens: 2, completion_tokens: 3 },
          }),
        { status: options.upstreamStatus ?? 200 },
      )
    },
  })
  const request = (body: Obj, credential = token) =>
    new Request("https://api.example/functions/v1/boss-ai/v1/chat/completions", {
      method: "POST",
      headers: { Authorization: `Bearer ${credential}` },
      body: JSON.stringify(body),
    })
  return { calls, requests, handler, request, token }
}
const body = { model: "boss-test", messages: [{ role: "user", content: "private prompt" }] }

Deno.test("all inference and catalog access requires AI-scoped authentication", async () => {
  const f = await fixture()
  for (const path of ["v1/models", "usage", "v1/chat/completions"]) {
    assertEquals((await f.handler(new Request(`https://api.example/boss-ai/${path}`))).status, 401)
  }
  for (const token of ["boss-session", f.token.slice(0, -5) + "wrong"]) {
    assertEquals((await f.handler(f.request(body, token))).status, 401)
  }
  assertEquals(f.calls.length, 0)
  assertEquals(f.requests.length, 0)
})

Deno.test("broker accepts a BOSS session but not an AI token", async () => {
  const f = await fixture()
  const req = (t: string) =>
    new Request("https://api.example/boss-ai/auth/token", {
      method: "POST",
      headers: { Authorization: `Bearer ${t}` },
    })
  assertEquals((await f.handler(req(f.token))).status, 401)
  const response = await f.handler(req("boss-session"))
  assertEquals(response.status, 200)
  assertEquals((await response.json()).refresh_after_seconds, 180)
})

Deno.test("permission and allowance denials never dispatch upstream", async () => {
  for (
    const [deny, status] of [["forbidden", 403], ["allowance_exceeded", 429], [
      "concurrency_exceeded",
      429,
    ]] as const
  ) {
    const f = await fixture({ deny })
    assertEquals((await f.handler(f.request(body))).status, status)
    assertEquals(f.requests.length, 0)
  }
})

Deno.test("server-selected endpoint, model and key; actual usage settlement", async () => {
  const f = await fixture()
  const response = await f.handler(f.request(body))
  assertEquals(response.status, 200)
  assertEquals(f.requests[0].url, "https://upstream.example/v1/chat/completions")
  assertEquals(f.requests[0].headers.get("authorization"), "Bearer upstream-secret")
  assertEquals(f.requests[0].redirect, "error")
  assertEquals((await f.requests[0].json()).model, "private")
  assertEquals(f.calls.at(-1)?.params.p_tokens, 5)
  const output = await response.text()
  assert(!output.includes("upstream-secret"))
  assert(!output.includes("private-model"))
})

Deno.test("invalid inputs refund reservations without inference", async () => {
  const f = await fixture()
  assertEquals((await f.handler(f.request({ ...body, provider: "attacker" }))).status, 400)
  assertEquals(f.calls.at(-1)?.params.p_tokens, 0)
  assertEquals(f.requests.length, 0)
})

Deno.test("truncated streams report failure and retain reserved usage", async () => {
  const f = await fixture({ stream: 'data: {"choices":[{"delta":{"content":"partial"}}]}\n\n' })
  const response = await f.handler(f.request({ ...body, stream: true }))
  const output = await response.text()
  assert(output.includes("stream_failed"))
  assert(!output.includes("[DONE]"))
  assertEquals(f.calls.at(-1)?.params.p_tokens, null)
})

Deno.test("complete streams settle final usage and terminate once", async () => {
  const f = await fixture({
    stream:
      'data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":5}}\n\ndata: [DONE]\n\n',
  })
  const response = await f.handler(f.request({ ...body, stream: true }))
  assertEquals((await response.text()).split("[DONE]").length, 2)
  assertEquals(f.calls.at(-1)?.params.p_tokens, 17)
  assertEquals(f.calls.filter((c) => c.name === "boss_ai_settle").length, 1)
})
