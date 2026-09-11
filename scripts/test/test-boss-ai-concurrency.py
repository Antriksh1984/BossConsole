"""Two independent PostgreSQL sessions against CI's disposable local Supabase DB.

The first transaction remains open until pg_stat_activity proves the second is
waiting on the advisory lock. No timing sleep is used as evidence of serialization.
"""
import json
import subprocess
import time

PSQL = ["docker", "exec", "-i", "supabase_db_boss-main", "psql", "-XqAt",
        "-U", "postgres", "-d", "postgres", "-v", "ON_ERROR_STOP=1"]
USER = "bc000000-0000-4000-8000-000000000001"
FIRST = "bd000000-0000-4000-8000-000000000001"
SECOND = "bd000000-0000-4000-8000-000000000002"


def query(sql):
    return subprocess.check_output(PSQL + ["-c", sql], text=True, timeout=15).strip()


first = second = None
try:
    query(f"""
      INSERT INTO auth.users(id,email) VALUES('{USER}','boss-ai-concurrency@pgtap.test');
      INSERT INTO public.boss_ai_connections VALUES
        ('concurrency-test','https://example.com/v1','BOSS_AI_TEST','openai_chat',true);
      INSERT INTO public.boss_ai_models
        (id,display_name,connection_id,upstream_model,context_length,max_output_tokens,published)
        VALUES('concurrency-test','Test','concurrency-test','private',1024,128,true);
      INSERT INTO public.boss_ai_allowances VALUES('concurrency-test','ai.use',1024,1024,1024,2);
    """)
    first = subprocess.Popen(PSQL, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, text=True, bufsize=1)
    first.stdin.write(f"BEGIN; SET LOCAL statement_timeout='10s';\n"
                      f"SELECT public.boss_ai_reserve('{USER}','concurrency-test','{FIRST}');\n")
    first.stdin.flush()
    # readline is bounded by the server's statement timeout and the CI step timeout.
    admitted = json.loads(first.stdout.readline())
    assert "model" in admitted, admitted
    second = subprocess.Popen(PSQL + ["-c",
        f"SET statement_timeout='10s'; SELECT public.boss_ai_reserve('{USER}','concurrency-test','{SECOND}');"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    deadline = time.monotonic() + 5
    blocked = False
    while time.monotonic() < deadline:
        blocked = query(f"""SELECT EXISTS(SELECT 1 FROM pg_stat_activity
          WHERE pid <> pg_backend_pid() AND query LIKE '%{SECOND}%'
            AND wait_event_type='Lock' AND wait_event='advisory')""") == "t"
        if blocked or second.poll() is not None:
            break
        time.sleep(0.05)
    assert blocked, "Second session did not wait on the reservation lock"
    _, error = first.communicate("COMMIT;\n\\q\n", timeout=15)
    assert first.returncode == 0, error
    output, error = second.communicate(timeout=15)
    assert second.returncode == 0, error
    assert json.loads(output)["error"] == "allowance_exceeded", output
    assert query(f"SELECT sum(charged_tokens) FROM public.boss_ai_requests WHERE user_id='{USER}'") == "1024"
    print("PASS: concurrent sessions serialize admission and cannot overspend the allowance")
finally:
    for process in [first, second]:
        if process and process.poll() is None:
            process.kill()
            process.communicate(timeout=15)
    query(f"""
      DELETE FROM public.boss_ai_requests WHERE user_id='{USER}';
      DELETE FROM public.boss_ai_allowances WHERE model_id='concurrency-test';
      DELETE FROM public.boss_ai_models WHERE id='concurrency-test';
      DELETE FROM public.boss_ai_connections WHERE id='concurrency-test';
      DELETE FROM auth.users WHERE id='{USER}';
    """)
