CREATE TABLE merchant_payment_configuration (
    payment_configuration_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    environment VARCHAR(16) NOT NULL,
    configuration_reference VARCHAR(128) NOT NULL,
    provider_account_reference VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payment_configuration_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchant (merchant_id) ON DELETE RESTRICT,
    CONSTRAINT uq_payment_configuration_merchant_environment
        UNIQUE (merchant_id, provider, environment),
    CONSTRAINT uq_payment_configuration_reference UNIQUE (configuration_reference),
    CONSTRAINT chk_payment_configuration_provider CHECK (provider = 'RAZORPAY'),
    CONSTRAINT chk_payment_configuration_environment CHECK (environment = 'TEST'),
    CONSTRAINT chk_payment_configuration_refs CHECK (
        length(configuration_reference) BETWEEN 1 AND 128
        AND length(provider_account_reference) BETWEEN 1 AND 128)
);

CREATE TABLE payment_control (
    payment_control_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    execution_id UUID NOT NULL,
    proposal_id UUID NOT NULL,
    buyer_actor_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    payment_configuration_id UUID NOT NULL,
    state VARCHAR(32) NOT NULL,
    expected_amount_minor BIGINT NOT NULL,
    expected_currency CHAR(3) NOT NULL,
    expected_provider_order_id VARCHAR(128),
    confirmed_payment_id VARCHAR(128),
    version INTEGER NOT NULL DEFAULT 0,
    reason_code VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    CONSTRAINT uq_payment_control_execution UNIQUE (execution_id),
    CONSTRAINT uq_payment_control_owner UNIQUE (
        payment_control_id, execution_id, proposal_id, buyer_actor_id, merchant_id),
    CONSTRAINT fk_payment_control_execution FOREIGN KEY (
        execution_id, proposal_id, buyer_actor_id, merchant_id)
        REFERENCES transaction_execution (
            execution_id, proposal_id, buyer_actor_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_control_configuration FOREIGN KEY (payment_configuration_id)
        REFERENCES merchant_payment_configuration (payment_configuration_id) ON DELETE RESTRICT,
    CONSTRAINT chk_payment_control_state CHECK (state IN (
        'NOT_STARTED','ORDER_CREATED','PAYMENT_PENDING','PAYMENT_UNCERTAIN',
        'PAYMENT_CONFIRMED','PAYMENT_FAILED')),
    CONSTRAINT chk_payment_control_amount CHECK (expected_amount_minor > 0),
    CONSTRAINT chk_payment_control_currency CHECK (expected_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_payment_control_confirmation CHECK (
        (state = 'PAYMENT_CONFIRMED' AND confirmed_payment_id IS NOT NULL AND confirmed_at IS NOT NULL)
        OR (state <> 'PAYMENT_CONFIRMED' AND confirmed_at IS NULL))
);

CREATE TABLE provider_order_creation_attempt (
    order_attempt_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_control_id UUID NOT NULL,
    execution_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    stable_receipt VARCHAR(40) NOT NULL,
    attempt_number INTEGER NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    error_category VARCHAR(64),
    provider_order_id VARCHAR(128),
    request_hash CHAR(64) NOT NULL,
    response_hash CHAR(64),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_order_attempt_number UNIQUE (execution_id, attempt_number),
    CONSTRAINT uq_order_attempt_receipt UNIQUE (execution_id, stable_receipt),
    CONSTRAINT fk_order_attempt_control FOREIGN KEY (payment_control_id)
        REFERENCES payment_control (payment_control_id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_attempt_execution FOREIGN KEY (execution_id)
        REFERENCES transaction_execution (execution_id) ON DELETE RESTRICT,
    CONSTRAINT chk_order_attempt_outcome CHECK (outcome IN (
        'IN_PROGRESS','CREATED','UNCERTAIN','DEFINITIVE_FAILURE')),
    CONSTRAINT chk_order_attempt_number CHECK (attempt_number > 0),
    CONSTRAINT chk_order_attempt_hashes CHECK (
        request_hash ~ '^[0-9a-f]{64}$'
        AND (response_hash IS NULL OR response_hash ~ '^[0-9a-f]{64}$'))
);

CREATE TABLE payment_provider_order (
    provider_order_record_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_control_id UUID NOT NULL,
    execution_id UUID NOT NULL,
    proposal_id UUID NOT NULL,
    proposal_hash CHAR(64) NOT NULL,
    merchant_id UUID NOT NULL,
    payment_configuration_id UUID NOT NULL,
    provider_order_id VARCHAR(128) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    receipt VARCHAR(40) NOT NULL,
    provider_status VARCHAR(32) NOT NULL,
    provider_created_at TIMESTAMPTZ,
    idempotency_reference VARCHAR(128) NOT NULL,
    response_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_provider_order_execution UNIQUE (execution_id),
    CONSTRAINT uq_provider_order_identity UNIQUE (payment_configuration_id, provider_order_id),
    CONSTRAINT uq_provider_order_receipt UNIQUE (payment_configuration_id, receipt),
    CONSTRAINT fk_provider_order_control FOREIGN KEY (payment_control_id)
        REFERENCES payment_control (payment_control_id) ON DELETE RESTRICT,
    CONSTRAINT fk_provider_order_execution FOREIGN KEY (execution_id)
        REFERENCES transaction_execution (execution_id) ON DELETE RESTRICT,
    CONSTRAINT fk_provider_order_proposal FOREIGN KEY (proposal_id)
        REFERENCES transaction_proposal (proposal_id) ON DELETE RESTRICT,
    CONSTRAINT fk_provider_order_configuration FOREIGN KEY (payment_configuration_id)
        REFERENCES merchant_payment_configuration (payment_configuration_id) ON DELETE RESTRICT,
    CONSTRAINT chk_provider_order_amount CHECK (amount_minor > 0),
    CONSTRAINT chk_provider_order_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_provider_order_hash CHECK (response_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE payment_callback_evidence (
    callback_evidence_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_control_id UUID NOT NULL,
    execution_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    provider_order_id VARCHAR(128) NOT NULL,
    provider_payment_id VARCHAR(128) NOT NULL,
    signature_hash CHAR(64) NOT NULL,
    valid BOOLEAN NOT NULL,
    evidence_hash CHAR(64) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_callback_evidence UNIQUE (
        payment_control_id, provider_order_id, provider_payment_id, signature_hash),
    CONSTRAINT fk_callback_control FOREIGN KEY (payment_control_id)
        REFERENCES payment_control (payment_control_id) ON DELETE RESTRICT,
    CONSTRAINT chk_callback_hashes CHECK (
        signature_hash ~ '^[0-9a-f]{64}$' AND evidence_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE provider_webhook_event (
    webhook_event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_configuration_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    provider_event_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    signature_hash CHAR(64) NOT NULL,
    raw_body_hash CHAR(64) NOT NULL,
    raw_body BYTEA NOT NULL,
    processing_status VARCHAR(32) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    last_error_code VARCHAR(128),
    CONSTRAINT uq_webhook_provider_event UNIQUE (payment_configuration_id, provider_event_id),
    CONSTRAINT fk_webhook_configuration FOREIGN KEY (payment_configuration_id)
        REFERENCES merchant_payment_configuration (payment_configuration_id) ON DELETE RESTRICT,
    CONSTRAINT chk_webhook_status CHECK (processing_status IN ('RECEIVED','PROCESSED','REJECTED')),
    CONSTRAINT chk_webhook_body CHECK (octet_length(raw_body) BETWEEN 2 AND 262144),
    CONSTRAINT chk_webhook_hashes CHECK (
        signature_hash ~ '^[0-9a-f]{64}$' AND raw_body_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE provider_payment_evidence (
    payment_evidence_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_control_id UUID NOT NULL,
    execution_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    payment_configuration_id UUID NOT NULL,
    provider_payment_id VARCHAR(128) NOT NULL,
    provider_order_id VARCHAR(128) NOT NULL,
    provider_status VARCHAR(32) NOT NULL,
    amount_minor BIGINT,
    currency CHAR(3),
    captured BOOLEAN NOT NULL,
    provider_account_reference VARCHAR(128),
    source VARCHAR(32) NOT NULL,
    source_reference VARCHAR(128) NOT NULL,
    evidence_hash CHAR(64) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_payment_evidence UNIQUE (
        payment_control_id, source, source_reference, evidence_hash),
    CONSTRAINT fk_payment_evidence_control FOREIGN KEY (payment_control_id)
        REFERENCES payment_control (payment_control_id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_evidence_configuration FOREIGN KEY (payment_configuration_id)
        REFERENCES merchant_payment_configuration (payment_configuration_id) ON DELETE RESTRICT,
    CONSTRAINT chk_payment_evidence_source CHECK (source IN ('CALLBACK','WEBHOOK','API_RECONCILIATION')),
    CONSTRAINT chk_payment_evidence_amount CHECK (amount_minor IS NULL OR amount_minor >= 0),
    CONSTRAINT chk_payment_evidence_hash CHECK (evidence_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_payment_evidence_control_observed
    ON provider_payment_evidence (payment_control_id, observed_at DESC);

CREATE TABLE provider_order_evidence (
    order_evidence_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_control_id UUID NOT NULL,
    execution_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    payment_configuration_id UUID NOT NULL,
    provider_order_id VARCHAR(128) NOT NULL,
    provider_status VARCHAR(32) NOT NULL,
    amount_minor BIGINT,
    amount_paid_minor BIGINT,
    currency CHAR(3),
    provider_account_reference VARCHAR(128),
    source VARCHAR(32) NOT NULL,
    source_reference VARCHAR(128) NOT NULL,
    evidence_hash CHAR(64) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_order_evidence UNIQUE (
        payment_control_id, source, source_reference, evidence_hash),
    CONSTRAINT fk_order_evidence_control FOREIGN KEY (payment_control_id)
        REFERENCES payment_control (payment_control_id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_evidence_configuration FOREIGN KEY (payment_configuration_id)
        REFERENCES merchant_payment_configuration (payment_configuration_id) ON DELETE RESTRICT,
    CONSTRAINT chk_order_evidence_source CHECK (source IN ('WEBHOOK','API_RECONCILIATION')),
    CONSTRAINT chk_order_evidence_amounts CHECK (
        (amount_minor IS NULL OR amount_minor >= 0)
        AND (amount_paid_minor IS NULL OR amount_paid_minor >= 0)),
    CONSTRAINT chk_order_evidence_hash CHECK (evidence_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_order_evidence_control_observed
    ON provider_order_evidence (payment_control_id, observed_at DESC);

CREATE TABLE payment_reduction_evidence (
    reduction_evidence_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_control_id UUID NOT NULL,
    execution_id UUID NOT NULL,
    previous_state VARCHAR(32) NOT NULL,
    reduced_state VARCHAR(32) NOT NULL,
    reason_code VARCHAR(128) NOT NULL,
    payment_evidence_id UUID,
    order_evidence_id UUID,
    input_hash CHAR(64) NOT NULL,
    reduced_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_reduction_control FOREIGN KEY (payment_control_id)
        REFERENCES payment_control (payment_control_id) ON DELETE RESTRICT,
    CONSTRAINT fk_reduction_payment_evidence FOREIGN KEY (payment_evidence_id)
        REFERENCES provider_payment_evidence (payment_evidence_id) ON DELETE RESTRICT,
    CONSTRAINT fk_reduction_order_evidence FOREIGN KEY (order_evidence_id)
        REFERENCES provider_order_evidence (order_evidence_id) ON DELETE RESTRICT,
    CONSTRAINT chk_reduction_states CHECK (
        previous_state IN ('NOT_STARTED','ORDER_CREATED','PAYMENT_PENDING','PAYMENT_UNCERTAIN','PAYMENT_CONFIRMED','PAYMENT_FAILED')
        AND reduced_state IN ('NOT_STARTED','ORDER_CREATED','PAYMENT_PENDING','PAYMENT_UNCERTAIN','PAYMENT_CONFIRMED','PAYMENT_FAILED')),
    CONSTRAINT chk_reduction_hash CHECK (input_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE payment_reconciliation (
    reconciliation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_control_id UUID NOT NULL UNIQUE,
    execution_id UUID NOT NULL UNIQUE,
    provider_payment_id VARCHAR(128),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    maximum_attempts INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    next_attempt_at TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,
    last_error_code VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_reconciliation_control FOREIGN KEY (payment_control_id)
        REFERENCES payment_control (payment_control_id) ON DELETE RESTRICT,
    CONSTRAINT chk_reconciliation_status CHECK (status IN ('PENDING','COMPLETED','MANUAL_REVIEW')),
    CONSTRAINT chk_reconciliation_budget CHECK (
        maximum_attempts BETWEEN 1 AND 20 AND attempt_count BETWEEN 0 AND maximum_attempts)
);

CREATE TABLE merchant_finalization (
    merchant_finalization_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    execution_id UUID NOT NULL UNIQUE,
    proposal_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    mapping_proposal_id UUID,
    merchant_operation_id VARCHAR(128) NOT NULL UNIQUE,
    state VARCHAR(32) NOT NULL,
    provider_order_id VARCHAR(128) NOT NULL,
    provider_payment_id VARCHAR(128) NOT NULL,
    merchant_order_id VARCHAR(256),
    request_hash CHAR(64) NOT NULL,
    response_hash CHAR(64),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error_code VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    fulfilled_at TIMESTAMPTZ,
    CONSTRAINT fk_finalization_execution FOREIGN KEY (execution_id)
        REFERENCES transaction_execution (execution_id) ON DELETE RESTRICT,
    CONSTRAINT fk_finalization_proposal FOREIGN KEY (proposal_id)
        REFERENCES transaction_proposal (proposal_id) ON DELETE RESTRICT,
    CONSTRAINT fk_finalization_mapping FOREIGN KEY (mapping_proposal_id)
        REFERENCES capability_mapping_proposal (mapping_proposal_id) ON DELETE RESTRICT,
    CONSTRAINT chk_finalization_state CHECK (state IN (
        'PENDING','IN_PROGRESS','FULFILLED','RETRYABLE_FAILURE',
        'TERMINAL_FAILURE','COMPENSATION_REQUIRED')),
    CONSTRAINT chk_finalization_hashes CHECK (
        request_hash ~ '^[0-9a-f]{64}$'
        AND (response_hash IS NULL OR response_hash ~ '^[0-9a-f]{64}$'))
);

CREATE TABLE merchant_finalization_attempt (
    finalization_attempt_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_finalization_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    error_code VARCHAR(128),
    response_hash CHAR(64),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_finalization_attempt UNIQUE (merchant_finalization_id, attempt_number),
    CONSTRAINT fk_finalization_attempt FOREIGN KEY (merchant_finalization_id)
        REFERENCES merchant_finalization (merchant_finalization_id) ON DELETE RESTRICT,
    CONSTRAINT chk_finalization_attempt_outcome CHECK (outcome IN (
        'IN_PROGRESS','FULFILLED','RETRYABLE_FAILURE','TERMINAL_FAILURE'))
);

CREATE TABLE transactional_outbox (
    outbox_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    execution_id UUID NOT NULL,
    work_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    last_error_code VARCHAR(128),
    CONSTRAINT uq_outbox_execution_work UNIQUE (execution_id, work_type),
    CONSTRAINT fk_outbox_execution FOREIGN KEY (execution_id)
        REFERENCES transaction_execution (execution_id) ON DELETE RESTRICT,
    CONSTRAINT chk_outbox_work_type CHECK (work_type = 'FINALIZE_MERCHANT_ORDER'),
    CONSTRAINT chk_outbox_status CHECK (status IN (
        'PENDING','PROCESSING','COMPLETED','FAILED_RETRYABLE','FAILED_TERMINAL')),
    CONSTRAINT chk_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_outbox_payload CHECK (
        jsonb_typeof(payload) = 'object' AND length(payload::text) <= 16384)
);

CREATE INDEX idx_outbox_claim
    ON transactional_outbox (next_attempt_at, created_at)
    WHERE status IN ('PENDING','FAILED_RETRYABLE','PROCESSING');

CREATE FUNCTION reject_payment_evidence_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'payment evidence rows are immutable';
END $$;

CREATE TRIGGER trg_provider_order_immutable BEFORE UPDATE OR DELETE ON payment_provider_order
    FOR EACH ROW EXECUTE FUNCTION reject_payment_evidence_mutation();
CREATE TRIGGER trg_callback_evidence_immutable BEFORE UPDATE OR DELETE ON payment_callback_evidence
    FOR EACH ROW EXECUTE FUNCTION reject_payment_evidence_mutation();
CREATE TRIGGER trg_payment_evidence_immutable BEFORE UPDATE OR DELETE ON provider_payment_evidence
    FOR EACH ROW EXECUTE FUNCTION reject_payment_evidence_mutation();
CREATE TRIGGER trg_order_evidence_immutable BEFORE UPDATE OR DELETE ON provider_order_evidence
    FOR EACH ROW EXECUTE FUNCTION reject_payment_evidence_mutation();
CREATE TRIGGER trg_reduction_evidence_immutable BEFORE UPDATE OR DELETE ON payment_reduction_evidence
    FOR EACH ROW EXECUTE FUNCTION reject_payment_evidence_mutation();

