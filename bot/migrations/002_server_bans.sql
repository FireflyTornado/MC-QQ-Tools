CREATE TABLE server_bans (
    mc_name_key TEXT PRIMARY KEY NOT NULL CHECK (
        length(mc_name_key) BETWEEN 1 AND 16
        AND mc_name_key NOT GLOB '*[^a-z0-9_]*'
        AND instr(mc_name_key, char(0)) = 0
    ),
    state TEXT NOT NULL CHECK (state IN ('BANNED', 'PENDING_MEMBERSHIP_CHECK')),
    expires_at INTEGER CHECK (expires_at IS NULL OR (typeof(expires_at) = 'integer' AND expires_at >= 0)),
    binding_id INTEGER,
    group_id TEXT,
    qq_id TEXT,
    mc_name TEXT,
    bound_at INTEGER,
    updated_at INTEGER,
    revision INTEGER NOT NULL DEFAULT 1 CHECK (typeof(revision) = 'integer' AND revision > 0),
    UNIQUE (group_id, qq_id),
    CHECK (state = 'BANNED' OR (binding_id IS NOT NULL AND expires_at IS NULL)),
    CHECK (
        (binding_id IS NULL AND group_id IS NULL AND qq_id IS NULL
            AND mc_name IS NULL AND bound_at IS NULL AND updated_at IS NULL)
        OR (typeof(binding_id) = 'integer' AND typeof(group_id) = 'text' AND typeof(qq_id) = 'text'
            AND typeof(mc_name) = 'text' AND lower(mc_name) = mc_name_key AND instr(mc_name, char(0)) = 0
            AND typeof(bound_at) = 'integer' AND typeof(updated_at) = 'integer')
    )
);
