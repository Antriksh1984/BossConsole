import { assertEquals, assertRejects, assertThrows } from "@std/assert"
import { completion, events, Model, requestBody, StreamAdapter, usage } from "../wire.ts"

const model: Model = {
  id: "boss-test",
  upstream_model: "private-model",
  capabilities: ["text", "tools", "structured_output"],
  context_length: 4096,
  max_output_tokens: 512,
}
const input = { model: model.id, messages: [{ role: "user", content: "hello" }] }

Deno.test("routing overrides and unadvertised capabilities are refused", () => {
  for (const field of ["base_url", "api_key", "provider", "n", "previous_response_id"]) {
    assertThrows(() => requestBody({ ...input, [field]: "override" }, model, "openai_chat"))
  }
  assertThrows(() => requestBody({ ...input, max_tokens: 513 }, model, "openai_chat"))
  assertThrows(() =>
    requestBody(
      {
        ...input,
        messages: [{
          role: "user",
          content: [{ type: "image_url", image_url: { url: "https://example.com/private" } }],
        }],
      },
      model,
      "openai_chat",
    )
  )
  assertThrows(() =>
    requestBody({ ...input, tools: [{ type: "web_search" }] }, model, "openai_responses")
  )
})

Deno.test("upstream mapping replaces model, disables storage, and bounds output", () => {
  const body = requestBody({ ...input, stream: true, max_tokens: 40 }, model, "openai_chat")
  assertEquals(body.model, "private-model")
  assertEquals(body.max_completion_tokens, 40)
  assertEquals(body.store, false)
  assertEquals(body.stream_options, { include_usage: true })
  assertEquals(body.temperature, undefined)
})

Deno.test("Responses adapter retains every tool round with call IDs", () => {
  const body = requestBody(
    {
      ...input,
      messages: [
        { role: "system", content: "system" },
        { role: "user", content: "question" },
        {
          role: "assistant",
          content: null,
          tool_calls: [{
            id: "call-1",
            type: "function",
            function: { name: "lookup", arguments: "{}" },
          }],
        },
        { role: "tool", tool_call_id: "call-1", content: "observation" },
        { role: "assistant", content: "answer" },
        { role: "user", content: "next question" },
      ],
      tools: [{ type: "function", function: { name: "lookup", parameters: { type: "object" } } }],
      tool_choice: { type: "function", function: { name: "lookup" } },
      max_tokens: 128,
    },
    model,
    "openai_responses",
  )
  assertEquals(body.input, [
    { role: "system", content: "system" },
    { role: "user", content: "question" },
    { type: "function_call", call_id: "call-1", name: "lookup", arguments: "{}" },
    { type: "function_call_output", call_id: "call-1", output: "observation" },
    { role: "assistant", content: "answer" },
    { role: "user", content: "next question" },
  ])
  assertEquals(body.tool_choice, { type: "function", name: "lookup" })
  assertEquals(body.max_output_tokens, 128)
  assertEquals(body.truncation, "disabled")
})

Deno.test("usage includes output reasoning once and rejects unknown accounting", () => {
  assertEquals(
    usage(
      { input_tokens: 12, output_tokens: 20, output_tokens_details: { reasoning_tokens: 15 } },
      true,
    )?.total_tokens,
    32,
  )
  assertEquals(usage({ prompt_tokens: -1, completion_tokens: 10 }), null)
  assertEquals(usage({}), null)
})

Deno.test("Responses completion translates text and function output", () => {
  const reply = completion(
    {
      status: "completed",
      output: [
        { type: "message", content: [{ type: "output_text", text: "hello" }] },
        { type: "function_call", call_id: "call", name: "lookup", arguments: "{}" },
      ],
      usage: { input_tokens: 1, output_tokens: 2 },
    },
    model.id,
    "openai_responses",
    "request",
  )
  assertEquals(reply.model, model.id)
  assertEquals(reply.usage, { prompt_tokens: 1, completion_tokens: 2, total_tokens: 3 })
  assertEquals((reply.choices as Record<string, unknown>[])[0].finish_reason, "tool_calls")
})

Deno.test("SSE parser handles byte splits, CRLF and multiline data", async () => {
  const bytes = new TextEncoder().encode(
    ': comment\r\n\r\ndata: {"text":\r\ndata: "日"}\r\n\r\ndata: [DONE]\n\n',
  )
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      for (const byte of bytes) c.enqueue(new Uint8Array([byte]))
      c.close()
    },
  })
  const result = []
  for await (const event of events(body)) result.push(event)
  assertEquals(result, ['{"text":\n"日"}', "[DONE]"])
  await assertRejects(async () => {
    for await (const _ of events(new Response("data: unfinished").body!)) { /* drain */ }
  })
})

Deno.test("Responses stream maps sparse output indexes to contiguous tool indexes", () => {
  const adapter = new StreamAdapter(model.id, "openai_responses", "request")
  const first = adapter.accept(
    JSON.stringify({
      type: "response.output_item.added",
      output_index: 3,
      item: { type: "function_call", call_id: "call", name: "lookup" },
    }),
  )
  assertEquals((first[0].choices as any)[0].delta.tool_calls[0].index, 0)
  const delta = adapter.accept(
    JSON.stringify({
      type: "response.function_call_arguments.delta",
      output_index: 3,
      delta: "{}",
    }),
  )
  assertEquals((delta[0].choices as any)[0].delta.tool_calls[0].function.arguments, "{}")
  adapter.accept(
    JSON.stringify({
      type: "response.completed",
      response: { usage: { input_tokens: 2, output_tokens: 4 } },
    }),
  )
  assertEquals(adapter.finished, true)
  assertEquals(adapter.tokens, 6)
  assertThrows(() =>
    new StreamAdapter(model.id, "openai_chat", "r").accept('{"error":{"message":"private prompt"}}')
  )
})
