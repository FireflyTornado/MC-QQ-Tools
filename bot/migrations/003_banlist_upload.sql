ALTER TABLE auth_state ADD COLUMN ban_generation INTEGER NOT NULL DEFAULT 0
    CHECK (typeof(ban_generation) = 'integer' AND ban_generation >= 0);
ALTER TABLE auth_state ADD COLUMN ban_sequence INTEGER NOT NULL DEFAULT 0
    CHECK (typeof(ban_sequence) = 'integer' AND ban_sequence >= 0);
ALTER TABLE auth_state ADD COLUMN ban_client_id TEXT NOT NULL DEFAULT '';
ALTER TABLE auth_state ADD COLUMN ban_digest TEXT NOT NULL DEFAULT '';
