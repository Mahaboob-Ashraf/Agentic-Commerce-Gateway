CREATE TABLE buyer_profile (
    buyer_actor_id UUID PRIMARY KEY REFERENCES application_actor(actor_id) ON DELETE RESTRICT,
    recipient_name VARCHAR(160) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    email VARCHAR(320) NOT NULL,
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE buyer_address (
    address_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    buyer_actor_id UUID NOT NULL REFERENCES application_actor(actor_id) ON DELETE RESTRICT,
    label VARCHAR(64) NOT NULL,
    recipient_name VARCHAR(160) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    address_line_1 VARCHAR(256) NOT NULL,
    address_line_2 VARCHAR(256),
    locality VARCHAR(128) NOT NULL,
    city VARCHAR(128) NOT NULL,
    state VARCHAR(128) NOT NULL,
    postal_code CHAR(6) NOT NULL CHECK (postal_code ~ '^[1-9][0-9]{5}$'),
    country CHAR(2) NOT NULL DEFAULT 'IN' CHECK (country = 'IN'),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    selected BOOLEAN NOT NULL DEFAULT FALSE,
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (address_id, buyer_actor_id)
);

CREATE UNIQUE INDEX uq_buyer_selected_address ON buyer_address(buyer_actor_id)
    WHERE selected AND active;

CREATE TABLE merchant_account_link (
    merchant_account_link_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    buyer_actor_id UUID NOT NULL REFERENCES application_actor(actor_id) ON DELETE RESTRICT,
    merchant_id UUID NOT NULL REFERENCES merchant(merchant_id) ON DELETE RESTRICT,
    external_customer_reference VARCHAR(256) NOT NULL,
    delegated_credential_reference VARCHAR(256) NOT NULL,
    link_method VARCHAR(32) NOT NULL CHECK (link_method IN ('TRUSTED_DEMO','OAUTH','DELEGATED_TOKEN')),
    link_version INTEGER NOT NULL CHECK (link_version > 0),
    link_hash CHAR(64) NOT NULL CHECK (link_hash ~ '^[0-9a-f]{64}$'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('LINKED','EXPIRED','REVOKED','FAILED')),
    linked_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    UNIQUE (buyer_actor_id, merchant_id, link_version),
    UNIQUE (merchant_account_link_id, buyer_actor_id, merchant_id),
    CHECK ((status='REVOKED') = (revoked_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_active_buyer_merchant_link ON merchant_account_link(buyer_actor_id, merchant_id)
    WHERE status='LINKED';

CREATE TABLE fulfilment_snapshot (
    fulfilment_snapshot_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    buyer_actor_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    source_address_id UUID NOT NULL,
    source_address_version INTEGER NOT NULL,
    merchant_account_link_id UUID NOT NULL,
    merchant_account_link_version INTEGER NOT NULL,
    merchant_account_link_hash CHAR(64) NOT NULL,
    external_customer_reference VARCHAR(256) NOT NULL,
    recipient_name VARCHAR(160) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    address_line_1 VARCHAR(256) NOT NULL,
    address_line_2 VARCHAR(256),
    locality VARCHAR(128) NOT NULL,
    city VARCHAR(128) NOT NULL,
    state VARCHAR(128) NOT NULL,
    postal_code CHAR(6) NOT NULL,
    country CHAR(2) NOT NULL CHECK (country='IN'),
    delivery_option VARCHAR(64) NOT NULL,
    snapshot_hash CHAR(64) NOT NULL CHECK (snapshot_hash ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (fulfilment_snapshot_id, buyer_actor_id, merchant_id),
    FOREIGN KEY (source_address_id, buyer_actor_id)
        REFERENCES buyer_address(address_id, buyer_actor_id) ON DELETE RESTRICT,
    FOREIGN KEY (merchant_account_link_id, buyer_actor_id, merchant_id)
        REFERENCES merchant_account_link(merchant_account_link_id, buyer_actor_id, merchant_id) ON DELETE RESTRICT
);

ALTER TABLE authoritative_serviceability_evidence
    ADD COLUMN fulfilment_snapshot_id UUID REFERENCES fulfilment_snapshot(fulfilment_snapshot_id),
    ADD COLUMN fulfilment_snapshot_hash CHAR(64),
    ADD COLUMN delivery_option VARCHAR(64);

CREATE TABLE purchase_fulfilment_authority (
    authority_refresh_id UUID PRIMARY KEY REFERENCES transaction_authority_refresh(authority_refresh_id) ON DELETE RESTRICT,
    buyer_actor_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    cart_id UUID NOT NULL,
    quote_record_id UUID NOT NULL,
    serviceability_evidence_id UUID NOT NULL,
    fulfilment_snapshot_id UUID NOT NULL,
    fulfilment_snapshot_hash CHAR(64) NOT NULL,
    merchant_account_link_id UUID NOT NULL,
    merchant_account_link_version INTEGER NOT NULL,
    merchant_account_link_hash CHAR(64) NOT NULL,
    delivery_option VARCHAR(64) NOT NULL,
    binding_hash CHAR(64) NOT NULL CHECK (binding_hash ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (fulfilment_snapshot_id, buyer_actor_id, merchant_id)
        REFERENCES fulfilment_snapshot(fulfilment_snapshot_id, buyer_actor_id, merchant_id) ON DELETE RESTRICT,
    FOREIGN KEY (merchant_account_link_id, buyer_actor_id, merchant_id)
        REFERENCES merchant_account_link(merchant_account_link_id, buyer_actor_id, merchant_id) ON DELETE RESTRICT
);

CREATE TABLE transaction_proposal_fulfilment (
    proposal_id UUID PRIMARY KEY REFERENCES transaction_proposal(proposal_id) ON DELETE RESTRICT,
    buyer_actor_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    authority_refresh_id UUID NOT NULL REFERENCES purchase_fulfilment_authority(authority_refresh_id) ON DELETE RESTRICT,
    fulfilment_snapshot_id UUID NOT NULL REFERENCES fulfilment_snapshot(fulfilment_snapshot_id) ON DELETE RESTRICT,
    fulfilment_snapshot_hash CHAR(64) NOT NULL,
    merchant_account_link_id UUID NOT NULL REFERENCES merchant_account_link(merchant_account_link_id) ON DELETE RESTRICT,
    merchant_account_link_version INTEGER NOT NULL,
    merchant_account_link_hash CHAR(64) NOT NULL,
    delivery_option VARCHAR(64) NOT NULL,
    binding_hash CHAR(64) NOT NULL CHECK (binding_hash ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE merchant_order_observation (
    merchant_order_observation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    execution_id UUID NOT NULL REFERENCES transaction_execution(execution_id) ON DELETE RESTRICT,
    merchant_finalization_id UUID NOT NULL REFERENCES merchant_finalization(merchant_finalization_id) ON DELETE RESTRICT,
    buyer_actor_id UUID NOT NULL REFERENCES application_actor(actor_id) ON DELETE RESTRICT,
    merchant_id UUID NOT NULL REFERENCES merchant(merchant_id) ON DELETE RESTRICT,
    merchant_order_id VARCHAR(256) NOT NULL,
    external_customer_reference VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('PLACED','PROCESSING','SHIPPED','DELIVERED','CANCELLED','RETURN_REQUESTED','RETURN_APPROVED','RETURN_RECEIVED')),
    source VARCHAR(32) NOT NULL CHECK (source IN ('MERCHANT_RESPONSE','MERCHANT_API','TRUSTED_DEMO_FIXTURE')),
    source_reference VARCHAR(128) NOT NULL,
    evidence_hash CHAR(64) NOT NULL CHECK (evidence_hash ~ '^[0-9a-f]{64}$'),
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (merchant_finalization_id, source, source_reference, evidence_hash)
);

CREATE INDEX idx_merchant_order_observation_current
    ON merchant_order_observation(merchant_finalization_id, observed_at DESC, created_at DESC);

CREATE TABLE lifecycle_intent (
    lifecycle_intent_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    buyer_actor_id UUID NOT NULL REFERENCES application_actor(actor_id) ON DELETE RESTRICT,
    thread_id UUID NOT NULL REFERENCES commerce_thread(thread_id) ON DELETE RESTRICT,
    merchant_finalization_id UUID REFERENCES merchant_finalization(merchant_finalization_id) ON DELETE RESTRICT,
    action_type VARCHAR(32) NOT NULL CHECK (action_type IN ('TRACK_ORDER','CANCEL_ORDER','RETURN_ORDER','REFUND_ORDER','REORDER','REPLACE_ITEM')),
    target_scope VARCHAR(32) NOT NULL CHECK (target_scope IN ('FULL_ORDER','PARTIAL_UNSUPPORTED','UNRESOLVED')),
    resolution_status VARCHAR(32) NOT NULL CHECK (resolution_status IN ('RESOLVED','CLARIFICATION_REQUIRED','UNSUPPORTED')),
    source_text_hash CHAR(64) NOT NULL,
    source_evidence JSONB NOT NULL CHECK (jsonb_typeof(source_evidence)='object' AND length(source_evidence::text)<=8192),
    confidence NUMERIC(5,4),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE lifecycle_policy_evaluation (
    lifecycle_policy_evaluation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lifecycle_intent_id UUID NOT NULL REFERENCES lifecycle_intent(lifecycle_intent_id) ON DELETE RESTRICT,
    buyer_actor_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    policy_snapshot_id UUID NOT NULL REFERENCES merchant_policy_snapshot(policy_snapshot_id) ON DELETE RESTRICT,
    policy_snapshot_version INTEGER NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('PASS','FAIL','UNKNOWN')),
    reason_code VARCHAR(128) NOT NULL,
    evidence_references JSONB NOT NULL CHECK (jsonb_typeof(evidence_references)='array'),
    evaluation_hash CHAR(64) NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE lifecycle_proposal (
    lifecycle_proposal_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lifecycle_intent_id UUID NOT NULL REFERENCES lifecycle_intent(lifecycle_intent_id) ON DELETE RESTRICT,
    lifecycle_policy_evaluation_id UUID NOT NULL REFERENCES lifecycle_policy_evaluation(lifecycle_policy_evaluation_id) ON DELETE RESTRICT,
    buyer_actor_id UUID NOT NULL REFERENCES application_actor(actor_id) ON DELETE RESTRICT,
    thread_id UUID NOT NULL REFERENCES commerce_thread(thread_id) ON DELETE RESTRICT,
    merchant_id UUID NOT NULL REFERENCES merchant(merchant_id) ON DELETE RESTRICT,
    merchant_finalization_id UUID NOT NULL REFERENCES merchant_finalization(merchant_finalization_id) ON DELETE RESTRICT,
    original_proposal_id UUID NOT NULL REFERENCES transaction_proposal(proposal_id) ON DELETE RESTRICT,
    original_execution_id UUID NOT NULL REFERENCES transaction_execution(execution_id) ON DELETE RESTRICT,
    original_payment_control_id UUID NOT NULL REFERENCES payment_control(payment_control_id) ON DELETE RESTRICT,
    action_type VARCHAR(32) NOT NULL CHECK (action_type IN ('CANCEL_ORDER','RETURN_ORDER','REFUND_ORDER')),
    target_scope VARCHAR(16) NOT NULL CHECK (target_scope='FULL_ORDER'),
    refundable_amount_minor BIGINT,
    currency CHAR(3) NOT NULL,
    policy_snapshot_id UUID NOT NULL REFERENCES merchant_policy_snapshot(policy_snapshot_id) ON DELETE RESTRICT,
    policy_snapshot_version INTEGER NOT NULL,
    merchant_account_link_id UUID NOT NULL REFERENCES merchant_account_link(merchant_account_link_id) ON DELETE RESTRICT,
    merchant_account_link_version INTEGER NOT NULL,
    canonical_schema_version INTEGER NOT NULL,
    canonical_material JSONB NOT NULL CHECK (jsonb_typeof(canonical_material)='object'),
    proposal_hash CHAR(64) NOT NULL CHECK (proposal_hash ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (buyer_actor_id, lifecycle_intent_id, action_type)
);

CREATE TABLE lifecycle_authorization_decision (
    lifecycle_authorization_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    buyer_actor_id UUID NOT NULL REFERENCES application_actor(actor_id) ON DELETE RESTRICT,
    session_binding_hash CHAR(64) NOT NULL,
    lifecycle_proposal_id UUID NOT NULL REFERENCES lifecycle_proposal(lifecycle_proposal_id) ON DELETE RESTRICT,
    lifecycle_proposal_hash CHAR(64) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('AUTHORIZED','DENIED')),
    authorization_hash CHAR(64) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    consumed_by_execution_id UUID,
    UNIQUE (buyer_actor_id, lifecycle_proposal_id)
);

CREATE TABLE lifecycle_execution (
    lifecycle_execution_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lifecycle_proposal_id UUID NOT NULL UNIQUE REFERENCES lifecycle_proposal(lifecycle_proposal_id) ON DELETE RESTRICT,
    lifecycle_proposal_hash CHAR(64) NOT NULL,
    lifecycle_authorization_id UUID NOT NULL UNIQUE REFERENCES lifecycle_authorization_decision(lifecycle_authorization_id) ON DELETE RESTRICT,
    buyer_actor_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    merchant_finalization_id UUID NOT NULL REFERENCES merchant_finalization(merchant_finalization_id) ON DELETE RESTRICT,
    action_type VARCHAR(32) NOT NULL,
    merchant_operation_id VARCHAR(128) NOT NULL UNIQUE,
    state VARCHAR(32) NOT NULL CHECK (state IN ('RESERVED','SUBMITTED','SUCCEEDED','FAILED_RETRYABLE','FAILED_TERMINAL')),
    response_reference VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE lifecycle_authorization_consumption (
    lifecycle_authorization_id UUID PRIMARY KEY REFERENCES lifecycle_authorization_decision(lifecycle_authorization_id) ON DELETE RESTRICT,
    lifecycle_execution_id UUID NOT NULL UNIQUE REFERENCES lifecycle_execution(lifecycle_execution_id) ON DELETE RESTRICT,
    consumed_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE refund_ledger (
    payment_control_id UUID PRIMARY KEY REFERENCES payment_control(payment_control_id) ON DELETE RESTRICT,
    buyer_actor_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    captured_refundable_amount_minor BIGINT NOT NULL CHECK (captured_refundable_amount_minor > 0),
    currency CHAR(3) NOT NULL,
    reserved_amount_minor BIGINT NOT NULL DEFAULT 0 CHECK (reserved_amount_minor >= 0),
    completed_amount_minor BIGINT NOT NULL DEFAULT 0 CHECK (completed_amount_minor >= 0),
    version INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (reserved_amount_minor + completed_amount_minor <= captured_refundable_amount_minor)
);

CREATE TABLE refund_execution (
    refund_execution_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lifecycle_proposal_id UUID NOT NULL UNIQUE REFERENCES lifecycle_proposal(lifecycle_proposal_id) ON DELETE RESTRICT,
    lifecycle_proposal_hash CHAR(64) NOT NULL,
    lifecycle_execution_id UUID NOT NULL UNIQUE REFERENCES lifecycle_execution(lifecycle_execution_id) ON DELETE RESTRICT,
    payment_control_id UUID NOT NULL REFERENCES refund_ledger(payment_control_id) ON DELETE RESTRICT,
    buyer_actor_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    provider_payment_id VARCHAR(128) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency CHAR(3) NOT NULL,
    provider_idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    request_body JSONB NOT NULL CHECK (jsonb_typeof(request_body)='object'),
    request_hash CHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL CHECK (state IN ('REFUND_PROPOSED','REFUND_INITIATED','REFUND_PENDING','REFUNDED','REFUND_FAILED','MANUAL_REVIEW')),
    provider_refund_id VARCHAR(128),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 5),
    next_attempt_at TIMESTAMPTZ,
    deadline_at TIMESTAMPTZ NOT NULL,
    last_error_code VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE refund_provider_evidence (
    refund_evidence_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    refund_execution_id UUID NOT NULL REFERENCES refund_execution(refund_execution_id) ON DELETE RESTRICT,
    payment_configuration_id UUID NOT NULL REFERENCES merchant_payment_configuration(payment_configuration_id) ON DELETE RESTRICT,
    provider_refund_id VARCHAR(128) NOT NULL,
    provider_payment_id VARCHAR(128) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    provider_status VARCHAR(32) NOT NULL,
    provider_account_reference VARCHAR(128) NOT NULL,
    source VARCHAR(32) NOT NULL CHECK (source IN ('CREATE_RESPONSE','WEBHOOK','API_RECONCILIATION')),
    source_reference VARCHAR(128) NOT NULL,
    evidence_hash CHAR(64) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (refund_execution_id, source, source_reference, evidence_hash)
);

ALTER TABLE transactional_outbox DROP CONSTRAINT chk_outbox_work_type;
ALTER TABLE transactional_outbox
    ADD COLUMN refund_execution_id UUID REFERENCES refund_execution(refund_execution_id) ON DELETE RESTRICT,
    ADD CONSTRAINT chk_outbox_work_type CHECK (work_type IN ('FINALIZE_MERCHANT_ORDER','SUBMIT_REFUND','RECONCILE_REFUND')),
    ADD CONSTRAINT chk_outbox_reference CHECK (
        (work_type='FINALIZE_MERCHANT_ORDER' AND refund_execution_id IS NULL)
        OR (work_type IN ('SUBMIT_REFUND','RECONCILE_REFUND') AND refund_execution_id IS NOT NULL));
CREATE UNIQUE INDEX uq_outbox_refund_work ON transactional_outbox(refund_execution_id, work_type)
    WHERE refund_execution_id IS NOT NULL;

CREATE TABLE autobuy_plan (
    autobuy_plan_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    buyer_actor_id UUID NOT NULL REFERENCES application_actor(actor_id) ON DELETE RESTRICT,
    merchant_id UUID NOT NULL REFERENCES merchant(merchant_id) ON DELETE RESTRICT,
    current_version INTEGER NOT NULL DEFAULT 1 CHECK (current_version > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','PAUSED','REVOKED')),
    pause_reason VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (autobuy_plan_id, buyer_actor_id)
);

CREATE TABLE autobuy_plan_version (
    autobuy_plan_id UUID NOT NULL REFERENCES autobuy_plan(autobuy_plan_id) ON DELETE RESTRICT,
    version INTEGER NOT NULL,
    buyer_actor_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    merchant_account_link_id UUID NOT NULL REFERENCES merchant_account_link(merchant_account_link_id) ON DELETE RESTRICT,
    address_id UUID NOT NULL REFERENCES buyer_address(address_id) ON DELETE RESTRICT,
    product_constraints JSONB NOT NULL CHECK (jsonb_typeof(product_constraints)='object' AND length(product_constraints::text)<=8192),
    maximum_amount_minor BIGINT NOT NULL CHECK (maximum_amount_minor > 0),
    trigger_description VARCHAR(256) NOT NULL,
    substitution_policy VARCHAR(32) NOT NULL CHECK (substitution_policy IN ('NONE','EXACT_ONLY')),
    hard_safety_constraints JSONB NOT NULL CHECK (jsonb_typeof(hard_safety_constraints)='object' AND length(hard_safety_constraints::text)<=8192),
    plan_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (autobuy_plan_id, version)
);

CREATE TABLE autobuy_evaluation (
    autobuy_evaluation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    autobuy_plan_id UUID NOT NULL REFERENCES autobuy_plan(autobuy_plan_id) ON DELETE RESTRICT,
    plan_version INTEGER NOT NULL,
    buyer_actor_id UUID NOT NULL,
    trigger_id VARCHAR(128) NOT NULL,
    outcome VARCHAR(32) NOT NULL CHECK (outcome IN ('AUTO_EXECUTE','CONFIRM','PAUSED','BLOCKED')),
    reason_code VARCHAR(128) NOT NULL,
    fresh_evidence JSONB NOT NULL CHECK (jsonb_typeof(fresh_evidence)='object' AND length(fresh_evidence::text)<=16384),
    proposal_id UUID REFERENCES transaction_proposal(proposal_id) ON DELETE RESTRICT,
    execution_id UUID REFERENCES transaction_execution(execution_id) ON DELETE RESTRICT,
    provider_order_id VARCHAR(128),
    checkout_authorization_required BOOLEAN NOT NULL DEFAULT TRUE,
    evaluation_hash CHAR(64) NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (autobuy_plan_id, trigger_id),
    FOREIGN KEY (autobuy_plan_id, plan_version)
        REFERENCES autobuy_plan_version(autobuy_plan_id, version) ON DELETE RESTRICT
);

CREATE TABLE agentization_goal (
    agentization_goal_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL REFERENCES merchant(merchant_id) ON DELETE RESTRICT,
    created_by_actor_id UUID NOT NULL REFERENCES application_actor(actor_id) ON DELETE RESTRICT,
    source_artifact_id UUID NOT NULL REFERENCES openapi_artifact(artifact_id) ON DELETE RESTRICT,
    goal_type VARCHAR(64) NOT NULL CHECK (goal_type='AGENTIZE_STORE_FOR_PURCHASE_AND_LIFECYCLE'),
    status VARCHAR(32) NOT NULL CHECK (status IN ('ACTIVE','WAITING_FOR_MERCHANT','BLOCKED','COMPLETED','BUDGET_EXHAUSTED')),
    max_step_budget INTEGER NOT NULL CHECK (max_step_budget BETWEEN 1 AND 500),
    consumed_steps INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE agentization_goal_target (
    agentization_goal_id UUID NOT NULL REFERENCES agentization_goal(agentization_goal_id) ON DELETE RESTRICT,
    capability VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING','IN_PROGRESS','READY','BLOCKED','UNTESTED','WAITING_FOR_MERCHANT')),
    agentization_run_id UUID REFERENCES agentization_run(run_id) ON DELETE RESTRICT,
    readiness_evaluation_id UUID REFERENCES capability_readiness_evaluation(readiness_evaluation_id) ON DELETE RESTRICT,
    PRIMARY KEY (agentization_goal_id, capability)
);

CREATE TABLE lifecycle_audit_event (
    lifecycle_audit_event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    buyer_actor_id UUID NOT NULL,
    merchant_id UUID,
    thread_id UUID,
    event_type VARCHAR(64) NOT NULL,
    subject_reference VARCHAR(256) NOT NULL,
    evidence JSONB NOT NULL CHECK (jsonb_typeof(evidence)='object' AND length(evidence::text)<=8192),
    evidence_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TRIGGER trg_fulfilment_snapshot_immutable BEFORE UPDATE OR DELETE ON fulfilment_snapshot
    FOR EACH ROW EXECUTE FUNCTION reject_payment_evidence_mutation();
CREATE TRIGGER trg_purchase_fulfilment_authority_immutable BEFORE UPDATE OR DELETE ON purchase_fulfilment_authority
    FOR EACH ROW EXECUTE FUNCTION reject_payment_evidence_mutation();
CREATE TRIGGER trg_proposal_fulfilment_immutable BEFORE UPDATE OR DELETE ON transaction_proposal_fulfilment
    FOR EACH ROW EXECUTE FUNCTION reject_payment_evidence_mutation();
CREATE TRIGGER trg_lifecycle_intent_immutable BEFORE UPDATE OR DELETE ON lifecycle_intent
    FOR EACH ROW EXECUTE FUNCTION reject_payment_evidence_mutation();
CREATE TRIGGER trg_lifecycle_policy_immutable BEFORE UPDATE OR DELETE ON lifecycle_policy_evaluation
    FOR EACH ROW EXECUTE FUNCTION reject_payment_evidence_mutation();
CREATE TRIGGER trg_lifecycle_proposal_immutable BEFORE UPDATE OR DELETE ON lifecycle_proposal
    FOR EACH ROW EXECUTE FUNCTION reject_payment_evidence_mutation();
CREATE TRIGGER trg_lifecycle_authorization_immutable BEFORE UPDATE OR DELETE ON lifecycle_authorization_decision
    FOR EACH ROW EXECUTE FUNCTION reject_payment_evidence_mutation();
CREATE TRIGGER trg_refund_evidence_immutable BEFORE UPDATE OR DELETE ON refund_provider_evidence
    FOR EACH ROW EXECUTE FUNCTION reject_payment_evidence_mutation();
CREATE TRIGGER trg_autobuy_plan_version_immutable BEFORE UPDATE OR DELETE ON autobuy_plan_version
    FOR EACH ROW EXECUTE FUNCTION reject_payment_evidence_mutation();
CREATE TRIGGER trg_autobuy_evaluation_immutable BEFORE UPDATE OR DELETE ON autobuy_evaluation
    FOR EACH ROW EXECUTE FUNCTION reject_payment_evidence_mutation();
