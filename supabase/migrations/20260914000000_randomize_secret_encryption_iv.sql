-- ============================================================================
-- BOSS Database Schema: Randomize the IV for encrypt_text/decrypt_text
-- ============================================================================
-- File: 20260914000000_randomize_secret_encryption_iv.sql
-- Fixes: BossConsole#618 (defect 1 of 3 - see that issue for the other two).
--
-- encrypt_text() called the 3-arg extensions.encrypt(plaintext, key, 'aes'),
-- which pgcrypto documents as using an all-zero IV. Encryption was therefore a
-- pure function of (plaintext, key): two equal passwords produced byte-identical
-- ciphertext. Because create_secret() encrypts server-side and RLS lets a user
-- read their own password_encrypted back, any signed-in user was an encryption
-- oracle - `SELECT password_encrypted, count(*) FROM secrets GROUP BY 1 HAVING
-- count(*) > 1` names every set of users sharing a password, from a dump alone,
-- with no key required. The same defect applied to recovery_codes_encrypted and
-- (#417) twofa_secret, wherever two rows happened to hold equal plaintext.
--
-- Fix: encrypt_text now generates a fresh random 16-byte IV per call
-- (extensions.gen_random_bytes) and uses extensions.encrypt_iv/decrypt_iv,
-- storing 'v2:' || base64(iv || ciphertext) - the same versioned-envelope
-- convention 20260909000000_encrypt_totp.sql established for this exact
-- purpose. decrypt_text dispatches on the 'v2:' prefix and falls back to the
-- original zero-IV path for anything not yet migrated, so this deploys without
-- a flag day: existing ciphertext keeps decrypting right up until the backfill
-- below converts it, and nothing but encrypt_text/decrypt_text's own behavior
-- changes for any caller.
--
-- Deliberately NOT touched here, so nobody assumes more was fixed than was:
--   - Defect 2 (encryption_key::bytea truncates the key to its first 32
--     *bytes of the string's own characters* - 32 hex characters is 128 bits,
--     not the documented 256). ops/rotate_master_encryption_key.sql's own
--     comment records that a deployed key may be hex OR base64, and this
--     migration cannot tell which is live in any given project's Vault - an
--     incorrect guess here would make get_encryption_key()'s result unusable
--     and break encryption/decryption for every row immediately on deploy.
--     Fixing defect 2 needs an operator who knows the deployed key's actual
--     encoding, not a guess baked into a migration. Left as GitHub#618's
--     still-open second defect.
--   - Defect 3 (no MAC/AEAD - a flipped ciphertext byte surfaces only as a
--     later UTF8 decode error, never as an authentication failure). Adding one
--     is a wire-format decision (where the tag lives, what key derives it)
--     that deserves its own review, not a rider on this fix.
--   - ops/rotate_master_encryption_key.sql's re-encrypt loop operates on raw
--     ciphertext bytes directly (old key -> new key) and assumes the fixed,
--     no-IV, no-prefix legacy layout for every mapped column. After the
--     backfill below, every mapped column is 'v2:'-framed, and that script
--     needs a matching update - covered by extracting the IV before
--     re-encrypting and re-attaching a fresh one - before it is next run. It
--     is a manual runbook, not a migration, so it is not applied automatically
--     and rotation must not be run against this schema until it is updated.
-- ============================================================================

CREATE OR REPLACE FUNCTION "public"."encrypt_text"("plaintext" "text") RETURNS "text"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public, pg_catalog, extensions'
    AS $$
DECLARE
    encryption_key TEXT;
    iv bytea;
    ciphertext bytea;
BEGIN
    encryption_key := public.get_encryption_key();
    -- 16 bytes: pgcrypto's AES block size, and what decrypt below expects to
    -- split back off the front of the stored envelope.
    iv := extensions.gen_random_bytes(16);
    ciphertext := extensions.encrypt_iv(
        plaintext::bytea,
        encryption_key::bytea,
        iv,
        'aes'::text
    );
    RETURN 'v2:' || pg_catalog.encode(iv || ciphertext, 'base64'::text);
END;
$$;

ALTER FUNCTION "public"."encrypt_text"("plaintext" "text") OWNER TO "postgres";

CREATE OR REPLACE FUNCTION "public"."decrypt_text"("ciphertext" "text") RETURNS "text"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public, pg_catalog, extensions'
    AS $$
DECLARE
    encryption_key TEXT;
    envelope bytea;
    iv bytea;
    body bytea;
BEGIN
    IF ciphertext IS NULL THEN
        RETURN NULL;
    END IF;

    encryption_key := public.get_encryption_key();

    IF ciphertext LIKE 'v2:%' THEN
        -- substring(x FROM y [FOR z]) is SQL-standard trailing syntax the parser recognizes only
        -- on the bare function name - schema-qualifying it (pg_catalog.substring(...)) makes FROM
        -- a syntax error, since it is then parsed as an ordinary call instead. search_path above
        -- already resolves the unqualified name to this one function (20260909000000_encrypt_totp.sql
        -- decrypt_text does the same).
        envelope := pg_catalog.decode(substring(ciphertext from 4), 'base64'::text);
        iv := substring(envelope from 1 for 16);
        body := substring(envelope from 17);
        RETURN pg_catalog.convert_from(
            extensions.decrypt_iv(body, encryption_key::bytea, iv, 'aes'::text),
            'utf8'::name
        );
    END IF;

    -- Legacy path, unchanged: a zero-IV ciphertext written before this
    -- migration, or any row the backfill below has not reached yet.
    RETURN pg_catalog.convert_from(
        extensions.decrypt(
            pg_catalog.decode(ciphertext, 'base64'::text),
            encryption_key::bytea,
            'aes'::text
        ),
        'utf8'::name
    );
END;
$$;

ALTER FUNCTION "public"."decrypt_text"("ciphertext" "text") OWNER TO "postgres";

-- ---- Backfill: convert every existing zero-IV ciphertext to the versioned,
-- random-IV envelope. Guarded by "NOT LIKE 'v2:%'" so re-running this
-- migration (or a row already migrated by a prior partial run) is a no-op,
-- matching 20260909000000_encrypt_totp.sql's own idempotency contract.

UPDATE public.secrets
SET password_encrypted = public.encrypt_text(public.decrypt_text(password_encrypted))
WHERE password_encrypted IS NOT NULL
  AND password_encrypted NOT LIKE 'v2:%';

UPDATE public.secret_metadata
SET recovery_codes_encrypted = public.encrypt_text(public.decrypt_text(recovery_codes_encrypted))
WHERE recovery_codes_encrypted IS NOT NULL
  AND recovery_codes_encrypted NOT LIKE 'v2:%';

-- twofa_secret carries its own 'v1:' envelope around an encrypt_text() payload
-- (20260909000000_encrypt_totp.sql). Re-encrypt the inner payload and keep the
-- outer 'v1:' framing exactly as-is, since safe_decrypt_twofa_secret and the
-- insert/update trigger both key off it. The trigger rejects any UPDATE whose
-- new value already carries a 'v1:' prefix ("TOTP input must be plaintext"),
-- which is correct for application writes and fatal for this backfill -
-- disable it for the duration, exactly as
-- ops/rotate_master_encryption_key.sql already does for the same reason.
ALTER TABLE public.secret_metadata DISABLE TRIGGER encrypt_twofa_secret_trigger;

UPDATE public.secret_metadata
SET twofa_secret = 'v1:' || public.encrypt_text(public.decrypt_text(substring(twofa_secret from 4)))
WHERE twofa_secret LIKE 'v1:%'
  AND substring(twofa_secret from 4) NOT LIKE 'v2:%';

ALTER TABLE public.secret_metadata ENABLE TRIGGER encrypt_twofa_secret_trigger;
