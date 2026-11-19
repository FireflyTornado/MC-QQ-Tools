CREATE TABLE banlist_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    generation INTEGER NOT NULL DEFAULT 0 CHECK (typeof(generation) = 'integer' AND generation >= 0),
    client_id TEXT NOT NULL DEFAULT '',
    sequence INTEGER NOT NULL DEFAULT 0 CHECK (typeof(sequence) = 'integer' AND sequence >= 0),
    digest TEXT NOT NULL DEFAULT ''
);

INSERT INTO banlist_state (id) VALUES (1);

CREATE TABLE server_bans (
    id INTEGER PRIMARY KEY,
    mc_name_key TEXT CHECK (mc_name_key IS NULL OR (
        length(mc_name_key) BETWEEN 1 AND 16
        AND mc_name_key NOT GLOB '*[^a-z0-9_]*'
        AND instr(mc_name_key, char(0)) = 0
    )),
    profile_uuid TEXT UNIQUE CHECK (profile_uuid IS NULL OR (
        length(profile_uuid) = 36
        AND substr(profile_uuid, 9, 1) = '-' AND substr(profile_uuid, 14, 1) = '-'
        AND substr(profile_uuid, 19, 1) = '-' AND substr(profile_uuid, 24, 1) = '-'
        AND length(replace(profile_uuid, '-', '')) = 32
        AND replace(profile_uuid, '-', '') NOT GLOB '*[^0-9a-f]*'
        AND instr(profile_uuid, char(0)) = 0
    )),
    state TEXT NOT NULL,
    expires_at INTEGER CHECK (expires_at IS NULL OR (typeof(expires_at) = 'integer' AND expires_at >= 0)),
    binding_id INTEGER,
    group_id TEXT,
    qq_id TEXT,
    mc_name TEXT,
    bound_at INTEGER,
    updated_at INTEGER,
    revision INTEGER NOT NULL DEFAULT 1 CHECK (typeof(revision) = 'integer' AND revision > 0),
    UNIQUE (group_id, qq_id),
    CHECK (
        (profile_uuid IS NULL AND mc_name_key IS NOT NULL AND (
            state = 'BANNED' OR (state = 'PENDING_MEMBERSHIP_CHECK' AND binding_id IS NOT NULL AND expires_at IS NULL)
        ))
        OR (profile_uuid IS NOT NULL AND binding_id IS NULL AND (
            state = 'BANNED' OR (state = 'KNOWN_PROFILE' AND mc_name_key IS NOT NULL AND expires_at IS NULL)
        ))
    ),
    CHECK (
        (binding_id IS NULL AND group_id IS NULL AND qq_id IS NULL
            AND mc_name IS NULL AND bound_at IS NULL AND updated_at IS NULL)
        OR (typeof(binding_id) = 'integer' AND typeof(group_id) = 'text' AND typeof(qq_id) = 'text'
            AND typeof(mc_name) = 'text' AND lower(mc_name) = mc_name_key AND instr(mc_name, char(0)) = 0
            AND typeof(bound_at) = 'integer' AND typeof(updated_at) = 'integer')
    )
);

CREATE UNIQUE INDEX idx_server_bans_name ON server_bans(mc_name_key) WHERE profile_uuid IS NULL;
