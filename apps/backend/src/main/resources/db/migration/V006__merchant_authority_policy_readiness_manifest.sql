ALTER TABLE agentization_run
    DROP CONSTRAINT chk_agentization_run_state,
    ADD CONSTRAINT chk_agentization_run_state CHECK (orchestration_state IN (
        'AGENTIZATION_CREATED', 'INPUTS_VALIDATING', 'INSPECTING_API', 'MAPPING_CAPABILITY',
        'EXTRACTING_POLICY', 'WAITING_FOR_MERCHANT_APPROVAL', 'TESTING_CAPABILITY',
        'DIAGNOSING_FAILURE', 'REVISING_MAPPING', 'WAITING_FOR_MERCHANT_CLARIFICATION',
        'READY_CANDIDATE', 'COMPLETE', 'BLOCKED', 'BUDGET_EXHAUSTED', 'FAILED'));

ALTER TABLE agent_observation
    DROP CONSTRAINT chk_agent_observation_state,
    DROP CONSTRAINT chk_agent_observation_tool,
    ADD CONSTRAINT chk_agent_observation_state CHECK (orchestration_state IN (
        'AGENTIZATION_CREATED', 'INPUTS_VALIDATING', 'INSPECTING_API', 'MAPPING_CAPABILITY',
        'EXTRACTING_POLICY', 'WAITING_FOR_MERCHANT_APPROVAL', 'TESTING_CAPABILITY',
        'DIAGNOSING_FAILURE', 'REVISING_MAPPING', 'WAITING_FOR_MERCHANT_CLARIFICATION',
        'READY_CANDIDATE', 'COMPLETE', 'BLOCKED', 'BUDGET_EXHAUSTED', 'FAILED')),
    ADD CONSTRAINT chk_agent_observation_tool CHECK (tool_name IN (
        'INSPECT_SPEC', 'INSPECT_SCHEMA', 'PROPOSE_MAPPING', 'VALIDATE_MAPPING',
        'RUN_CONTRACT_TEST', 'INSPECT_TEST_FAILURE', 'REVISE_MAPPING',
        'INSPECT_POLICY', 'EXTRACT_POLICY_RULES', 'REQUEST_MERCHANT_CLARIFICATION',
        'REQUEST_MERCHANT_APPROVAL', 'PUBLISH_MANIFEST_CANDIDATE'));

ALTER TABLE capability_mapping_proposal
    ADD COLUMN status_normalization JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN idempotency_semantics JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN retry_semantics JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD CONSTRAINT chk_mapping_authority_semantics CHECK (
        jsonb_typeof(status_normalization) = 'object'
        AND jsonb_typeof(idempotency_semantics) = 'object'
        AND jsonb_typeof(retry_semantics) = 'object'
        AND length(status_normalization::text) <= 8000
        AND length(idempotency_semantics::text) <= 8000
        AND length(retry_semantics::text) <= 8000);

CREATE TABLE merchant_clarification (
    clarification_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    agentization_run_id UUID NOT NULL,
    capability VARCHAR(64) NOT NULL,
    mapping_proposal_id UUID,
    policy_document_id UUID,
    policy_rule_id UUID,
    question VARCHAR(512) NOT NULL,
    evidence_references JSONB NOT NULL,
    clarification_kind VARCHAR(32) NOT NULL,
    continuation_state VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    merchant_response VARCHAR(2000),
    responding_actor_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    answered_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    CONSTRAINT uq_merchant_clarification_tenant UNIQUE (clarification_id, merchant_id),
    CONSTRAINT fk_clarification_run FOREIGN KEY (agentization_run_id, merchant_id)
        REFERENCES agentization_run (run_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_clarification_mapping FOREIGN KEY (mapping_proposal_id, merchant_id)
        REFERENCES capability_mapping_proposal (mapping_proposal_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_clarification_actor FOREIGN KEY (responding_actor_id)
        REFERENCES application_actor (actor_id) ON DELETE RESTRICT,
    CONSTRAINT chk_clarification_capability CHECK (capability IN (
        'SEARCH_PRODUCTS', 'GET_AVAILABILITY', 'GET_QUOTE', 'PLACE_ORDER',
        'GET_ORDER_STATE', 'CANCEL_ORDER', 'RETURN_ITEM', 'REFUND')),
    CONSTRAINT chk_clarification_kind CHECK (clarification_kind IN (
        'MAPPING', 'POLICY', 'LIFECYCLE', 'IDEMPOTENCY', 'MONEY_SEMANTICS')),
    CONSTRAINT chk_clarification_continuation CHECK (continuation_state IN (
        'DIAGNOSING_FAILURE', 'REVISING_MAPPING', 'EXTRACTING_POLICY',
        'WAITING_FOR_MERCHANT_APPROVAL', 'READY_CANDIDATE')),
    CONSTRAINT chk_clarification_status CHECK (status IN ('OPEN', 'ANSWERED', 'CANCELLED')),
    CONSTRAINT chk_clarification_question CHECK (length(trim(question)) BETWEEN 1 AND 512),
    CONSTRAINT chk_clarification_evidence CHECK (
        jsonb_typeof(evidence_references) = 'array' AND jsonb_array_length(evidence_references) BETWEEN 1 AND 32),
    CONSTRAINT chk_clarification_answer CHECK (
        (status = 'OPEN' AND merchant_response IS NULL AND responding_actor_id IS NULL
            AND answered_at IS NULL AND cancelled_at IS NULL)
        OR (status = 'ANSWERED' AND merchant_response IS NOT NULL AND responding_actor_id IS NOT NULL
            AND answered_at IS NOT NULL AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND merchant_response IS NULL AND responding_actor_id IS NULL
            AND answered_at IS NULL AND cancelled_at IS NOT NULL))
);

CREATE INDEX idx_clarification_merchant_run_status
    ON merchant_clarification (merchant_id, agentization_run_id, status, created_at DESC);

CREATE TABLE mapping_approval_decision (
    mapping_approval_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    agentization_run_id UUID NOT NULL,
    mapping_proposal_id UUID NOT NULL,
    mapping_version INTEGER NOT NULL,
    mapping_content_hash CHAR(64) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    approving_actor_id UUID NOT NULL,
    merchant_note VARCHAR(512),
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_mapping_approval_tenant UNIQUE (mapping_approval_id, merchant_id),
    CONSTRAINT uq_mapping_approval_decision UNIQUE (mapping_proposal_id),
    CONSTRAINT fk_mapping_approval_run FOREIGN KEY (agentization_run_id, merchant_id)
        REFERENCES agentization_run (run_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_mapping_approval_mapping FOREIGN KEY (mapping_proposal_id, merchant_id)
        REFERENCES capability_mapping_proposal (mapping_proposal_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_mapping_approval_actor FOREIGN KEY (approving_actor_id)
        REFERENCES application_actor (actor_id) ON DELETE RESTRICT,
    CONSTRAINT chk_mapping_approval_decision CHECK (decision IN ('APPROVE', 'REJECT')),
    CONSTRAINT chk_mapping_approval_version CHECK (mapping_version > 0),
    CONSTRAINT chk_mapping_approval_hash CHECK (mapping_content_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_mapping_approval_merchant_run
    ON mapping_approval_decision (merchant_id, agentization_run_id, decided_at DESC);

CREATE TABLE policy_document (
    policy_document_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    document_type VARCHAR(32) NOT NULL,
    document_version INTEGER NOT NULL,
    title VARCHAR(256) NOT NULL,
    normalized_content TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    uploaded_by_actor_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_policy_document_tenant UNIQUE (policy_document_id, merchant_id),
    CONSTRAINT uq_policy_document_version UNIQUE (merchant_id, document_type, document_version),
    CONSTRAINT uq_policy_document_content UNIQUE (merchant_id, document_type, content_hash),
    CONSTRAINT fk_policy_document_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchant (merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_policy_document_actor FOREIGN KEY (uploaded_by_actor_id)
        REFERENCES application_actor (actor_id) ON DELETE RESTRICT,
    CONSTRAINT chk_policy_document_type CHECK (document_type IN (
        'CANCELLATION', 'RETURN', 'REFUND', 'SHIPPING', 'REPLACEMENT', 'GENERAL_COMMERCE')),
    CONSTRAINT chk_policy_document_version CHECK (document_version > 0),
    CONSTRAINT chk_policy_document_title CHECK (length(trim(title)) BETWEEN 1 AND 256),
    CONSTRAINT chk_policy_document_content CHECK (length(normalized_content) BETWEEN 1 AND 100000),
    CONSTRAINT chk_policy_document_hash CHECK (content_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_policy_document_merchant_type
    ON policy_document (merchant_id, document_type, document_version DESC);

CREATE TABLE proposed_policy_rule (
    policy_rule_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    policy_document_id UUID NOT NULL,
    document_version INTEGER NOT NULL,
    rule_version INTEGER NOT NULL,
    rule_type VARCHAR(64) NOT NULL,
    source_clause VARCHAR(1000) NOT NULL,
    applicability_conditions JSONB NOT NULL,
    outcome_effect JSONB NOT NULL,
    model_confidence NUMERIC(5,4),
    precedence_priority INTEGER,
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    extraction_provider VARCHAR(128) NOT NULL,
    extraction_model VARCHAR(256) NOT NULL,
    rule_content_hash CHAR(64) NOT NULL,
    approval_state VARCHAR(16) NOT NULL DEFAULT 'PROPOSED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_policy_rule_tenant UNIQUE (policy_rule_id, merchant_id),
    CONSTRAINT uq_policy_rule_version UNIQUE (policy_document_id, rule_type, rule_version),
    CONSTRAINT fk_policy_rule_document FOREIGN KEY (policy_document_id, merchant_id)
        REFERENCES policy_document (policy_document_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT chk_policy_rule_type CHECK (rule_type IN (
        'CANCELLATION_WINDOW', 'RETURN_WINDOW', 'REFUND_ELIGIBILITY',
        'NON_RETURNABLE', 'SHIPPING_RULE', 'REPLACEMENT_ELIGIBILITY')),
    CONSTRAINT chk_policy_rule_versions CHECK (document_version > 0 AND rule_version > 0),
    CONSTRAINT chk_policy_rule_conditions CHECK (
        jsonb_typeof(applicability_conditions) = 'object' AND length(applicability_conditions::text) <= 8000),
    CONSTRAINT chk_policy_rule_outcome CHECK (
        jsonb_typeof(outcome_effect) = 'object' AND length(outcome_effect::text) <= 8000),
    CONSTRAINT chk_policy_rule_confidence CHECK (
        model_confidence IS NULL OR (model_confidence >= 0 AND model_confidence <= 1)),
    CONSTRAINT chk_policy_rule_precedence CHECK (
        precedence_priority IS NULL OR precedence_priority BETWEEN 0 AND 10000),
    CONSTRAINT chk_policy_rule_effective_dates CHECK (
        effective_to IS NULL OR effective_from IS NULL OR effective_to > effective_from),
    CONSTRAINT chk_policy_rule_approval_state CHECK (approval_state IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_policy_rule_hash CHECK (rule_content_hash ~ '^[0-9a-f]{64}$')
);

ALTER TABLE merchant_clarification
    ADD CONSTRAINT fk_clarification_policy_document FOREIGN KEY (policy_document_id, merchant_id)
        REFERENCES policy_document (policy_document_id, merchant_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_clarification_policy_rule FOREIGN KEY (policy_rule_id, merchant_id)
        REFERENCES proposed_policy_rule (policy_rule_id, merchant_id) ON DELETE RESTRICT;

CREATE INDEX idx_policy_rule_merchant_state
    ON proposed_policy_rule (merchant_id, approval_state, rule_type, created_at DESC);

CREATE TABLE policy_rule_approval_decision (
    policy_rule_approval_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    policy_rule_id UUID NOT NULL,
    rule_version INTEGER NOT NULL,
    rule_content_hash CHAR(64) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    approving_actor_id UUID NOT NULL,
    merchant_note VARCHAR(512),
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_policy_rule_approval_tenant UNIQUE (policy_rule_approval_id, merchant_id),
    CONSTRAINT uq_policy_rule_approval_decision UNIQUE (policy_rule_id),
    CONSTRAINT fk_rule_approval_rule FOREIGN KEY (policy_rule_id, merchant_id)
        REFERENCES proposed_policy_rule (policy_rule_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_rule_approval_actor FOREIGN KEY (approving_actor_id)
        REFERENCES application_actor (actor_id) ON DELETE RESTRICT,
    CONSTRAINT chk_rule_approval_decision CHECK (decision IN ('APPROVE', 'REJECT')),
    CONSTRAINT chk_rule_approval_version CHECK (rule_version > 0),
    CONSTRAINT chk_rule_approval_hash CHECK (rule_content_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE merchant_policy_snapshot (
    policy_snapshot_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    snapshot_version INTEGER NOT NULL,
    snapshot_hash CHAR(64) NOT NULL,
    published_by_actor_id UUID NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_policy_snapshot_tenant UNIQUE (policy_snapshot_id, merchant_id),
    CONSTRAINT uq_policy_snapshot_version UNIQUE (merchant_id, snapshot_version),
    CONSTRAINT fk_policy_snapshot_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchant (merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_policy_snapshot_actor FOREIGN KEY (published_by_actor_id)
        REFERENCES application_actor (actor_id) ON DELETE RESTRICT,
    CONSTRAINT chk_policy_snapshot_version CHECK (snapshot_version > 0),
    CONSTRAINT chk_policy_snapshot_hash CHECK (snapshot_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE merchant_policy_snapshot_rule (
    policy_snapshot_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    policy_rule_id UUID NOT NULL,
    rule_version INTEGER NOT NULL,
    rule_content_hash CHAR(64) NOT NULL,
    PRIMARY KEY (policy_snapshot_id, policy_rule_id),
    CONSTRAINT fk_snapshot_rule_snapshot FOREIGN KEY (policy_snapshot_id, merchant_id)
        REFERENCES merchant_policy_snapshot (policy_snapshot_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_snapshot_rule_rule FOREIGN KEY (policy_rule_id, merchant_id)
        REFERENCES proposed_policy_rule (policy_rule_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT chk_snapshot_rule_version CHECK (rule_version > 0),
    CONSTRAINT chk_snapshot_rule_hash CHECK (rule_content_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE capability_readiness_evaluation (
    readiness_evaluation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    agentization_run_id UUID NOT NULL,
    capability VARCHAR(64) NOT NULL,
    readiness VARCHAR(16) NOT NULL,
    mapping_proposal_id UUID,
    mapping_version INTEGER,
    mapping_content_hash CHAR(64),
    contract_test_run_id UUID,
    policy_snapshot_id UUID,
    required_evidence JSONB NOT NULL,
    satisfied_evidence JSONB NOT NULL,
    missing_requirements JSONB NOT NULL,
    blocking_evidence JSONB NOT NULL,
    evidence_references JSONB NOT NULL,
    evaluation_hash CHAR(64) NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_readiness_evaluation_tenant UNIQUE (readiness_evaluation_id, merchant_id),
    CONSTRAINT fk_readiness_run FOREIGN KEY (agentization_run_id, merchant_id)
        REFERENCES agentization_run (run_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_readiness_mapping FOREIGN KEY (mapping_proposal_id, merchant_id)
        REFERENCES capability_mapping_proposal (mapping_proposal_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_readiness_test FOREIGN KEY (contract_test_run_id, merchant_id)
        REFERENCES capability_contract_test_run (contract_test_run_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_readiness_policy FOREIGN KEY (policy_snapshot_id, merchant_id)
        REFERENCES merchant_policy_snapshot (policy_snapshot_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT chk_readiness_capability CHECK (capability IN (
        'SEARCH_PRODUCTS', 'GET_AVAILABILITY', 'GET_QUOTE', 'PLACE_ORDER',
        'GET_ORDER_STATE', 'CANCEL_ORDER', 'RETURN_ITEM', 'REFUND', 'PURCHASE')),
    CONSTRAINT chk_readiness_value CHECK (readiness IN ('READY', 'BLOCKED', 'UNTESTED')),
    CONSTRAINT chk_readiness_mapping_version CHECK (mapping_version IS NULL OR mapping_version > 0),
    CONSTRAINT chk_readiness_json CHECK (
        jsonb_typeof(required_evidence) = 'array' AND jsonb_typeof(satisfied_evidence) = 'array'
        AND jsonb_typeof(missing_requirements) = 'array' AND jsonb_typeof(blocking_evidence) = 'array'
        AND jsonb_typeof(evidence_references) = 'array'),
    CONSTRAINT chk_readiness_hashes CHECK (
        evaluation_hash ~ '^[0-9a-f]{64}$'
        AND (mapping_content_hash IS NULL OR mapping_content_hash ~ '^[0-9a-f]{64}$'))
);

CREATE INDEX idx_readiness_merchant_capability
    ON capability_readiness_evaluation (merchant_id, capability, evaluated_at DESC);

CREATE TABLE agent_commerce_manifest (
    manifest_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schema_version INTEGER NOT NULL DEFAULT 1,
    merchant_id UUID NOT NULL,
    agentization_run_id UUID NOT NULL,
    manifest_version INTEGER NOT NULL,
    policy_snapshot_id UUID,
    catalogue_version VARCHAR(128),
    publication_actor_id UUID NOT NULL,
    publication_component VARCHAR(64) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    manifest_hash CHAR(64) NOT NULL,
    CONSTRAINT uq_manifest_tenant UNIQUE (manifest_id, merchant_id),
    CONSTRAINT uq_manifest_version UNIQUE (merchant_id, manifest_version),
    CONSTRAINT fk_manifest_run FOREIGN KEY (agentization_run_id, merchant_id)
        REFERENCES agentization_run (run_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_manifest_policy FOREIGN KEY (policy_snapshot_id, merchant_id)
        REFERENCES merchant_policy_snapshot (policy_snapshot_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_manifest_actor FOREIGN KEY (publication_actor_id)
        REFERENCES application_actor (actor_id) ON DELETE RESTRICT,
    CONSTRAINT chk_manifest_schema_version CHECK (schema_version = 1),
    CONSTRAINT chk_manifest_version CHECK (manifest_version > 0),
    CONSTRAINT chk_manifest_hash CHECK (manifest_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE agent_commerce_manifest_capability (
    manifest_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    capability VARCHAR(64) NOT NULL,
    advertised BOOLEAN NOT NULL,
    readiness VARCHAR(16) NOT NULL,
    executable_mapping_proposal_id UUID,
    readiness_evaluation_id UUID NOT NULL,
    PRIMARY KEY (manifest_id, capability),
    CONSTRAINT fk_manifest_capability_manifest FOREIGN KEY (manifest_id, merchant_id)
        REFERENCES agent_commerce_manifest (manifest_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_manifest_capability_mapping FOREIGN KEY (executable_mapping_proposal_id, merchant_id)
        REFERENCES capability_mapping_proposal (mapping_proposal_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_manifest_capability_evaluation FOREIGN KEY (readiness_evaluation_id, merchant_id)
        REFERENCES capability_readiness_evaluation (readiness_evaluation_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT chk_manifest_capability CHECK (capability IN (
        'SEARCH_PRODUCTS', 'GET_AVAILABILITY', 'GET_QUOTE', 'PLACE_ORDER',
        'GET_ORDER_STATE', 'CANCEL_ORDER', 'RETURN_ITEM', 'REFUND', 'PURCHASE')),
    CONSTRAINT chk_manifest_readiness CHECK (readiness IN ('READY', 'BLOCKED', 'UNTESTED')),
    CONSTRAINT chk_manifest_advertised CHECK (advertised = (readiness = 'READY')),
    CONSTRAINT chk_manifest_executable CHECK (
        (readiness = 'READY' AND executable_mapping_proposal_id IS NOT NULL)
        OR (readiness <> 'READY' AND executable_mapping_proposal_id IS NULL))
);

CREATE INDEX idx_manifest_merchant_latest
    ON agent_commerce_manifest (merchant_id, manifest_version DESC);
