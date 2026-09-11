import { bearer, HttpError, mintToken, signingKey, verifyToken } from "./auth.ts"
import {
  completion,
  Connection,
  endpoint,
  events,
  Model,
  Obj,
  readJson,
  requestBody,
  StreamAdapter,
} from "./wire.ts"

export interface Dependencies {
  sessionUser(token: string): Promise<string | null>
  rpc(name: string, params: Obj): Promise<unknown>
  secret(name: string): string | undefined
  fetch: typeof fetch
}
const headers = { "Content-Type": "application/json", "Cache-Control": "no-store" }
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers })
function failure(error: unknown): Response {
  const e = error instanceof HttpError
    ? error
    : new HttpError(503, "unavailable", "BOSS AI is temporarily unavailable.")
  return json({ error: { code: e.code, message: e.message } }, e.status)
}

export function createHandler(deps: Dependencies): (request: Request) => Promise<Response> {
  return async (request) => {
    let reservation: string | undefined
    let dispatched = false
    try {
      const path = new URL(request.url).pathname.replace(/^\/functions\/v1/, "").replace(
        /^\/boss-ai/,
        "",
      )
      const token = bearer(request)
      const key = signingKey(deps.secret("BOSS_AI_SIGNING_SECRET"))
      if (request.method === "POST" && path === "/auth/token") {
        const user = await deps.sessionUser(token)
        if (!user) throw new HttpError(401, "unauthorized", "Sign in to BOSS to use AI.")
        return json(await mintToken(user, key))
      }
      const user = await verifyToken(token, key)
      if (request.method === "GET" && (path === "/v1/models" || path === "/usage")) {
        const data = await deps.rpc("boss_ai_catalog", { p_user_id: user })
        return json({ object: "list", data })
      }
      if (request.method !== "POST" || path !== "/v1/chat/completions") {
        throw new HttpError(404, "not_found", "Unknown BOSS AI endpoint.")
      }
      const input = await readJson(request.body)
      if (typeof input.model !== "string" || !/^[a-z0-9][a-z0-9._-]{0,99}$/.test(input.model)) {
        throw new HttpError(400, "invalid_model", "Choose a published BOSS model.")
      }
      const requestId = crypto.randomUUID()
      const result = await deps.rpc("boss_ai_reserve", {
        p_user_id: user,
        p_model_id: input.model,
        p_request_id: requestId,
      }) as { error?: string; model: Model; connection: Connection }
      if (result.error) {
        if (result.error === "forbidden") {
          throw new HttpError(403, "forbidden", "This model is not available for your account.")
        }
        if (result.error === "allowance_exceeded") {
          throw new HttpError(
            429,
            result.error,
            "Your model allowance has insufficient remaining capacity. Check BOSS AI usage for reset times.",
          )
        }
        throw new HttpError(429, "busy", "Too many requests for this model. Try again shortly.")
      }
      reservation = requestId
      const body = requestBody(input, result.model, result.connection.api_type)
      const url = endpoint(result.connection)
      const apiKey = deps.secret(result.connection.api_key_secret)
      if (!apiKey) throw new HttpError(503, "configuration", "BOSS AI is temporarily unavailable.")
      // Never follow redirects with an upstream credential. Only configured servers
      // receive it; request-supplied routing and provider overrides are rejected.
      const abort = new AbortController()
      const cancel = () => abort.abort()
      request.signal.addEventListener("abort", cancel, { once: true })
      if (request.signal.aborted) abort.abort()
      const timeout = setTimeout(cancel, 240_000)
      const cleanup = () => {
        clearTimeout(timeout)
        request.signal.removeEventListener("abort", cancel)
      }
      let upstream: Response
      try {
        dispatched = true
        upstream = await deps.fetch(url, {
          method: "POST",
          redirect: "error",
          signal: abort.signal,
          headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
          body: JSON.stringify(body),
        })
      } catch (e) {
        cleanup()
        throw e
      }
      if (!upstream.ok) {
        await upstream.body?.cancel()
        cleanup()
        // A rejected request with a known 4xx response performed no inference.
        if ([400, 401, 403, 404, 422, 429].includes(upstream.status)) dispatched = false
        throw new HttpError(
          upstream.status === 429 ? 503 : 502,
          "upstream_error",
          "The model is temporarily unavailable. Please try again.",
        )
      }
      const settle = async (tokens: number | null) => {
        await deps.rpc("boss_ai_settle", { p_request_id: requestId, p_tokens: tokens })
      }
      if (input.stream !== true) {
        try {
          const output = completion(
            await readJson(upstream.body, 16 * 1024 * 1024),
            input.model,
            result.connection.api_type,
            requestId,
          )
          const tokens = (output.usage as Obj | null)?.total_tokens
          await settle(typeof tokens === "number" ? tokens : null)
          reservation = undefined
          return json(output)
        } finally {
          cleanup()
        }
      }
      if (!upstream.body) {
        cleanup()
        throw new Error("Missing stream")
      }
      const adapter = new StreamAdapter(input.model, result.connection.api_type, requestId)
      const iterator = events(upstream.body)[Symbol.asyncIterator]()
      const encoder = new TextEncoder()
      const frame = (data: unknown) =>
        encoder.encode(`data: ${typeof data === "string" ? data : JSON.stringify(data)}\n\n`)
      let closed = false
      const finish = async (tokens: number | null) => {
        if (closed) return
        closed = true
        abort.abort()
        cleanup()
        await iterator.return?.(undefined).catch(() => {})
        // If settlement fails the full reservation remains charged. Never refund
        // an unknown outcome merely because a worker or a client disconnected.
        await settle(tokens).catch(() => console.error("boss-ai settlement failed", requestId))
      }
      reservation = undefined // The stream now owns cleanup and settlement.
      const stream = new ReadableStream<Uint8Array>({
        async pull(controller) {
          try {
            const next = await iterator.next()
            if (next.done) {
              if (!adapter.finished) throw new Error("Truncated stream")
              await finish(adapter.tokens)
              controller.enqueue(frame("[DONE]"))
              controller.close()
              return
            }
            for (const chunk of adapter.accept(next.value)) controller.enqueue(frame(chunk))
            if (adapter.finished) {
              await finish(adapter.tokens)
              controller.enqueue(frame("[DONE]"))
              controller.close()
            }
          } catch {
            await finish(null)
            controller.enqueue(
              frame({
                error: {
                  code: "stream_failed",
                  message: "The model response was interrupted. Please retry.",
                },
              }),
            )
            controller.close()
          }
        },
        async cancel() {
          await finish(null)
        },
      })
      return new Response(stream, {
        headers: {
          "Content-Type": "text/event-stream",
          "Cache-Control": "no-store",
          "X-Request-ID": requestId,
        },
      })
    } catch (error) {
      if (reservation) {
        await deps.rpc("boss_ai_settle", {
          p_request_id: reservation,
          p_tokens: dispatched ? null : 0,
        })
          .catch(() => console.error("boss-ai settlement failed", reservation))
      }
      return failure(error)
    }
  }
}
