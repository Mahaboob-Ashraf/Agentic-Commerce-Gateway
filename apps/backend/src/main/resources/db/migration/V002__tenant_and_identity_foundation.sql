CREATE TABLE merchant (
    merchant_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_key VARCHAR(100) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_merchant_key UNIQUE (merchant_key),
    CONSTRAINT chk_merchant_key_canonical CHECK (
        merchant_key = lower(btrim(merchant_key))
        AND merchant_key ~ '^[a-z0-9][a-z0-9-]{2,99}$'
    ),
    CONSTRAINT chk_merchant_display_name_nonblank CHECK (
        char_length(btrim(display_name)) BETWEEN 1 AND 200
    )
);

CREATE TABLE application_actor (
    actor_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    identity_handle VARCHAR(320) NOT NULL,
    platform_role VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_application_actor_identity_handle UNIQUE (identity_handle),
    CONSTRAINT uq_application_actor_id_role UNIQUE (actor_id, platform_role),
    CONSTRAINT chk_application_actor_identity_handle_canonical CHECK (
        identity_handle = lower(btrim(identity_handle))
        AND char_length(identity_handle) BETWEEN 3 AND 320
        AND identity_handle ~ '^[a-z0-9][a-z0-9._@+-]+$'
    ),
    CONSTRAINT chk_application_actor_platform_role CHECK (
        platform_role IN ('BUYER', 'MERCHANT_ADMIN', 'PLATFORM_ADMIN', 'SYSTEM')
    )
);

CREATE TABLE merchant_admin_membership (
    merchant_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    actor_role VARCHAR(32) GENERATED ALWAYS AS ('MERCHANT_ADMIN') STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_merchant_admin_membership PRIMARY KEY (merchant_id, actor_id),
    CONSTRAINT fk_merchant_admin_membership_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchant (merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_merchant_admin_membership_actor_role FOREIGN KEY (actor_id, actor_role)
        REFERENCES application_actor (actor_id, platform_role) ON DELETE RESTRICT
);

CREATE INDEX idx_merchant_admin_membership_actor
    ON merchant_admin_membership (actor_id, merchant_id);
