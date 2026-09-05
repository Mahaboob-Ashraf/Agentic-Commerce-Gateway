ALTER TABLE commerce_thread_message
    DROP CONSTRAINT chk_thread_message_source,
    ADD CONSTRAINT chk_thread_message_source
        CHECK (input_source IN ('TYPED_TEXT','SARVAM_TRANSCRIPT','IMAGE_TEXT'));

ALTER TABLE buyer_intent
    ADD COLUMN excluded_materials JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT chk_buyer_intent_excluded_materials CHECK (
        jsonb_typeof(excluded_materials)='array' AND jsonb_array_length(excluded_materials)<=8);

CREATE TABLE buyer_visual_observation (
    observation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id UUID NOT NULL,
    buyer_actor_id UUID NOT NULL,
    thread_id UUID NOT NULL,
    source_message_id UUID NOT NULL,
    mime_type VARCHAR(32) NOT NULL,
    original_filename VARCHAR(255),
    size_bytes BIGINT NOT NULL,
    width_pixels INTEGER NOT NULL,
    height_pixels INTEGER NOT NULL,
    image_sha256 CHAR(64) NOT NULL,
    observation JSONB NOT NULL,
    observation_hash CHAR(64) NOT NULL,
    provider_name VARCHAR(64) NOT NULL,
    provider_model VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_buyer_visual_request UNIQUE (buyer_actor_id, request_id),
    CONSTRAINT uq_buyer_visual_message UNIQUE (source_message_id, thread_id, buyer_actor_id),
    CONSTRAINT fk_buyer_visual_request FOREIGN KEY (buyer_actor_id, request_id)
        REFERENCES buyer_commerce_request (buyer_actor_id, request_id) ON DELETE RESTRICT,
    CONSTRAINT fk_buyer_visual_message FOREIGN KEY (source_message_id, thread_id, buyer_actor_id)
        REFERENCES commerce_thread_message (message_id, thread_id, buyer_actor_id) ON DELETE RESTRICT,
    CONSTRAINT chk_buyer_visual_mime CHECK (mime_type IN ('image/jpeg','image/png','image/webp')),
    CONSTRAINT chk_buyer_visual_size CHECK (size_bytes BETWEEN 1 AND 5242880),
    CONSTRAINT chk_buyer_visual_dimensions CHECK (
        width_pixels BETWEEN 1 AND 8192 AND height_pixels BETWEEN 1 AND 8192
        AND width_pixels::BIGINT * height_pixels::BIGINT <= 25000000),
    CONSTRAINT chk_buyer_visual_hashes CHECK (
        image_sha256 ~ '^[0-9a-f]{64}$' AND observation_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_buyer_visual_observation CHECK (jsonb_typeof(observation)='object')
);

CREATE INDEX idx_buyer_visual_thread
    ON buyer_visual_observation (buyer_actor_id, thread_id, created_at DESC);
