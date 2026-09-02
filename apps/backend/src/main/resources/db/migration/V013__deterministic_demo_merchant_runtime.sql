CREATE TABLE demo_merchant_profile (
    merchant_id UUID PRIMARY KEY REFERENCES merchant (merchant_id) ON DELETE RESTRICT,
    profile_code VARCHAR(32) NOT NULL UNIQUE,
    cancellation_allowed BOOLEAN NOT NULL,
    returns_allowed BOOLEAN NOT NULL,
    perishable_returns_allowed BOOLEAN NOT NULL,
    delivery_minutes INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_demo_profile_code CHECK (profile_code IN ('AMAZING', 'FRESH_BASKET')),
    CONSTRAINT chk_demo_delivery_minutes CHECK (delivery_minutes BETWEEN 10 AND 10080)
);

CREATE TABLE demo_merchant_inventory (
    merchant_id UUID NOT NULL,
    catalogue_version_id UUID NOT NULL,
    product_id UUID NOT NULL,
    merchant_sku VARCHAR(128) NOT NULL,
    available_quantity BIGINT NOT NULL,
    inventory_version BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (merchant_id, product_id),
    CONSTRAINT fk_demo_inventory_product FOREIGN KEY (product_id, merchant_id)
        REFERENCES merchant_product (product_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_demo_inventory_catalogue FOREIGN KEY (catalogue_version_id, merchant_id)
        REFERENCES catalogue_version (catalogue_version_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT uq_demo_inventory_sku UNIQUE (merchant_id, merchant_sku),
    CONSTRAINT chk_demo_inventory_quantity CHECK (available_quantity >= 0),
    CONSTRAINT chk_demo_inventory_version CHECK (inventory_version > 0)
);

CREATE TABLE demo_merchant_order (
    demo_order_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL REFERENCES merchant (merchant_id) ON DELETE RESTRICT,
    merchant_operation_id VARCHAR(256) NOT NULL,
    merchant_order_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    customer_reference VARCHAR(256),
    line_items JSONB NOT NULL,
    total_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    order_state VARCHAR(32) NOT NULL,
    stock_released BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_demo_order_operation UNIQUE (merchant_id, merchant_operation_id),
    CONSTRAINT uq_demo_order_identity UNIQUE (merchant_id, merchant_order_id),
    CONSTRAINT chk_demo_order_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_demo_order_items CHECK (jsonb_typeof(line_items) = 'array' AND jsonb_array_length(line_items) BETWEEN 1 AND 100),
    CONSTRAINT chk_demo_order_money CHECK (total_minor >= 0 AND currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_demo_order_state CHECK (order_state IN ('PLACED', 'CANCELLED', 'RETURN_REQUESTED'))
);

CREATE INDEX idx_demo_order_lookup ON demo_merchant_order (merchant_id, merchant_order_id);

CREATE TABLE demo_bootstrap_completion (
    bootstrap_key VARCHAR(64) PRIMARY KEY,
    fixture_version VARCHAR(64) NOT NULL,
    buyer_actor_id UUID NOT NULL REFERENCES application_actor (actor_id) ON DELETE RESTRICT,
    summary JSONB NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_demo_bootstrap_summary CHECK (jsonb_typeof(summary) = 'object')
);
