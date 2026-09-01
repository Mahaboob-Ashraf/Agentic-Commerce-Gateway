ALTER TABLE buyer_intent
    DROP CONSTRAINT chk_buyer_intent_goal,
    ADD CONSTRAINT chk_buyer_intent_goal CHECK (goal IN ('PURCHASE_PRODUCT', 'PURCHASE_FOOD')),
    ADD COLUMN exact_brand VARCHAR(256),
    ADD COLUMN exact_size_storage VARCHAR(128),
    ADD COLUMN exact_colour VARCHAR(128);

ALTER TABLE product_external_fact
    DROP CONSTRAINT chk_fact_type,
    ADD CONSTRAINT chk_fact_type CHECK (fact_type IN (
        'INGREDIENTS', 'ALLERGEN', 'VEGETARIAN', 'DIET', 'NUTRITION', 'PROTEIN',
        'IMAGE', 'BARCODE', 'BRAND', 'SPECIFICATION', 'RATING', 'REVIEW_COUNT'));

CREATE TABLE buyer_commerce_request (
    commerce_request_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id UUID NOT NULL,
    buyer_actor_id UUID NOT NULL,
    buyer_role VARCHAR(32) GENERATED ALWAYS AS ('BUYER') STORED,
    requested_thread_id UUID,
    thread_id UUID,
    normalized_text VARCHAR(4000) NOT NULL,
    material_hash CHAR(64) NOT NULL,
    request_status VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
    authoritative_result JSONB,
    failure_code VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_buyer_commerce_request UNIQUE (buyer_actor_id, request_id),
    CONSTRAINT uq_buyer_commerce_request_owner UNIQUE (commerce_request_id, buyer_actor_id),
    CONSTRAINT fk_buyer_commerce_request_actor FOREIGN KEY (buyer_actor_id, buyer_role)
        REFERENCES application_actor (actor_id, platform_role) ON DELETE RESTRICT,
    CONSTRAINT fk_buyer_commerce_requested_thread FOREIGN KEY (requested_thread_id, buyer_actor_id)
        REFERENCES commerce_thread (thread_id, buyer_actor_id) ON DELETE RESTRICT,
    CONSTRAINT fk_buyer_commerce_thread FOREIGN KEY (thread_id, buyer_actor_id)
        REFERENCES commerce_thread (thread_id, buyer_actor_id) ON DELETE RESTRICT,
    CONSTRAINT chk_buyer_commerce_request_text CHECK (length(btrim(normalized_text)) BETWEEN 1 AND 4000),
    CONSTRAINT chk_buyer_commerce_request_hash CHECK (material_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_buyer_commerce_request_status CHECK (
        request_status IN ('RUNNING', 'COMPLETED', 'WAITING_FOR_USER', 'FAILED')),
    CONSTRAINT chk_buyer_commerce_request_result CHECK (
        authoritative_result IS NULL OR jsonb_typeof(authoritative_result) = 'object'),
    CONSTRAINT chk_buyer_commerce_request_completion CHECK (
        (request_status = 'RUNNING' AND authoritative_result IS NULL AND completed_at IS NULL)
        OR (request_status <> 'RUNNING' AND authoritative_result IS NOT NULL AND completed_at IS NOT NULL)),
    CONSTRAINT chk_buyer_commerce_request_thread CHECK (
        request_status = 'RUNNING' OR thread_id IS NOT NULL)
);

CREATE INDEX idx_buyer_commerce_request_recovery
    ON buyer_commerce_request (buyer_actor_id, updated_at DESC, request_id);
