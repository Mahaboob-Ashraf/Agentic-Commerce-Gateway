CREATE TABLE actor_password_credential (
    actor_id UUID PRIMARY KEY,
    password_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    password_changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_actor_password_credential_actor FOREIGN KEY (actor_id)
        REFERENCES application_actor (actor_id) ON DELETE RESTRICT,
    CONSTRAINT chk_actor_password_credential_argon2 CHECK (
        password_hash LIKE '$argon2%'
    ),
    CONSTRAINT chk_actor_password_credential_timestamps CHECK (
        password_changed_at >= created_at
    )
);

CREATE TABLE spring_session (
    primary_id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    creation_time BIGINT NOT NULL,
    last_access_time BIGINT NOT NULL,
    max_inactive_interval INTEGER NOT NULL,
    expiry_time BIGINT NOT NULL,
    principal_name VARCHAR(320),
    CONSTRAINT pk_spring_session PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX uq_spring_session_id ON spring_session (session_id);
CREATE INDEX idx_spring_session_expiry_time ON spring_session (expiry_time);
CREATE INDEX idx_spring_session_principal_name ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BYTEA NOT NULL,
    CONSTRAINT pk_spring_session_attributes PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT fk_spring_session_attributes_session FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);
