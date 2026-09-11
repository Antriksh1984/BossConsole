import { assert, assertEquals } from "@std/assert"
import { createHandler } from "../app.ts"
import { mintToken, signingKey } from "../auth.ts"
import { Obj } from "../wire.ts"

const secret = "test-signing-secret-at-least-32-bytes-long"
async function fixture(options: {
  deny?: string
  stream?: string
  upstreamStatus?: number
  payload?: Obj
  failFirstSettlement?: boolean
  failFetch?: boolean
  hang?: boolean
  keyName?: string
  apiType?: "openai_chat" | "openai_responses"
} = {}) {
  const calls: { name: string; params: Obj }[] = []
  const requests: Request[] = []
  const audits: string[] = []
  let settlements = 0
  const token =
    (await mintToken("00000000-0000-0000-0000-000000000001", signingKey(secret))).access_token
  const handler = createHandler({
    audit: (event) => {
      audits.push(event)
    },
    upstreamTimeoutMs: 20,
    sessionUser: async (t) => t === "boss-session" ? "00000000-0000-0000-0000-000000000001" : null,
    secret: (name) => name === "BOSS_AI_SIGNING_SECRET" ? secret : "upstream-secret",
    async rpc(name, params) {
      calls.push({ name, params })
      if (name === "boss_ai_settle" && options.failFirstSettlement && ++settlements === 1) {
        throw new Error("private database detail")
      }
      if (name === "boss_ai_catalog") {
        return [{ id: "boss-test", allowance: { day: { remaining: 1024 } } }]
      }
      if (name === "boss_ai_reserve") {
        return options.deny ? { error: options.deny } : {
          model: {
            id: "boss-test",
            upstream_model: "private-model",
            context_length: 4096,
            max_output_tokens: 512,
            capabilities: ["text"],
          },
          connection: {
            base_url: "https://upstream.example/v1",
            api_type: options.apiType ?? "openai_chat",
            api_key_secret: options.keyName ?? "BOSS_AI_TEST",
          },
        }
      }
      return null
    },
    fetch: async (url, init) => {
      requests.push(new Request(url, init))
      if (options.failFetch) throw new Error("private network detail")
      if (options.hang) {
        await new Promise<void>((_, reject) => {
          init!.signal!.addEventListener("abort", () => reject(new Error("timeout")), {
            once: true,
          })
        })
      }
      return new Response(
        options.stream ??
          JSON.stringify(
            options.payload ?? {
              choices: [{
                message: { role: "assistant", content: "hello" },
                finish_reason: "stop",
                index: 0,
              }],
              usage: { prompt_tokens: 2, completion_tokens: 3 },
            },
          ),
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
  return { calls, requests, handler, request, token, audits }
}
const body = { model: "boss-test", messages: [{ role: "user", content: "private prompt" }] }

Deno.test("all inference and catalog access requires AI-scoped authentication", async () => {
  const f = await fixture()
  for (const path of ["v1/models", "v1/usage", "v1/chat/completions"]) {
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
  assertEquals((await f.requests[0].json()).model, "private-model")
  assertEquals(f.calls.at(-1)?.params.p_tokens, 5)
  const output = await response.text()
  assert(!output.includes("upstream-secret"))
  assert(!output.includes("private-model"))
  assertEquals(typeof JSON.parse(output).created, "number")
  assert(response.headers.get("x-request-id"))
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

Deno.test("catalog and usage are accessible inside the declared broker scope", async () => {
  const f = await fixture()
  const get = (path: string) =>
    f.handler(
      new Request(`https://api.example/boss-ai/v1/${path}`, {
        headers: { Authorization: `Bearer ${f.token}` },
      }),
    )
  assertEquals((await (await get("models")).json()).data[0].id, "boss-test")
  assertEquals((await (await get("usage")).json()).data, [
    { model: "boss-test", allowance: { day: { remaining: 1024 } } },
  ])
})

Deno.test("explicit upstream rejections refund while ambiguous server faults remain charged", async () => {
  for (const status of [400, 401, 402, 403, 404, 413, 422, 429, 408, 500, 502, 503, 504]) {
    const f = await fixture({ upstreamStatus: status })
    const response = await f.handler(f.request(body))
    assertEquals(response.status, status === 429 ? 503 : 502)
    assertEquals(f.calls.at(-1)?.params.p_tokens, status < 500 && status !== 408 ? 0 : null)
    assert(f.audits.includes(`upstream_status_${status}`))
  }
})

Deno.test("failed dispatch and timeout are observable unknown outcomes not inferred refunds", async () => {
  for (const options of [{ failFetch: true }, { hang: true }]) {
    const f = await fixture(options)
    const response = await f.handler(f.request(body))
    assertEquals(response.status, 503)
    assertEquals(f.calls.at(-1)?.params.p_tokens, null)
    assert(f.audits.includes("usage_unknown_reservation_retained"))
    assert(!(await response.text()).includes("private"))
  }
})

Deno.test("known usage survives a settlement retry", async () => {
  const f = await fixture({ failFirstSettlement: true })
  await f.handler(f.request(body))
  assertEquals(
    f.calls.filter((call) => call.name === "boss_ai_settle").map((call) => call.params.p_tokens),
    [5, 5],
  )
})

Deno.test("missing usage emits accounting diagnostics and malformed upstream is a 502", async () => {
  const f = await fixture({
    payload: { choices: [{ message: { role: "assistant", content: "hi" } }] },
  })
  assertEquals((await f.handler(f.request(body))).status, 200)
  assert(f.audits.includes("usage_unknown_reservation_retained"))
  assertEquals(f.calls.at(-1)?.params.p_tokens, null)
  const bad = await fixture({ payload: {} })
  assertEquals((await bad.handler(bad.request(body))).status, 502)
})

Deno.test("routing cannot disclose infrastructure or signing secrets", async () => {
  for (const keyName of ["BOSS_AI_SIGNING_SECRET", "SUPABASE_SERVICE_ROLE_KEY"]) {
    const f = await fixture({ keyName })
    assertEquals((await f.handler(f.request(body))).status, 503)
    assertEquals(f.requests.length, 0)
    assertEquals(f.calls.at(-1)?.params.p_tokens, 0)
  }
})

Deno.test("Responses endpoint round trip exposes only the published model", async () => {
  const f = await fixture({
    apiType: "openai_responses",
    payload: {
      status: "completed",
      output: [{ type: "message", content: [{ type: "output_text", text: "hello" }] }],
      usage: { input_tokens: 2, output_tokens: 3 },
    },
  })
  const response = await f.handler(f.request(body))
  assertEquals(response.status, 200)
  assertEquals(f.requests[0].url, "https://upstream.example/v1/responses")
  assertEquals((await response.json()).model, "boss-test")
  assertEquals(f.calls.at(-1)?.params.p_tokens, 5)
})

Deno.test("stream cancellation retains usage and does not attempt to write to a closed client", async () => {
  const f = await fixture({ stream: 'data: {"choices":[{"delta":{"content":"partial"}}]}\n\n' })
  const response = await f.handler(f.request({ ...body, stream: true }))
  const reader = response.body!.getReader()
  await reader.read()
  await reader.cancel()
  assertEquals(f.calls.filter((call) => call.name === "boss_ai_settle").length, 1)
  assertEquals(f.calls.at(-1)?.params.p_tokens, null)
})
