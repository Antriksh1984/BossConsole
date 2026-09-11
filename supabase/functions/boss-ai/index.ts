import { createClient } from "@supabase/supabase-js"
import { createHandler } from "./app.ts"

const client = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  {
    auth: { persistSession: false, autoRefreshToken: false },
  },
)
Deno.serve(createHandler({
  async sessionUser(token) {
    const { data, error } = await client.auth.getUser(token)
    return error || !data.user || data.user.is_anonymous ? null : data.user.id
  },
  async rpc(name, params) {
    const { data, error } = await client.rpc(name, params)
    if (error) throw new Error("BOSS AI database operation failed")
    return data
  },
  secret: (name) => Deno.env.get(name),
  fetch,
}))
