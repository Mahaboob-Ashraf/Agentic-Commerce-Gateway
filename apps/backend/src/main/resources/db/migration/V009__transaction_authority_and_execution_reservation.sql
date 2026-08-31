ALTER TABLE agent_commerce_manifest
    ADD CONSTRAINT uq_manifest_material UNIQUE (manifest_id, merchant_id, manifest_version);

ALTER TABLE merchant_policy_snapshot
    ADD CONSTRAINT uq_policy_snapshot_material UNIQUE (
        policy_snapshot_id, merchant_id, snapshot_version, snapshot_hash);

CREATE TABLE authoritative_availability_refresh (
    availability_refresh_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id UUID NOT NULL,
    buyer_actor_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    cart_id UUID NOT NULL,
    cart_version INTEGER NOT NULL,
    cart_hash CHAR(64) NOT NULL,
    manifest_id UUID NOT NULL,
    manifest_version INTEGER NOT NULL,
    readiness_evaluation_id UUID,
    executable_mapping_proposal_id UUID,
    outcome VARCHAR(16) NOT NULL,
    reason_code VARCHAR(128) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    evidence_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_availability_refresh_owner UNIQUE (
        availability_refresh_id, thread_id, buyer_actor_id, merchant_id),
    CONSTRAINT uq_availability_refresh_thread UNIQUE (
        availability_refresh_id, thread_id, buyer_actor_id),
    CONSTRAINT uq_availability_refresh_material UNIQUE (
        availability_refresh_id, thread_id, buyer_actor_id, merchant_id,
        cart_id, cart_version, cart_hash, evidence_hash),
    CONSTRAINT fk_availability_refresh_cart FOREIGN KEY (
        cart_id, thread_id, buyer_actor_id, merchant_id, cart_version, cart_hash)
        REFERENCES candidate_cart (
            cart_id, thread_id, buyer_actor_id, merchant_id, cart_version, cart_hash)
        ON DELETE RESTRICT,
    CONSTRAINT fk_availability_refresh_manifest FOREIGN KEY (
        manifest_id, merchant_id, manifest_version)
        REFERENCES agent_commerce_manifest (manifest_id, merchant_id, manifest_version)
        ON DELETE RESTRICT,
    CONSTRAINT fk_availability_refresh_readiness FOREIGN KEY (
        readiness_evaluation_id, merchant_id)
        REFERENCES capability_readiness_evaluation (readiness_evaluation_id, merchant_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_availability_refresh_mapping FOREIGN KEY (
        executable_mapping_proposal_id, merchant_id)
        REFERENCES capability_mapping_proposal (mapping_proposal_id, merchant_id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_availability_refresh_outcome CHECK (outcome IN ('PASS','FAIL','UNKNOWN')),
    CONSTRAINT chk_availability_refresh_versions CHECK (cart_version > 0 AND manifest_version > 0),
    CONSTRAINT chk_availability_refresh_binding CHECK (
        outcome = 'UNKNOWN' OR executable_mapping_proposal_id IS NOT NULL),
    CONSTRAINT chk_availability_refresh_expiry CHECK (expires_at IS NULL OR expires_at > observed_at),
    CONSTRAINT chk_availability_refresh_hashes CHECK (
        cart_hash ~ '^[0-9a-f]{64}$' AND evidence_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_availability_refresh_thread_created
    ON authoritative_availability_refresh (thread_id, created_at DESC);

CREATE TABLE authoritative_availability_item (
    availability_item_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    availability_refresh_id UUID NOT NULL,
    thread_id UUID NOT NULL,
    buyer_actor_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    catalogue_version_id UUID NOT NULL,
    product_id UUID NOT NULL,
    merchant_sku VARCHAR(128) NOT NULL,
    variant VARCHAR(256),
    requested_quantity INTEGER NOT NULL,
    available BOOLEAN,
    authoritative_quantity BIGINT,
    outcome VARCHAR(16) NOT NULL,
    reason_code VARCHAR(128) NOT NULL,
    merchant_observed_at TIMESTAMPTZ,
    merchant_expires_at TIMESTAMPTZ,
    response_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_availability_item_product UNIQUE (availability_refresh_id, product_id),
    CONSTRAINT fk_availability_item_refresh FOREIGN KEY (
        availability_refresh_id, thread_id, buyer_actor_id, merchant_id)
        REFERENCES authoritative_availability_refresh (
            availability_refresh_id, thread_id, buyer_actor_id, merchant_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_availability_item_product FOREIGN KEY (
        product_id, merchant_id, catalogue_version_id)
        REFERENCES merchant_product (product_id, merchant_id, catalogue_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_availability_item_values CHECK (
        requested_quantity BETWEEN 1 AND 100
        AND (authoritative_quantity IS NULL OR authoritative_quantity >= 0)
        AND (merchant_expires_at IS NULL OR merchant_observed_at IS NULL
            OR merchant_expires_at > merchant_observed_at)),
    CONSTRAINT chk_availability_item_outcome CHECK (outcome IN ('PASS','FAIL','UNKNOWN')),
    CONSTRAINT chk_availability_item_truth CHECK (
        (outcome = 'PASS' AND (available IS TRUE
            OR authoritative_quantity >= requested_quantity))
        OR (outcome = 'FAIL' AND (available IS FALSE
            OR authoritative_quantity < requested_quantity))
        OR outcome = 'UNKNOWN'),
    CONSTRAINT chk_availability_item_hash CHECK (response_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE authoritative_serviceability_evidence (
    serviceability_evidence_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id UUID NOT NULL,
    buyer_actor_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    cart_id UUID NOT NULL,
    cart_version INTEGER NOT NULL,
    cart_hash CHAR(64) NOT NULL,
    manifest_id UUID NOT NULL,
    manifest_version INTEGER NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_reference VARCHAR(256),
    location_reference_hash CHAR(64) NOT NULL,
    reason_code VARCHAR(128) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    evidence_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_serviceability_owner UNIQUE (
        serviceability_evidence_id, thread_id, buyer_actor_id, merchant_id),
    CONSTRAINT uq_serviceability_material UNIQUE (
        serviceability_evidence_id, thread_id, buyer_actor_id, merchant_id,
        cart_id, cart_version, cart_hash, evidence_hash),
    CONSTRAINT fk_serviceability_cart FOREIGN KEY (
        cart_id, thread_id, buyer_actor_id, merchant_id, cart_version, cart_hash)
        REFERENCES candidate_cart (
            cart_id, thread_id, buyer_actor_id, merchant_id, cart_version, cart_hash)
        ON DELETE RESTRICT,
    CONSTRAINT fk_serviceability_manifest FOREIGN KEY (
        manifest_id, merchant_id, manifest_version)
        REFERENCES agent_commerce_manifest (manifest_id, merchant_id, manifest_version)
        ON DELETE RESTRICT,
    CONSTRAINT chk_serviceability_outcome CHECK (outcome IN ('PASS','FAIL','UNKNOWN')),
    CONSTRAINT chk_serviceability_source CHECK (
        source_type IN ('MERCHANT_API','TRUSTED_DEMO_FIXTURE','UNRESOLVED')),
    CONSTRAINT chk_serviceability_truth CHECK (
        outcome = 'UNKNOWN' OR source_type <> 'UNRESOLVED'),
    CONSTRAINT chk_serviceability_expiry CHECK (expires_at IS NULL OR expires_at > observed_at),
    CONSTRAINT chk_serviceability_hashes CHECK (
        cart_hash ~ '^[0-9a-f]{64}$'
        AND location_reference_hash ~ '^[0-9a-f]{64}$'
        AND evidence_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_serviceability_thread_created
    ON authoritative_serviceability_evidence (thread_id, created_at DESC);

ALTER TABLE constraint_certificate
    ADD COLUMN merchant_id UUID,
    ADD COLUMN availability_refresh_id UUID,
    ADD COLUMN availability_evidence_hash CHAR(64),
    ADD COLUMN serviceability_evidence_id UUID,
    ADD COLUMN serviceability_evidence_hash CHAR(64),
    ADD COLUMN executable BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE constraint_certificate DISABLE TRIGGER trg_constraint_certificate_immutable;

UPDATE constraint_certificate certificate
SET merchant_id = cart.merchant_id
FROM candidate_cart cart
WHERE cart.cart_id = certificate.cart_id;

ALTER TABLE constraint_certificate ENABLE TRIGGER trg_constraint_certificate_immutable;

ALTER TABLE constraint_certificate
    ALTER COLUMN merchant_id SET NOT NULL,
    ADD CONSTRAINT uq_constraint_certificate_material UNIQUE (
        certificate_id, thread_id, buyer_actor_id, merchant_id, certificate_hash),
    ADD CONSTRAINT fk_constraint_certificate_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchant (merchant_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_constraint_certificate_availability FOREIGN KEY (
        availability_refresh_id, thread_id, buyer_actor_id, merchant_id,
        cart_id, cart_version, cart_hash, availability_evidence_hash)
        REFERENCES authoritative_availability_refresh (
            availability_refresh_id, thread_id, buyer_actor_id, merchant_id,
            cart_id, cart_version, cart_hash, evidence_hash)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_constraint_certificate_serviceability FOREIGN KEY (
        serviceability_evidence_id, thread_id, buyer_actor_id, merchant_id,
        cart_id, cart_version, cart_hash, serviceability_evidence_hash)
        REFERENCES authoritative_serviceability_evidence (
            serviceability_evidence_id, thread_id, buyer_actor_id, merchant_id,
            cart_id, cart_version, cart_hash, evidence_hash)
        ON DELETE RESTRICT,
    ADD CONSTRAINT chk_constraint_certificate_executable CHECK (
        (NOT executable AND availability_refresh_id IS NULL
            AND availability_evidence_hash IS NULL
            AND serviceability_evidence_id IS NULL
            AND serviceability_evidence_hash IS NULL)
        OR (executable AND availability_refresh_id IS NOT NULL
            AND availability_evidence_hash IS NOT NULL
            AND serviceability_evidence_id IS NOT NULL
            AND serviceability_evidence_hash IS NOT NULL)),
    ADD CONSTRAINT chk_constraint_certificate_authority_hashes CHECK (
        (availability_evidence_hash IS NULL
            OR availability_evidence_hash ~ '^[0-9a-f]{64}$')
        AND (serviceability_evidence_hash IS NULL
            OR serviceability_evidence_hash ~ '^[0-9a-f]{64}$'));

CREATE TABLE transaction_authority_refresh (
    authority_refresh_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id UUID NOT NULL,
    buyer_actor_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    cart_id UUID NOT NULL,
    cart_version INTEGER NOT NULL,
    cart_hash CHAR(64) NOT NULL,
    quote_record_id UUID NOT NULL,
    quote_hash CHAR(64) NOT NULL,
    availability_refresh_id UUID NOT NULL,
    availability_hash CHAR(64) NOT NULL,
    serviceability_evidence_id UUID NOT NULL,
    serviceability_hash CHAR(64) NOT NULL,
    constraint_certificate_id UUID NOT NULL,
    constraint_certificate_hash CHAR(64) NOT NULL,
    manifest_id UUID NOT NULL,
    manifest_version INTEGER NOT NULL,
    policy_snapshot_id UUID NOT NULL,
    policy_snapshot_version INTEGER NOT NULL,
    policy_snapshot_hash CHAR(64) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    evidence_references JSONB NOT NULL,
    refresh_hash CHAR(64) NOT NULL,
    refreshed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_authority_refresh_owner UNIQUE (
        authority_refresh_id, thread_id, buyer_actor_id, merchant_id),
    CONSTRAINT uq_authority_refresh_thread UNIQUE (
        authority_refresh_id, thread_id, buyer_actor_id),
    CONSTRAINT uq_authority_refresh_material UNIQUE (
        authority_refresh_id, thread_id, buyer_actor_id, merchant_id, refresh_hash),
    CONSTRAINT fk_authority_refresh_cart FOREIGN KEY (
        cart_id, thread_id, buyer_actor_id, merchant_id, cart_version, cart_hash)
        REFERENCES candidate_cart (
            cart_id, thread_id, buyer_actor_id, merchant_id, cart_version, cart_hash)
        ON DELETE RESTRICT,
    CONSTRAINT fk_authority_refresh_quote FOREIGN KEY (
        quote_record_id, thread_id, buyer_actor_id, quote_hash)
        REFERENCES merchant_quote (
            quote_record_id, thread_id, buyer_actor_id, evidence_hash)
        ON DELETE RESTRICT,
    CONSTRAINT fk_authority_refresh_availability FOREIGN KEY (
        availability_refresh_id, thread_id, buyer_actor_id, merchant_id,
        cart_id, cart_version, cart_hash, availability_hash)
        REFERENCES authoritative_availability_refresh (
            availability_refresh_id, thread_id, buyer_actor_id, merchant_id,
            cart_id, cart_version, cart_hash, evidence_hash)
        ON DELETE RESTRICT,
    CONSTRAINT fk_authority_refresh_serviceability FOREIGN KEY (
        serviceability_evidence_id, thread_id, buyer_actor_id, merchant_id,
        cart_id, cart_version, cart_hash, serviceability_hash)
        REFERENCES authoritative_serviceability_evidence (
            serviceability_evidence_id, thread_id, buyer_actor_id, merchant_id,
            cart_id, cart_version, cart_hash, evidence_hash)
        ON DELETE RESTRICT,
    CONSTRAINT fk_authority_refresh_certificate FOREIGN KEY (
        constraint_certificate_id, thread_id, buyer_actor_id, merchant_id,
        constraint_certificate_hash)
        REFERENCES constraint_certificate (
            certificate_id, thread_id, buyer_actor_id, merchant_id, certificate_hash)
        ON DELETE RESTRICT,
    CONSTRAINT fk_authority_refresh_manifest FOREIGN KEY (
        manifest_id, merchant_id, manifest_version)
        REFERENCES agent_commerce_manifest (manifest_id, merchant_id, manifest_version)
        ON DELETE RESTRICT,
    CONSTRAINT fk_authority_refresh_policy FOREIGN KEY (
        policy_snapshot_id, merchant_id, policy_snapshot_version, policy_snapshot_hash)
        REFERENCES merchant_policy_snapshot (
            policy_snapshot_id, merchant_id, snapshot_version, snapshot_hash)
        ON DELETE RESTRICT,
    CONSTRAINT chk_authority_refresh_result CHECK (outcome IN ('PASS','FAIL','UNKNOWN')),
    CONSTRAINT chk_authority_refresh_versions CHECK (
        cart_version > 0 AND manifest_version > 0 AND policy_snapshot_version > 0),
    CONSTRAINT chk_authority_refresh_json CHECK (
        jsonb_typeof(evidence_references) = 'array'
        AND jsonb_array_length(evidence_references) <= 128),
    CONSTRAINT chk_authority_refresh_hashes CHECK (
        cart_hash ~ '^[0-9a-f]{64}$' AND quote_hash ~ '^[0-9a-f]{64}$'
        AND availability_hash ~ '^[0-9a-f]{64}$'
        AND serviceability_hash ~ '^[0-9a-f]{64}$'
        AND constraint_certificate_hash ~ '^[0-9a-f]{64}$'
        AND policy_snapshot_hash ~ '^[0-9a-f]{64}$'
        AND refresh_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_authority_refresh_thread_created
    ON transaction_authority_refresh (thread_id, created_at DESC);

CREATE TABLE transaction_proposal (
    proposal_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    buyer_actor_id UUID NOT NULL,
    thread_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    authority_refresh_id UUID NOT NULL,
    authority_refresh_hash CHAR(64) NOT NULL,
    intent_id UUID NOT NULL,
    intent_version INTEGER NOT NULL,
    intent_hash CHAR(64) NOT NULL,
    cart_id UUID NOT NULL,
    cart_version INTEGER NOT NULL,
    cart_hash CHAR(64) NOT NULL,
    constraint_certificate_id UUID NOT NULL,
    constraint_certificate_hash CHAR(64) NOT NULL,
    quote_record_id UUID NOT NULL,
    quote_hash CHAR(64) NOT NULL,
    merchant_quote_id VARCHAR(256) NOT NULL,
    merchant_quote_version VARCHAR(128),
    availability_refresh_id UUID NOT NULL,
    availability_hash CHAR(64) NOT NULL,
    serviceability_evidence_id UUID NOT NULL,
    serviceability_hash CHAR(64) NOT NULL,
    policy_snapshot_id UUID NOT NULL,
    policy_snapshot_version INTEGER NOT NULL,
    policy_snapshot_hash CHAR(64) NOT NULL,
    catalogue_version_id UUID NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    subtotal_minor BIGINT NOT NULL,
    tax_minor BIGINT NOT NULL,
    fees_minor BIGINT NOT NULL,
    delivery_minor BIGINT NOT NULL,
    final_amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    quote_expires_at TIMESTAMPTZ NOT NULL,
    proposal_expires_at TIMESTAMPTZ NOT NULL,
    canonical_schema_version INTEGER NOT NULL,
    canonical_material JSONB NOT NULL,
    proposal_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_transaction_proposal_owner UNIQUE (
        proposal_id, buyer_actor_id, thread_id, merchant_id),
    CONSTRAINT uq_transaction_proposal_refresh UNIQUE (authority_refresh_id),
    CONSTRAINT uq_transaction_proposal_thread UNIQUE (
        proposal_id, buyer_actor_id, thread_id),
    CONSTRAINT uq_transaction_proposal_authorization UNIQUE (
        proposal_id, buyer_actor_id, proposal_hash, action_type),
    CONSTRAINT uq_transaction_proposal_execution UNIQUE (
        proposal_id, buyer_actor_id, merchant_id, proposal_hash, action_type),
    CONSTRAINT uq_transaction_proposal_material UNIQUE (
        proposal_id, buyer_actor_id, thread_id, merchant_id, proposal_hash, action_type),
    CONSTRAINT fk_transaction_proposal_thread FOREIGN KEY (thread_id, buyer_actor_id)
        REFERENCES commerce_thread (thread_id, buyer_actor_id) ON DELETE RESTRICT,
    CONSTRAINT fk_transaction_proposal_refresh FOREIGN KEY (
        authority_refresh_id, thread_id, buyer_actor_id, merchant_id, authority_refresh_hash)
        REFERENCES transaction_authority_refresh (
            authority_refresh_id, thread_id, buyer_actor_id, merchant_id, refresh_hash)
        ON DELETE RESTRICT,
    CONSTRAINT fk_transaction_proposal_intent FOREIGN KEY (
        intent_id, thread_id, buyer_actor_id, intent_version, intent_hash)
        REFERENCES buyer_intent (
            intent_id, thread_id, buyer_actor_id, intent_version, intent_hash)
        ON DELETE RESTRICT,
    CONSTRAINT fk_transaction_proposal_cart FOREIGN KEY (
        cart_id, thread_id, buyer_actor_id, merchant_id, cart_version, cart_hash)
        REFERENCES candidate_cart (
            cart_id, thread_id, buyer_actor_id, merchant_id, cart_version, cart_hash)
        ON DELETE RESTRICT,
    CONSTRAINT fk_transaction_proposal_certificate FOREIGN KEY (
        constraint_certificate_id, thread_id, buyer_actor_id, merchant_id,
        constraint_certificate_hash)
        REFERENCES constraint_certificate (
            certificate_id, thread_id, buyer_actor_id, merchant_id, certificate_hash)
        ON DELETE RESTRICT,
    CONSTRAINT fk_transaction_proposal_quote FOREIGN KEY (
        quote_record_id, thread_id, buyer_actor_id, quote_hash)
        REFERENCES merchant_quote (
            quote_record_id, thread_id, buyer_actor_id, evidence_hash)
        ON DELETE RESTRICT,
    CONSTRAINT fk_transaction_proposal_availability FOREIGN KEY (
        availability_refresh_id, thread_id, buyer_actor_id, merchant_id,
        cart_id, cart_version, cart_hash, availability_hash)
        REFERENCES authoritative_availability_refresh (
            availability_refresh_id, thread_id, buyer_actor_id, merchant_id,
            cart_id, cart_version, cart_hash, evidence_hash)
        ON DELETE RESTRICT,
    CONSTRAINT fk_transaction_proposal_serviceability FOREIGN KEY (
        serviceability_evidence_id, thread_id, buyer_actor_id, merchant_id,
        cart_id, cart_version, cart_hash, serviceability_hash)
        REFERENCES authoritative_serviceability_evidence (
            serviceability_evidence_id, thread_id, buyer_actor_id, merchant_id,
            cart_id, cart_version, cart_hash, evidence_hash)
        ON DELETE RESTRICT,
    CONSTRAINT fk_transaction_proposal_policy FOREIGN KEY (
        policy_snapshot_id, merchant_id, policy_snapshot_version, policy_snapshot_hash)
        REFERENCES merchant_policy_snapshot (
            policy_snapshot_id, merchant_id, snapshot_version, snapshot_hash) ON DELETE RESTRICT,
    CONSTRAINT fk_transaction_proposal_catalogue FOREIGN KEY (catalogue_version_id, merchant_id)
        REFERENCES catalogue_version (catalogue_version_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT chk_transaction_proposal_action CHECK (action_type = 'PURCHASE'),
    CONSTRAINT chk_transaction_proposal_versions CHECK (
        intent_version > 0 AND cart_version > 0 AND policy_snapshot_version > 0
        AND canonical_schema_version = 1),
    CONSTRAINT chk_transaction_proposal_money CHECK (
        subtotal_minor >= 0 AND tax_minor >= 0 AND fees_minor >= 0
        AND delivery_minor >= 0 AND final_amount_minor >= 0
        AND final_amount_minor = subtotal_minor + tax_minor + fees_minor + delivery_minor
        AND currency = 'INR'),
    CONSTRAINT chk_transaction_proposal_expiry CHECK (
        proposal_expires_at <= quote_expires_at AND proposal_expires_at > created_at),
    CONSTRAINT chk_transaction_proposal_canonical CHECK (
        jsonb_typeof(canonical_material) = 'object'
        AND length(canonical_material::text) <= 65536),
    CONSTRAINT chk_transaction_proposal_hashes CHECK (
        authority_refresh_hash ~ '^[0-9a-f]{64}$'
        AND intent_hash ~ '^[0-9a-f]{64}$' AND cart_hash ~ '^[0-9a-f]{64}$'
        AND constraint_certificate_hash ~ '^[0-9a-f]{64}$'
        AND quote_hash ~ '^[0-9a-f]{64}$' AND availability_hash ~ '^[0-9a-f]{64}$'
        AND serviceability_hash ~ '^[0-9a-f]{64}$'
        AND policy_snapshot_hash ~ '^[0-9a-f]{64}$'
        AND proposal_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_transaction_proposal_thread_created
    ON transaction_proposal (thread_id, created_at DESC);

CREATE TABLE transaction_proposal_line_item (
    proposal_line_item_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    proposal_id UUID NOT NULL,
    buyer_actor_id UUID NOT NULL,
    thread_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    catalogue_version_id UUID NOT NULL,
    line_number INTEGER NOT NULL,
    product_id UUID NOT NULL,
    merchant_sku VARCHAR(128) NOT NULL,
    variant VARCHAR(256),
    quantity INTEGER NOT NULL,
    unit_amount_minor BIGINT NOT NULL,
    line_amount_minor BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_transaction_proposal_line UNIQUE (proposal_id, line_number),
    CONSTRAINT uq_transaction_proposal_product UNIQUE (proposal_id, product_id),
    CONSTRAINT fk_transaction_proposal_line_proposal FOREIGN KEY (
        proposal_id, buyer_actor_id, thread_id, merchant_id)
        REFERENCES transaction_proposal (
            proposal_id, buyer_actor_id, thread_id, merchant_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_transaction_proposal_line_product FOREIGN KEY (
        product_id, merchant_id, catalogue_version_id)
        REFERENCES merchant_product (product_id, merchant_id, catalogue_version_id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_transaction_proposal_line_values CHECK (
        line_number > 0 AND quantity BETWEEN 1 AND 100
        AND unit_amount_minor >= 0 AND line_amount_minor >= 0
        AND line_amount_minor = unit_amount_minor * quantity)
);

CREATE TABLE reversibility_evaluation (
    reversibility_evaluation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    proposal_id UUID NOT NULL,
    buyer_actor_id UUID NOT NULL,
    thread_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    proposal_hash CHAR(64) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    reason_codes JSONB NOT NULL,
    normalized_inputs JSONB NOT NULL,
    input_hash CHAR(64) NOT NULL,
    additional_confirmation_required BOOLEAN NOT NULL,
    payment_authorization_still_required BOOLEAN NOT NULL DEFAULT TRUE,
    evaluated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_reversibility_proposal UNIQUE (proposal_id),
    CONSTRAINT uq_reversibility_owner UNIQUE (
        reversibility_evaluation_id, proposal_id, buyer_actor_id, thread_id),
    CONSTRAINT fk_reversibility_proposal FOREIGN KEY (
        proposal_id, buyer_actor_id, thread_id, merchant_id, proposal_hash, action_type)
        REFERENCES transaction_proposal (
            proposal_id, buyer_actor_id, thread_id, merchant_id, proposal_hash, action_type)
        ON DELETE RESTRICT,
    CONSTRAINT chk_reversibility_outcome CHECK (
        outcome IN ('AUTO_EXECUTE','CLARIFY','EXPLICIT_CONFIRMATION','BLOCK')),
    CONSTRAINT chk_reversibility_json CHECK (
        jsonb_typeof(reason_codes) = 'array' AND jsonb_array_length(reason_codes) <= 32
        AND jsonb_typeof(normalized_inputs) = 'object'),
    CONSTRAINT chk_reversibility_semantics CHECK (
        payment_authorization_still_required
        AND additional_confirmation_required = (outcome = 'EXPLICIT_CONFIRMATION')),
    CONSTRAINT chk_reversibility_hashes CHECK (
        proposal_hash ~ '^[0-9a-f]{64}$' AND input_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE authorization_decision (
    authorization_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    buyer_actor_id UUID NOT NULL,
    session_binding_hash CHAR(64) NOT NULL,
    proposal_id UUID NOT NULL,
    proposal_hash CHAR(64) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    authorization_method VARCHAR(32) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    authorization_material JSONB NOT NULL,
    authorization_hash CHAR(64) NOT NULL,
    CONSTRAINT uq_authorization_proposal UNIQUE (proposal_id),
    CONSTRAINT uq_authorization_material UNIQUE (
        authorization_id, buyer_actor_id, proposal_id, proposal_hash, action_type, decision),
    CONSTRAINT fk_authorization_proposal FOREIGN KEY (
        proposal_id, buyer_actor_id, proposal_hash, action_type)
        REFERENCES transaction_proposal (
            proposal_id, buyer_actor_id, proposal_hash, action_type)
        ON DELETE RESTRICT,
    CONSTRAINT chk_authorization_decision CHECK (decision IN ('AUTHORIZED','DENIED')),
    CONSTRAINT chk_authorization_method CHECK (
        authorization_method IN ('EXPLICIT_CONFIRMATION','AUTO_EXECUTE_POLICY','BUYER_DENIAL')),
    CONSTRAINT chk_authorization_expiry CHECK (expires_at > issued_at),
    CONSTRAINT chk_authorization_json CHECK (
        jsonb_typeof(authorization_material) = 'object'
        AND length(authorization_material::text) <= 16384),
    CONSTRAINT chk_authorization_hashes CHECK (
        session_binding_hash ~ '^[0-9a-f]{64}$'
        AND proposal_hash ~ '^[0-9a-f]{64}$'
        AND authorization_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_authorization_buyer_issued
    ON authorization_decision (buyer_actor_id, issued_at DESC);

CREATE TABLE transaction_execution (
    execution_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    proposal_id UUID NOT NULL,
    proposal_hash CHAR(64) NOT NULL,
    buyer_actor_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    authorization_id UUID NOT NULL,
    authorization_decision VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    provider_order_reference VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_execution_proposal UNIQUE (proposal_id),
    CONSTRAINT uq_execution_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uq_execution_owner UNIQUE (
        execution_id, proposal_id, buyer_actor_id, merchant_id),
    CONSTRAINT fk_execution_proposal FOREIGN KEY (
        proposal_id, buyer_actor_id, merchant_id, proposal_hash, action_type)
        REFERENCES transaction_proposal (
            proposal_id, buyer_actor_id, merchant_id, proposal_hash, action_type)
        ON DELETE RESTRICT,
    CONSTRAINT fk_execution_authorization FOREIGN KEY (
        authorization_id, buyer_actor_id, proposal_id, proposal_hash, action_type,
        authorization_decision)
        REFERENCES authorization_decision (
            authorization_id, buyer_actor_id, proposal_id, proposal_hash, action_type, decision)
        ON DELETE RESTRICT,
    CONSTRAINT chk_execution_authorized CHECK (authorization_decision = 'AUTHORIZED'),
    CONSTRAINT chk_execution_status CHECK (status IN ('RESERVED','PAYMENT_PENDING','FAILED')),
    CONSTRAINT chk_execution_provider_state CHECK (
        status <> 'RESERVED' OR provider_order_reference IS NULL),
    CONSTRAINT chk_execution_hash CHECK (proposal_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE authorization_consumption (
    authorization_id UUID PRIMARY KEY,
    execution_id UUID NOT NULL UNIQUE,
    consumed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_authorization_consumption_authorization FOREIGN KEY (authorization_id)
        REFERENCES authorization_decision (authorization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_authorization_consumption_execution FOREIGN KEY (execution_id)
        REFERENCES transaction_execution (execution_id) ON DELETE RESTRICT
);

CREATE TABLE execution_gate_evidence (
    execution_gate_evidence_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    buyer_actor_id UUID NOT NULL,
    session_binding_hash CHAR(64) NOT NULL,
    proposal_id UUID NOT NULL,
    proposal_hash CHAR(64) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    authorization_id UUID,
    execution_id UUID,
    decision VARCHAR(16) NOT NULL,
    reason_code VARCHAR(128) NOT NULL,
    duplicate_resolution BOOLEAN NOT NULL DEFAULT FALSE,
    evidence_references JSONB NOT NULL,
    gate_hash CHAR(64) NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_gate_proposal FOREIGN KEY (
        proposal_id, buyer_actor_id, proposal_hash, action_type)
        REFERENCES transaction_proposal (
            proposal_id, buyer_actor_id, proposal_hash, action_type)
        ON DELETE RESTRICT,
    CONSTRAINT fk_gate_authorization FOREIGN KEY (authorization_id)
        REFERENCES authorization_decision (authorization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_gate_execution FOREIGN KEY (execution_id)
        REFERENCES transaction_execution (execution_id) ON DELETE RESTRICT,
    CONSTRAINT chk_gate_decision CHECK (decision IN ('ALLOW','DENY')),
    CONSTRAINT chk_gate_json CHECK (
        jsonb_typeof(evidence_references) = 'array'
        AND jsonb_array_length(evidence_references) <= 64),
    CONSTRAINT chk_gate_hashes CHECK (
        session_binding_hash ~ '^[0-9a-f]{64}$'
        AND proposal_hash ~ '^[0-9a-f]{64}$'
        AND gate_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_execution_gate_proposal_evaluated
    ON execution_gate_evidence (proposal_id, evaluated_at DESC);

ALTER TABLE commerce_thread
    ADD COLUMN current_authority_refresh_id UUID,
    ADD COLUMN current_proposal_id UUID,
    ADD COLUMN current_reversibility_evaluation_id UUID,
    ADD COLUMN current_authorization_id UUID,
    ADD COLUMN current_execution_id UUID,
    ADD CONSTRAINT fk_thread_current_authority_refresh FOREIGN KEY (
        current_authority_refresh_id, thread_id, buyer_actor_id)
        REFERENCES transaction_authority_refresh (
            authority_refresh_id, thread_id, buyer_actor_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_thread_current_proposal FOREIGN KEY (
        current_proposal_id, buyer_actor_id, thread_id)
        REFERENCES transaction_proposal (
            proposal_id, buyer_actor_id, thread_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_thread_current_risk FOREIGN KEY (
        current_reversibility_evaluation_id, current_proposal_id, buyer_actor_id, thread_id)
        REFERENCES reversibility_evaluation (
            reversibility_evaluation_id, proposal_id, buyer_actor_id, thread_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_thread_current_authorization FOREIGN KEY (current_authorization_id)
        REFERENCES authorization_decision (authorization_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_thread_current_execution FOREIGN KEY (current_execution_id)
        REFERENCES transaction_execution (execution_id) ON DELETE RESTRICT;

CREATE FUNCTION reject_transaction_authority_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'transaction authority evidence rows are immutable';
END $$;

CREATE TRIGGER trg_availability_refresh_immutable BEFORE UPDATE OR DELETE
    ON authoritative_availability_refresh FOR EACH ROW
    EXECUTE FUNCTION reject_transaction_authority_mutation();
CREATE TRIGGER trg_availability_item_immutable BEFORE UPDATE OR DELETE
    ON authoritative_availability_item FOR EACH ROW
    EXECUTE FUNCTION reject_transaction_authority_mutation();
CREATE TRIGGER trg_serviceability_immutable BEFORE UPDATE OR DELETE
    ON authoritative_serviceability_evidence FOR EACH ROW
    EXECUTE FUNCTION reject_transaction_authority_mutation();
CREATE TRIGGER trg_authority_refresh_immutable BEFORE UPDATE OR DELETE
    ON transaction_authority_refresh FOR EACH ROW
    EXECUTE FUNCTION reject_transaction_authority_mutation();
CREATE TRIGGER trg_transaction_proposal_immutable BEFORE UPDATE OR DELETE
    ON transaction_proposal FOR EACH ROW
    EXECUTE FUNCTION reject_transaction_authority_mutation();
CREATE TRIGGER trg_transaction_proposal_line_immutable BEFORE UPDATE OR DELETE
    ON transaction_proposal_line_item FOR EACH ROW
    EXECUTE FUNCTION reject_transaction_authority_mutation();
CREATE TRIGGER trg_reversibility_immutable BEFORE UPDATE OR DELETE
    ON reversibility_evaluation FOR EACH ROW
    EXECUTE FUNCTION reject_transaction_authority_mutation();
CREATE TRIGGER trg_authorization_decision_immutable BEFORE UPDATE OR DELETE
    ON authorization_decision FOR EACH ROW
    EXECUTE FUNCTION reject_transaction_authority_mutation();
CREATE TRIGGER trg_authorization_consumption_immutable BEFORE UPDATE OR DELETE
    ON authorization_consumption FOR EACH ROW
    EXECUTE FUNCTION reject_transaction_authority_mutation();
CREATE TRIGGER trg_execution_gate_immutable BEFORE UPDATE OR DELETE
    ON execution_gate_evidence FOR EACH ROW
    EXECUTE FUNCTION reject_transaction_authority_mutation();

CREATE FUNCTION protect_execution_material() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.execution_id <> NEW.execution_id
       OR OLD.proposal_id <> NEW.proposal_id
       OR OLD.proposal_hash <> NEW.proposal_hash
       OR OLD.buyer_actor_id <> NEW.buyer_actor_id
       OR OLD.merchant_id <> NEW.merchant_id
       OR OLD.action_type <> NEW.action_type
       OR OLD.authorization_id <> NEW.authorization_id
       OR OLD.idempotency_key <> NEW.idempotency_key
       OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'execution material is immutable';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_execution_material BEFORE UPDATE ON transaction_execution
    FOR EACH ROW EXECUTE FUNCTION protect_execution_material();
CREATE TRIGGER trg_execution_delete BEFORE DELETE ON transaction_execution
    FOR EACH ROW EXECUTE FUNCTION reject_transaction_authority_mutation();
