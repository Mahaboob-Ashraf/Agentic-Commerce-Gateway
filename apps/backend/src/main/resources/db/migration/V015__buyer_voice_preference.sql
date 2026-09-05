CREATE TABLE buyer_voice_preference (
    buyer_actor_id UUID PRIMARY KEY REFERENCES application_actor(actor_id) ON DELETE RESTRICT,
    voice_name VARCHAR(32) NOT NULL,
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_buyer_voice_name CHECK (voice_name IN ('Kore','Aoede','Puck','Charon'))
);
