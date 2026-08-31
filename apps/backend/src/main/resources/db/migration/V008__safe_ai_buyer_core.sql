CREATE TABLE commerce_thread (
    thread_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    buyer_actor_id UUID NOT NULL,
    buyer_role VARCHAR(32) GENERATED ALWAYS AS ('BUYER') STORED,
    title VARCHAR(200) NOT NULL,
    current_state VARCHAR(32) NOT NULL DEFAULT 'UNDERSTANDING',
    current_intent_version INTEGER,
    current_cart_version INTEGER,
    current_quote_id UUID,
    current_certificate_id UUID,
    step_count INTEGER NOT NULL DEFAULT 0,
    maximum_steps INTEGER NOT NULL DEFAULT 32,
    repeated_failure_count INTEGER NOT NULL DEFAULT 0,
    wall_clock_deadline TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '30 minutes'),
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_commerce_thread_buyer UNIQUE (thread_id, buyer_actor_id),
    CONSTRAINT fk_commerce_thread_buyer_role FOREIGN KEY (buyer_actor_id, buyer_role)
        REFERENCES application_actor (actor_id, platform_role) ON DELETE RESTRICT,
    CONSTRAINT chk_commerce_thread_title CHECK (length(btrim(title)) BETWEEN 1 AND 200),
    CONSTRAINT chk_commerce_thread_state CHECK (current_state IN (
        'UNDERSTANDING','SEARCHING','CART_PROPOSED','CONSTRAINTS_VERIFIED','WAITING_FOR_USER',
        'TRANSACTION_PROPOSED','RISK_EVALUATED','READY_TO_EXECUTE')),
    CONSTRAINT chk_commerce_thread_versions CHECK (
        (current_intent_version IS NULL OR current_intent_version > 0)
        AND (current_cart_version IS NULL OR current_cart_version > 0)
        AND step_count >= 0 AND maximum_steps BETWEEN 1 AND 64
        AND repeated_failure_count BETWEEN 0 AND 3)
);

CREATE INDEX idx_commerce_thread_buyer_updated
    ON commerce_thread (buyer_actor_id, updated_at DESC, thread_id);

CREATE TABLE commerce_thread_message (
    message_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id UUID NOT NULL,
    buyer_actor_id UUID NOT NULL,
    message_number INTEGER NOT NULL,
    input_source VARCHAR(32) NOT NULL,
    normalized_text VARCHAR(4000) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_thread_message_number UNIQUE (thread_id, message_number),
    CONSTRAINT uq_thread_message_owner UNIQUE (message_id, thread_id, buyer_actor_id),
    CONSTRAINT fk_thread_message_owner FOREIGN KEY (thread_id, buyer_actor_id)
        REFERENCES commerce_thread (thread_id, buyer_actor_id) ON DELETE RESTRICT,
    CONSTRAINT chk_thread_message_number CHECK (message_number > 0),
    CONSTRAINT chk_thread_message_source CHECK (input_source IN ('TYPED_TEXT','SARVAM_TRANSCRIPT')),
    CONSTRAINT chk_thread_message_text CHECK (length(btrim(normalized_text)) BETWEEN 1 AND 4000),
    CONSTRAINT chk_thread_message_hash CHECK (content_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE buyer_intent (
    intent_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id UUID NOT NULL,
    buyer_actor_id UUID NOT NULL,
    intent_version INTEGER NOT NULL,
    source_message_id UUID NOT NULL,
    goal VARCHAR(32) NOT NULL,
    category_request VARCHAR(256),
    budget_amount_minor BIGINT,
    currency CHAR(3),
    exact_merchant_sku VARCHAR(128),
    exact_gtin VARCHAR(14),
    exact_variant VARCHAR(256),
    vegetarian BOOLEAN,
    prohibited_allergen VARCHAR(64),
    quantity INTEGER,
    people INTEGER,
    substitution_policy VARCHAR(16) NOT NULL,
    delivery_hint VARCHAR(512),
    soft_preferences JSONB NOT NULL,
    material_fields JSONB NOT NULL,
    ambiguity_state VARCHAR(16) NOT NULL,
    clarification_question VARCHAR(512),
    compiler_provider VARCHAR(64) NOT NULL,
    compiler_model VARCHAR(128) NOT NULL,
    model_output_hash CHAR(64) NOT NULL,
    intent_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_buyer_intent_version UNIQUE (thread_id, intent_version),
    CONSTRAINT uq_buyer_intent_owner UNIQUE (intent_id, thread_id, buyer_actor_id),
    CONSTRAINT uq_buyer_intent_binding UNIQUE (intent_id, thread_id, buyer_actor_id, intent_version),
    CONSTRAINT uq_buyer_intent_material UNIQUE (intent_id, thread_id, buyer_actor_id, intent_version, intent_hash),
    CONSTRAINT fk_buyer_intent_thread FOREIGN KEY (thread_id, buyer_actor_id)
        REFERENCES commerce_thread (thread_id, buyer_actor_id) ON DELETE RESTRICT,
    CONSTRAINT fk_buyer_intent_message FOREIGN KEY (source_message_id, thread_id, buyer_actor_id)
        REFERENCES commerce_thread_message (message_id, thread_id, buyer_actor_id) ON DELETE RESTRICT,
    CONSTRAINT chk_buyer_intent_version CHECK (intent_version > 0),
    CONSTRAINT chk_buyer_intent_goal CHECK (goal IN ('PURCHASE_FOOD')),
    CONSTRAINT chk_buyer_intent_money CHECK (
        (budget_amount_minor IS NULL AND currency IS NULL)
        OR (budget_amount_minor >= 0 AND currency ~ '^[A-Z]{3}$')),
    CONSTRAINT chk_buyer_intent_gtin CHECK (exact_gtin IS NULL OR exact_gtin ~ '^[0-9]{8,14}$'),
    CONSTRAINT chk_buyer_intent_counts CHECK (
        (quantity IS NULL OR quantity BETWEEN 1 AND 100)
        AND (people IS NULL OR people BETWEEN 1 AND 100)),
    CONSTRAINT chk_buyer_intent_substitution CHECK (substitution_policy IN ('ALLOW','PROHIBIT','UNKNOWN')),
    CONSTRAINT chk_buyer_intent_ambiguity CHECK (
        (ambiguity_state='CLEAR' AND clarification_question IS NULL)
        OR (ambiguity_state='AMBIGUOUS' AND length(btrim(clarification_question)) BETWEEN 1 AND 512)),
    CONSTRAINT chk_buyer_intent_json CHECK (
        jsonb_typeof(soft_preferences)='array' AND jsonb_array_length(soft_preferences)<=16
        AND jsonb_typeof(material_fields)='array' AND jsonb_array_length(material_fields) BETWEEN 1 AND 32),
    CONSTRAINT chk_buyer_intent_hashes CHECK (
        model_output_hash ~ '^[0-9a-f]{64}$' AND intent_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE buyer_agent_action (
    action_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id UUID NOT NULL,
    buyer_actor_id UUID NOT NULL,
    step_number INTEGER NOT NULL,
    state_before VARCHAR(32) NOT NULL,
    state_after VARCHAR(32) NOT NULL,
    selected_tool VARCHAR(32) NOT NULL,
    input_hash CHAR(64) NOT NULL,
    result_evidence_references JSONB NOT NULL,
    concise_rationale VARCHAR(512) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    action_signature CHAR(64) NOT NULL,
    provider_name VARCHAR(64) NOT NULL,
    provider_model VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_buyer_action_step UNIQUE (thread_id, step_number),
    CONSTRAINT fk_buyer_action_thread FOREIGN KEY (thread_id, buyer_actor_id)
        REFERENCES commerce_thread (thread_id, buyer_actor_id) ON DELETE RESTRICT,
    CONSTRAINT chk_buyer_action_step CHECK (step_number > 0),
    CONSTRAINT chk_buyer_action_states CHECK (
        state_before IN ('UNDERSTANDING','SEARCHING','CART_PROPOSED','CONSTRAINTS_VERIFIED','WAITING_FOR_USER','TRANSACTION_PROPOSED','RISK_EVALUATED','READY_TO_EXECUTE')
        AND state_after IN ('UNDERSTANDING','SEARCHING','CART_PROPOSED','CONSTRAINTS_VERIFIED','WAITING_FOR_USER','TRANSACTION_PROPOSED','RISK_EVALUATED','READY_TO_EXECUTE')),
    CONSTRAINT chk_buyer_action_tool CHECK (selected_tool IN (
        'COMPILE_INTENT','REQUEST_CLARIFICATION','DISCOVER_MERCHANTS','SEARCH_PRODUCTS',
        'BUILD_CANDIDATE_CART','GET_QUOTE','VERIFY_CONSTRAINTS')),
    CONSTRAINT chk_buyer_action_outcome CHECK (outcome IN ('SUCCESS','FAILURE','DENIED','WAITING')),
    CONSTRAINT chk_buyer_action_json CHECK (jsonb_typeof(result_evidence_references)='array' AND jsonb_array_length(result_evidence_references)<=64),
    CONSTRAINT chk_buyer_action_hashes CHECK (input_hash ~ '^[0-9a-f]{64}$' AND action_signature ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_buyer_action_thread_step ON buyer_agent_action (thread_id, step_number);

CREATE TABLE merchant_discovery_evidence (
    discovery_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id UUID NOT NULL,
    buyer_actor_id UUID NOT NULL,
    intent_id UUID NOT NULL,
    intent_version INTEGER NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    required_capabilities JSONB NOT NULL,
    eligible_merchants JSONB NOT NULL,
    evidence_references JSONB NOT NULL,
    discovery_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_discovery_owner UNIQUE (discovery_id, thread_id, buyer_actor_id),
    CONSTRAINT fk_discovery_intent FOREIGN KEY (intent_id, thread_id, buyer_actor_id)
        REFERENCES buyer_intent (intent_id, thread_id, buyer_actor_id) ON DELETE RESTRICT,
    CONSTRAINT chk_discovery_outcome CHECK (outcome IN ('ELIGIBLE','NO_ELIGIBLE_MERCHANT','NEEDS_MULTI_MERCHANT','NO_SINGLE_MERCHANT')),
    CONSTRAINT chk_discovery_json CHECK (
        jsonb_typeof(required_capabilities)='array' AND jsonb_array_length(required_capabilities)<=16
        AND jsonb_typeof(eligible_merchants)='array' AND jsonb_array_length(eligible_merchants)<=20
        AND jsonb_typeof(evidence_references)='array' AND jsonb_array_length(evidence_references)<=64),
    CONSTRAINT chk_discovery_hash CHECK (discovery_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_discovery_thread_created ON merchant_discovery_evidence (thread_id, created_at DESC);

CREATE TABLE candidate_cart (
    cart_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id UUID NOT NULL,
    buyer_actor_id UUID NOT NULL,
    intent_id UUID NOT NULL,
    intent_version INTEGER NOT NULL,
    merchant_id UUID NOT NULL,
    cart_version INTEGER NOT NULL,
    catalogue_version_id UUID NOT NULL,
    selection_evidence_references JSONB NOT NULL,
    alternatives JSONB NOT NULL,
    cart_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_candidate_cart_version UNIQUE (thread_id, cart_version),
    CONSTRAINT uq_candidate_cart_owner UNIQUE (cart_id, thread_id, buyer_actor_id),
    CONSTRAINT uq_candidate_cart_merchant UNIQUE (cart_id, merchant_id),
    CONSTRAINT uq_candidate_cart_quote UNIQUE (cart_id, thread_id, buyer_actor_id, merchant_id, cart_version, cart_hash),
    CONSTRAINT uq_candidate_cart_certificate UNIQUE (cart_id, thread_id, buyer_actor_id, cart_version, cart_hash, catalogue_version_id),
    CONSTRAINT fk_candidate_cart_intent FOREIGN KEY (intent_id, thread_id, buyer_actor_id, intent_version)
        REFERENCES buyer_intent (intent_id, thread_id, buyer_actor_id, intent_version) ON DELETE RESTRICT,
    CONSTRAINT fk_candidate_cart_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_candidate_cart_catalogue FOREIGN KEY (catalogue_version_id, merchant_id)
        REFERENCES catalogue_version (catalogue_version_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT chk_candidate_cart_version CHECK (cart_version > 0 AND intent_version > 0),
    CONSTRAINT chk_candidate_cart_json CHECK (
        jsonb_typeof(selection_evidence_references)='array' AND jsonb_array_length(selection_evidence_references)<=64
        AND jsonb_typeof(alternatives)='array' AND jsonb_array_length(alternatives)<=12),
    CONSTRAINT chk_candidate_cart_hash CHECK (cart_hash ~ '^[0-9a-f]{64}$')
);

ALTER TABLE merchant_product ADD CONSTRAINT uq_merchant_product_catalogue_binding
    UNIQUE (product_id, merchant_id, catalogue_version_id);

CREATE TABLE candidate_cart_item (
    cart_item_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cart_id UUID NOT NULL,
    thread_id UUID NOT NULL,
    buyer_actor_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    catalogue_version_id UUID NOT NULL,
    product_id UUID NOT NULL,
    merchant_sku VARCHAR(128) NOT NULL,
    variant VARCHAR(256),
    quantity INTEGER NOT NULL,
    selection_rationale VARCHAR(512) NOT NULL,
    evidence_references JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_cart_item_product UNIQUE (cart_id, product_id),
    CONSTRAINT fk_cart_item_cart FOREIGN KEY (cart_id, thread_id, buyer_actor_id)
        REFERENCES candidate_cart (cart_id, thread_id, buyer_actor_id) ON DELETE RESTRICT,
    CONSTRAINT fk_cart_item_cart_merchant FOREIGN KEY (cart_id, merchant_id)
        REFERENCES candidate_cart (cart_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_cart_item_product FOREIGN KEY (product_id, merchant_id, catalogue_version_id)
        REFERENCES merchant_product (product_id, merchant_id, catalogue_version_id) ON DELETE RESTRICT,
    CONSTRAINT chk_cart_item_quantity CHECK (quantity BETWEEN 1 AND 100),
    CONSTRAINT chk_cart_item_evidence CHECK (jsonb_typeof(evidence_references)='array' AND jsonb_array_length(evidence_references)<=32)
);

CREATE TABLE merchant_quote (
    quote_record_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    thread_id UUID NOT NULL,
    buyer_actor_id UUID NOT NULL,
    cart_id UUID NOT NULL,
    cart_version INTEGER NOT NULL,
    cart_hash CHAR(64) NOT NULL,
    merchant_quote_id VARCHAR(256) NOT NULL,
    merchant_quote_version VARCHAR(128),
    subtotal_minor BIGINT,
    tax_minor BIGINT,
    delivery_minor BIGINT,
    fees_minor BIGINT,
    final_amount_minor BIGINT,
    currency CHAR(3),
    expires_at TIMESTAMPTZ NOT NULL,
    stock_guaranteed BOOLEAN,
    price_guaranteed BOOLEAN,
    executable_mapping_proposal_id UUID NOT NULL,
    evidence_hash CHAR(64) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_merchant_quote_identity UNIQUE (merchant_id, merchant_quote_id, merchant_quote_version),
    CONSTRAINT uq_merchant_quote_owner UNIQUE (quote_record_id, thread_id, buyer_actor_id),
    CONSTRAINT uq_merchant_quote_material UNIQUE (quote_record_id, thread_id, buyer_actor_id, evidence_hash),
    CONSTRAINT fk_merchant_quote_cart FOREIGN KEY (cart_id, thread_id, buyer_actor_id, merchant_id, cart_version, cart_hash)
        REFERENCES candidate_cart (cart_id, thread_id, buyer_actor_id, merchant_id, cart_version, cart_hash) ON DELETE RESTRICT,
    CONSTRAINT fk_merchant_quote_mapping FOREIGN KEY (executable_mapping_proposal_id, merchant_id)
        REFERENCES capability_mapping_proposal (mapping_proposal_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT chk_merchant_quote_money CHECK (
        (subtotal_minor IS NULL OR subtotal_minor>=0) AND (tax_minor IS NULL OR tax_minor>=0)
        AND (delivery_minor IS NULL OR delivery_minor>=0) AND (fees_minor IS NULL OR fees_minor>=0)
        AND (final_amount_minor IS NULL OR final_amount_minor>=0)
        AND ((final_amount_minor IS NULL AND currency IS NULL) OR currency ~ '^[A-Z]{3}$')),
    CONSTRAINT chk_merchant_quote_hashes CHECK (cart_hash ~ '^[0-9a-f]{64}$' AND evidence_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_merchant_quote_thread_created ON merchant_quote (thread_id, created_at DESC);

CREATE TABLE merchant_quote_item (
    quote_item_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quote_record_id UUID NOT NULL,
    thread_id UUID NOT NULL,
    buyer_actor_id UUID NOT NULL,
    product_id UUID NOT NULL,
    merchant_sku VARCHAR(128) NOT NULL,
    quantity INTEGER NOT NULL,
    unit_amount_minor BIGINT,
    line_amount_minor BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_quote_item_product UNIQUE (quote_record_id, product_id),
    CONSTRAINT fk_quote_item_quote FOREIGN KEY (quote_record_id, thread_id, buyer_actor_id)
        REFERENCES merchant_quote (quote_record_id, thread_id, buyer_actor_id) ON DELETE RESTRICT,
    CONSTRAINT chk_quote_item_values CHECK (quantity BETWEEN 1 AND 100
        AND (unit_amount_minor IS NULL OR unit_amount_minor>=0)
        AND (line_amount_minor IS NULL OR line_amount_minor>=0))
);

CREATE TABLE constraint_certificate (
    certificate_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id UUID NOT NULL,
    buyer_actor_id UUID NOT NULL,
    certificate_version INTEGER NOT NULL,
    intent_id UUID NOT NULL,
    intent_version INTEGER NOT NULL,
    intent_hash CHAR(64) NOT NULL,
    cart_id UUID NOT NULL,
    cart_version INTEGER NOT NULL,
    cart_hash CHAR(64) NOT NULL,
    quote_record_id UUID NOT NULL,
    quote_hash CHAR(64) NOT NULL,
    catalogue_version_id UUID NOT NULL,
    policy_snapshot_id UUID,
    source_freshness JSONB NOT NULL,
    evidence_references JSONB NOT NULL,
    overall_result VARCHAR(16) NOT NULL,
    certificate_hash CHAR(64) NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_constraint_certificate_version UNIQUE (thread_id, certificate_version),
    CONSTRAINT uq_constraint_certificate_owner UNIQUE (certificate_id, thread_id, buyer_actor_id),
    CONSTRAINT fk_certificate_intent FOREIGN KEY (intent_id, thread_id, buyer_actor_id, intent_version, intent_hash)
        REFERENCES buyer_intent (intent_id, thread_id, buyer_actor_id, intent_version, intent_hash) ON DELETE RESTRICT,
    CONSTRAINT fk_certificate_cart FOREIGN KEY (cart_id, thread_id, buyer_actor_id, cart_version, cart_hash, catalogue_version_id)
        REFERENCES candidate_cart (cart_id, thread_id, buyer_actor_id, cart_version, cart_hash, catalogue_version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_certificate_quote FOREIGN KEY (quote_record_id, thread_id, buyer_actor_id, quote_hash)
        REFERENCES merchant_quote (quote_record_id, thread_id, buyer_actor_id, evidence_hash) ON DELETE RESTRICT,
    CONSTRAINT chk_certificate_version CHECK (certificate_version>0 AND intent_version>0 AND cart_version>0),
    CONSTRAINT chk_certificate_result CHECK (overall_result IN ('PASS','FAIL','UNKNOWN')),
    CONSTRAINT chk_certificate_json CHECK (jsonb_typeof(source_freshness)='object'
        AND jsonb_typeof(evidence_references)='array' AND jsonb_array_length(evidence_references)<=128),
    CONSTRAINT chk_certificate_hashes CHECK (
        intent_hash ~ '^[0-9a-f]{64}$' AND cart_hash ~ '^[0-9a-f]{64}$'
        AND quote_hash ~ '^[0-9a-f]{64}$' AND certificate_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE constraint_result (
    constraint_result_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    certificate_id UUID NOT NULL,
    thread_id UUID NOT NULL,
    buyer_actor_id UUID NOT NULL,
    constraint_key VARCHAR(128) NOT NULL,
    constraint_type VARCHAR(32) NOT NULL,
    normalized_requirement JSONB NOT NULL,
    result VARCHAR(16) NOT NULL,
    safety_critical BOOLEAN NOT NULL,
    evidence_references JSONB NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_constraint_result_key UNIQUE (certificate_id, constraint_key),
    CONSTRAINT fk_constraint_result_certificate FOREIGN KEY (certificate_id, thread_id, buyer_actor_id)
        REFERENCES constraint_certificate (certificate_id, thread_id, buyer_actor_id) ON DELETE RESTRICT,
    CONSTRAINT chk_constraint_result_type CHECK (constraint_type IN ('USER','BUYER_AUTHORITY','MERCHANT_PRODUCT','SAFETY_COMPLIANCE')),
    CONSTRAINT chk_constraint_result_result CHECK (result IN ('PASS','FAIL','UNKNOWN')),
    CONSTRAINT chk_constraint_result_json CHECK (jsonb_typeof(normalized_requirement)='object'
        AND jsonb_typeof(evidence_references)='array' AND jsonb_array_length(evidence_references)<=64)
);

ALTER TABLE commerce_thread ADD CONSTRAINT fk_thread_current_quote
    FOREIGN KEY (current_quote_id, thread_id, buyer_actor_id)
    REFERENCES merchant_quote (quote_record_id, thread_id, buyer_actor_id) ON DELETE RESTRICT;
ALTER TABLE commerce_thread ADD CONSTRAINT fk_thread_current_certificate
    FOREIGN KEY (current_certificate_id, thread_id, buyer_actor_id)
    REFERENCES constraint_certificate (certificate_id, thread_id, buyer_actor_id) ON DELETE RESTRICT;

CREATE FUNCTION reject_buyer_evidence_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'buyer evidence rows are immutable';
END $$;

CREATE TRIGGER trg_thread_message_immutable BEFORE UPDATE OR DELETE ON commerce_thread_message
    FOR EACH ROW EXECUTE FUNCTION reject_buyer_evidence_mutation();
CREATE TRIGGER trg_buyer_intent_immutable BEFORE UPDATE OR DELETE ON buyer_intent
    FOR EACH ROW EXECUTE FUNCTION reject_buyer_evidence_mutation();
CREATE TRIGGER trg_buyer_action_immutable BEFORE UPDATE OR DELETE ON buyer_agent_action
    FOR EACH ROW EXECUTE FUNCTION reject_buyer_evidence_mutation();
CREATE TRIGGER trg_discovery_immutable BEFORE UPDATE OR DELETE ON merchant_discovery_evidence
    FOR EACH ROW EXECUTE FUNCTION reject_buyer_evidence_mutation();
CREATE TRIGGER trg_candidate_cart_immutable BEFORE UPDATE OR DELETE ON candidate_cart
    FOR EACH ROW EXECUTE FUNCTION reject_buyer_evidence_mutation();
CREATE TRIGGER trg_candidate_cart_item_immutable BEFORE UPDATE OR DELETE ON candidate_cart_item
    FOR EACH ROW EXECUTE FUNCTION reject_buyer_evidence_mutation();
CREATE TRIGGER trg_merchant_quote_immutable BEFORE UPDATE OR DELETE ON merchant_quote
    FOR EACH ROW EXECUTE FUNCTION reject_buyer_evidence_mutation();
CREATE TRIGGER trg_merchant_quote_item_immutable BEFORE UPDATE OR DELETE ON merchant_quote_item
    FOR EACH ROW EXECUTE FUNCTION reject_buyer_evidence_mutation();
CREATE TRIGGER trg_constraint_certificate_immutable BEFORE UPDATE OR DELETE ON constraint_certificate
    FOR EACH ROW EXECUTE FUNCTION reject_buyer_evidence_mutation();
CREATE TRIGGER trg_constraint_result_immutable BEFORE UPDATE OR DELETE ON constraint_result
    FOR EACH ROW EXECUTE FUNCTION reject_buyer_evidence_mutation();
