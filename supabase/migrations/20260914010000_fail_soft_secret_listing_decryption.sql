-- ============================================================================
-- BOSS Database Schema: fail-soft decryption in the secret listing RPCs
-- ============================================================================
-- File: 20260914010000_fail_soft_secret_listing_decryption.sql
-- Fixes: BossConsole#620.
--
-- get_user_secrets, search_user_secrets and get_user_secrets_with_shared each
-- decrypt password_encrypted and recovery_codes_encrypted with a bare call to
-- public.decrypt_text inside a set-returning query. decrypt_text RAISES on an
-- undecryptable value - a wrong key after a partial key rotation, storage
-- corruption, or (with the v2 envelope 20260914000000 introduced) an HMAC/
-- padding mismatch surfacing as a UTF-8 decode failure - and one raised row
-- aborts the WHOLE query. The caller sees every one of their secrets fail to
-- load, not just the one bad row.
--
-- twofa_secret is already read through safe_decrypt_twofa_secret (NULL on
-- failure, 20260909000000). recovery_codes_encrypted already HAS a safe
-- wrapper - safe_decrypt_recovery_codes, added in 20251023000005 with a
-- doc comment literally saying "Used internally by get_user_secrets
-- functions" - but the three RPCs below call raw decrypt_text directly
-- instead, and password_encrypted never had a safe wrapper at all. Whichever
-- later migration touched these RPCs (20260802000000_secrets_org_ownership.sql
-- onward) dropped the wrapper without anyone renaming or removing it, so it
-- has sat unused since.
--
-- Fix: a new try_decrypt_text(text) mirrors safe_decrypt_recovery_codes's
-- BEGIN/EXCEPTION WHEN OTHERS THEN RETURN NULL shape for a plain scalar
-- result, and both the password read and the recovery-codes read in all
-- three RPCs route through it (recovery codes reuse the existing
-- safe_decrypt_recovery_codes rather than a second copy). One corrupt row
-- now surfaces as a single blanked field - password NULL, recovery_codes []
-- - while every other secret in the page still loads.
--
-- Deliberately named try_decrypt_text, not safe_decrypt_text:
-- ops/rotate_master_encryption_key.sql's re-encrypt loop treats every
-- safe_decrypt_* name it finds as a mapped bespoke-format adapter (see its
-- own header comment and 20260914000000's) and refuses to run if one is
-- unmapped. try_decrypt_text wraps the SAME plain envelope
-- rotate_master_encryption_key.sql already re-encrypts directly - it must
-- not be swept into that list and made to block rotation over a name match.
--
-- The backfill/rotation paths (ops/rotate_master_encryption_key.sql) keep
-- using raw decrypt_text and are NOT changed here: those are operator-run,
-- fail closed by design, and a failure there should stop the run rather than
-- silently skip a row. This migration is scoped to the three user-facing
-- listing RPCs only.
--
-- WHAT THIS DOES NOT CHANGE: signatures, RETURNS TABLE column lists, filters,
-- joins, DISTINCT ON precedence, the (created_at DESC, id DESC) paging order
-- from 20260907000000, SECURITY DEFINER, SET search_path TO '', the owner or
-- the grants. The bodies below are the current definitions
-- (20260907000000_secrets_paging_tiebreaker.sql for get_user_secrets and
-- search_user_secrets; 20260909000000_encrypt_totp.sql for
-- get_user_secrets_with_shared, which superseded the paging migration's own
-- copy) with only the password/recovery-codes decrypt calls edited.
--
-- CREATE OR REPLACE, NOT AN EDIT TO THE HISTORICAL FILES - editing an already
-- applied migration does not change a live database and would drift the
-- recorded checksum while looking authoritative in the source.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- try_decrypt_text: NULL on any decryption failure instead of raising.
-- Locked down the same way safe_decrypt_twofa_secret is (20260909000000): no
-- client role gets EXECUTE, so this cannot become a decryption oracle
-- separate from the gated listing RPCs that call it internally as postgres.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."try_decrypt_text"("ciphertext" "text") RETURNS "text"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    IF ciphertext IS NULL THEN
        RETURN NULL;
    END IF;

    BEGIN
        RETURN public.decrypt_text(ciphertext);
    EXCEPTION
        WHEN OTHERS THEN
            RETURN NULL;
    END;
END;
$$;

ALTER FUNCTION "public"."try_decrypt_text"("ciphertext" "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."try_decrypt_text"("ciphertext" "text") IS
    'Safely decrypt a stored ciphertext value, returning NULL on any failure '
    'instead of aborting the caller (BossConsole#620). Not named safe_decrypt_* '
    '- see the function header in 20260914010000 for why.';

REVOKE EXECUTE ON FUNCTION "public"."try_decrypt_text"("ciphertext" "text")
    FROM PUBLIC, "anon", "authenticated", "service_role";
GRANT EXECUTE ON FUNCTION "public"."try_decrypt_text"("ciphertext" "text") TO "postgres";


-- ----------------------------------------------------------------------------
-- (a) get_user_secrets - verbatim from 20260907000000, decrypt calls only.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."get_user_secrets"(
    "p_limit" integer DEFAULT 50,
    "p_offset" integer DEFAULT 0
) RETURNS TABLE(
    "id" "uuid", "website" "text", "username" "text", "password" "text", "notes" "text",
    "expiration_date" timestamp with time zone, "tags" "jsonb", "metadata" "jsonb",
    "created_at" timestamp with time zone, "updated_at" timestamp with time zone,
    "org_id" "uuid", "org_slug" "text", "is_org_owned" boolean, "can_manage" boolean
)
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    RETURN QUERY
    SELECT
        s.id, s.website, s.username,
        public.try_decrypt_text(s.password_encrypted) AS password,
        s.notes, s.expiration_date,
        COALESCE((SELECT jsonb_agg(st.tag) FROM public.secret_tags st WHERE st.secret_id = s.id), '[]'::jsonb) AS tags,
        COALESCE((
            SELECT jsonb_build_object(
                'twofa_enabled', sm.twofa_enabled,
                'twofa_type', sm.twofa_type,
                'recovery_codes', public.safe_decrypt_recovery_codes(sm.recovery_codes_encrypted)
            )
            FROM public.secret_metadata sm WHERE sm.secret_id = s.id
        ), '{}'::jsonb) AS metadata,
        s.created_at, s.updated_at,
        s.org_id,
        o.slug AS org_slug,
        (s.org_id IS NOT NULL) AS is_org_owned,
        (s.user_id = auth.uid()
            OR public.is_user_admin(auth.uid())
            OR (s.org_id IS NOT NULL AND public.is_org_admin(s.org_id))) AS can_manage
    FROM public.secrets s
    LEFT JOIN public.organisations o ON o.id = s.org_id
    WHERE s.user_id = auth.uid()
       OR (s.org_id IS NOT NULL AND public.is_org_member(s.org_id))
    ORDER BY s.created_at DESC, s.id DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

ALTER FUNCTION "public"."get_user_secrets"(integer,integer) OWNER TO "postgres";


-- ----------------------------------------------------------------------------
-- (b) search_user_secrets - verbatim from 20260907000000, decrypt calls only.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."search_user_secrets"(
    "p_query" "text",
    "p_limit" integer DEFAULT 50,
    "p_offset" integer DEFAULT 0
) RETURNS TABLE(
    "id" "uuid", "website" "text", "username" "text", "password" "text", "notes" "text",
    "expiration_date" timestamp with time zone, "tags" "jsonb", "metadata" "jsonb",
    "created_at" timestamp with time zone, "updated_at" timestamp with time zone,
    "org_id" "uuid", "org_slug" "text", "is_org_owned" boolean, "can_manage" boolean
)
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    RETURN QUERY
    SELECT
        s.id, s.website, s.username,
        public.try_decrypt_text(s.password_encrypted) AS password,
        s.notes, s.expiration_date,
        COALESCE((SELECT jsonb_agg(st.tag) FROM public.secret_tags st WHERE st.secret_id = s.id), '[]'::jsonb) AS tags,
        COALESCE((
            SELECT jsonb_build_object(
                'twofa_enabled', sm.twofa_enabled,
                'twofa_type', sm.twofa_type,
                'recovery_codes', public.safe_decrypt_recovery_codes(sm.recovery_codes_encrypted)
            )
            FROM public.secret_metadata sm WHERE sm.secret_id = s.id
        ), '{}'::jsonb) AS metadata,
        s.created_at, s.updated_at,
        s.org_id,
        o.slug AS org_slug,
        (s.org_id IS NOT NULL) AS is_org_owned,
        (s.user_id = auth.uid()
            OR public.is_user_admin(auth.uid())
            OR (s.org_id IS NOT NULL AND public.is_org_admin(s.org_id))) AS can_manage
    FROM public.secrets s
    LEFT JOIN public.organisations o ON o.id = s.org_id
    WHERE (s.user_id = auth.uid()
           OR (s.org_id IS NOT NULL AND public.is_org_member(s.org_id)))
      AND (s.website ILIKE '%' || p_query || '%' OR s.username ILIKE '%' || p_query || '%')
    ORDER BY s.created_at DESC, s.id DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

ALTER FUNCTION "public"."search_user_secrets"("text",integer,integer) OWNER TO "postgres";


-- ----------------------------------------------------------------------------
-- (c) get_user_secrets_with_shared - verbatim from 20260909000000 (the
--     current definition; it superseded 20260907000000's own copy),
--     decrypt calls only.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."get_user_secrets_with_shared"(
    "p_limit" integer DEFAULT 50,
    "p_offset" integer DEFAULT 0
) RETURNS TABLE(
    "id" "uuid", "website" "text", "username" "text", "password" "text", "notes" "text",
    "expiration_date" timestamp with time zone, "tags" "jsonb", "metadata" "jsonb",
    "created_at" timestamp with time zone, "updated_at" timestamp with time zone,
    "is_owner" boolean, "shared_by_email" "text", "access_level" "text",
    "org_id" "uuid", "org_slug" "text", "is_org_owned" boolean,
    "shared_with_org_slug" "text", "can_manage" boolean
)
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    RETURN QUERY
    WITH accessible_secrets AS (
        -- Source 1: the caller's own secrets.
        SELECT s.id, TRUE AS is_owner, NULL::TEXT AS shared_by_email,
               'owner'::TEXT AS access_level, NULL::TEXT AS shared_with_org_slug, 1 AS priority
        FROM public.secrets s
        WHERE s.user_id = auth.uid()

        UNION ALL

        -- Source 4: secrets OWNED BY an organisation the caller belongs to.
        SELECT s.id, (s.user_id = auth.uid()) AS is_owner, NULL::TEXT,
               'org'::TEXT, o.slug, 2
        FROM public.secrets s
        JOIN public.organisations o ON o.id = s.org_id
        WHERE s.org_id IS NOT NULL
          AND public.is_org_member(s.org_id)

        UNION ALL

        -- Source 2: shared directly with the caller.
        SELECT s.id, FALSE, u.email, ss.access_level, NULL::TEXT, 3
        FROM public.secrets s
        JOIN public.secret_shares ss ON ss.secret_id = s.id
        JOIN auth.users u ON u.id = ss.shared_by
        WHERE ss.shared_with_user_id = auth.uid()
          AND (ss.expires_at IS NULL OR ss.expires_at > now())

        UNION ALL

        -- Source 3: shared with a role the caller holds, OR any DESCENDANT of one
        -- (20260802010000). Previously matched assigned roles only, which
        -- disagreed with how authorize() expands permissions.
        SELECT s.id, FALSE, u.email, ss.access_level, NULL::TEXT, 4
        FROM public.secrets s
        JOIN public.secret_shares ss ON ss.secret_id = s.id
        JOIN auth.users u ON u.id = ss.shared_by
        WHERE ss.shared_with_role_id IN (SELECT public.effective_share_role_ids(auth.uid()))
          AND (ss.expires_at IS NULL OR ss.expires_at > now())

        UNION ALL

        -- Source 5: shared with an organisation the caller belongs to.
        SELECT s.id, FALSE, u.email, ss.access_level, o.slug, 5
        FROM public.secrets s
        JOIN public.secret_shares ss ON ss.secret_id = s.id
        JOIN auth.users u ON u.id = ss.shared_by
        JOIN public.organisations o ON o.id = ss.shared_with_org_id
        WHERE ss.shared_with_org_id IS NOT NULL
          AND public.is_org_member(ss.shared_with_org_id)
          AND (ss.expires_at IS NULL OR ss.expires_at > now())
    ),
    unique_secrets AS (
        SELECT DISTINCT ON (a.id)
            a.id, a.is_owner, a.shared_by_email, a.access_level, a.shared_with_org_slug
        FROM accessible_secrets a
        ORDER BY a.id, a.is_owner DESC, a.priority
    )
    SELECT
        s.id, s.website, s.username,
        public.try_decrypt_text(s.password_encrypted) AS password,
        s.notes, s.expiration_date,
        COALESCE((SELECT jsonb_agg(st.tag) FROM public.secret_tags st WHERE st.secret_id = s.id), '[]'::jsonb) AS tags,
        COALESCE((
            SELECT jsonb_build_object(
                'twofa_enabled', sm.twofa_enabled,
                'twofa_type', sm.twofa_type,
                'twofa_secret', public.safe_decrypt_twofa_secret(sm.twofa_secret),
                'recovery_codes', public.safe_decrypt_recovery_codes(sm.recovery_codes_encrypted)
            )
            FROM public.secret_metadata sm WHERE sm.secret_id = s.id
        ), '{}'::jsonb) AS metadata,
        s.created_at, s.updated_at,
        us.is_owner, us.shared_by_email, us.access_level,
        s.org_id,
        o.slug AS org_slug,
        (s.org_id IS NOT NULL) AS is_org_owned,
        us.shared_with_org_slug,
        (s.user_id = auth.uid()
            OR public.is_user_admin(auth.uid())
            OR (s.org_id IS NOT NULL AND public.is_org_admin(s.org_id))) AS can_manage
    FROM unique_secrets us
    JOIN public.secrets s ON s.id = us.id
    LEFT JOIN public.organisations o ON o.id = s.org_id
    ORDER BY s.created_at DESC, s.id DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

ALTER FUNCTION "public"."get_user_secrets_with_shared"(integer,integer) OWNER TO "postgres";

-- Restate the existing RPC grants, matching the convention every migration
-- that CREATE OR REPLACEs these functions follows (20260907000000,
-- 20260909000000). The explicit-anon-grants event trigger
-- (20260908000000) already preserves pre-existing authenticated/service_role
-- privilege across a replace, but restating is the established belt-and-
-- braces here rather than relying on that alone.
GRANT EXECUTE ON FUNCTION "public"."get_user_secrets"(integer,integer)             TO "authenticated", "service_role";
GRANT EXECUTE ON FUNCTION "public"."search_user_secrets"("text",integer,integer)   TO "authenticated", "service_role";
GRANT EXECUTE ON FUNCTION "public"."get_user_secrets_with_shared"(integer,integer) TO "authenticated", "service_role";
