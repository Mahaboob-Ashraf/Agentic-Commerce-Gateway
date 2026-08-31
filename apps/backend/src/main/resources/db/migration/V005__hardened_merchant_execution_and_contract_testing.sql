ALTER TABLE merchant_approved_endpoint
    DROP CONSTRAINT chk_merchant_approved_endpoint_uri,
    DROP CONSTRAINT chk_merchant_approved_endpoint_status;

ALTER TABLE merchant_approved_endpoint
    ADD COLUMN endpoint_kind VARCHAR(32) NOT NULL DEFAULT 'REMOTE_HTTPS',
    ADD COLUMN hostname TEXT,
    ADD COLUMN port INTEGER NOT NULL DEFAULT 443,
    ADD COLUMN approved_methods JSONB NOT NULL DEFAULT '["GET", "POST"]'::jsonb,
    ADD COLUMN approved_path_templates JSONB NOT NULL
        DEFAULT '["/products", "/quotes", "/cart/price"]'::jsonb,
    ADD COLUMN approved_resolved_addresses JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN approved_by_actor_id UUID,
    ADD COLUMN approved_at TIMESTAMPTZ,
    ADD COLUMN dns_validated_at TIMESTAMPTZ,
    ADD COLUMN revoked_at TIMESTAMPTZ,
    ADD COLUMN credential_reference VARCHAR(256);

UPDATE merchant_approved_endpoint
SET hostname = lower(split_part(split_part(base_uri, '://', 2), '/', 1)),
    approved_at = created_at,
    dns_validated_at = created_at
WHERE hostname IS NULL;

UPDATE merchant_approved_endpoint
SET endpoint_kind = 'LEGACY_DISABLED',
    approval_status = 'REVOKED',
    revoked_at = CURRENT_TIMESTAMP
WHERE base_uri NOT LIKE 'https://%';

ALTER TABLE merchant_approved_endpoint
    ALTER COLUMN hostname SET NOT NULL,
    ADD CONSTRAINT fk_merchant_endpoint_approver FOREIGN KEY (approved_by_actor_id)
        REFERENCES application_actor (actor_id) ON DELETE RESTRICT,
    ADD CONSTRAINT chk_merchant_endpoint_kind CHECK (
        endpoint_kind IN ('REMOTE_HTTPS', 'LEGACY_DISABLED')),
    ADD CONSTRAINT chk_merchant_endpoint_status CHECK (
        approval_status IN ('PENDING', 'APPROVED', 'REVOKED')),
    ADD CONSTRAINT chk_merchant_endpoint_https CHECK (
        (endpoint_kind = 'REMOTE_HTTPS' AND base_uri LIKE 'https://%')
        OR (endpoint_kind = 'LEGACY_DISABLED' AND approval_status = 'REVOKED')),
    ADD CONSTRAINT chk_merchant_endpoint_port CHECK (port BETWEEN 1 AND 65535),
    ADD CONSTRAINT chk_merchant_endpoint_methods CHECK (
        jsonb_typeof(approved_methods) = 'array'
        AND jsonb_array_length(approved_methods) BETWEEN 1 AND 5),
    ADD CONSTRAINT chk_merchant_endpoint_paths CHECK (
        jsonb_typeof(approved_path_templates) = 'array'
        AND jsonb_array_length(approved_path_templates) BETWEEN 1 AND 32),
    ADD CONSTRAINT chk_merchant_endpoint_addresses CHECK (
        jsonb_typeof(approved_resolved_addresses) = 'array'
        AND jsonb_array_length(approved_resolved_addresses) <= 32),
    ADD CONSTRAINT chk_merchant_endpoint_approval CHECK (
        (approval_status = 'APPROVED' AND approved_at IS NOT NULL AND dns_validated_at IS NOT NULL)
        OR approval_status <> 'APPROVED'),
    ADD CONSTRAINT chk_merchant_endpoint_credential_ref CHECK (
        credential_reference IS NULL OR length(credential_reference) BETWEEN 1 AND 256);

CREATE INDEX idx_merchant_endpoint_approval
    ON merchant_approved_endpoint (merchant_id, approval_status, created_at DESC);

ALTER TABLE capability_mapping_proposal
    ADD COLUMN endpoint_id UUID,
    ADD COLUMN validation_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN connect_timeout_ms INTEGER NOT NULL DEFAULT 2000,
    ADD COLUMN request_timeout_ms INTEGER NOT NULL DEFAULT 5000,
    ADD COLUMN maximum_request_bytes INTEGER NOT NULL DEFAULT 32768,
    ADD COLUMN maximum_response_bytes INTEGER NOT NULL DEFAULT 65536,
    ADD COLUMN previous_mapping_proposal_id UUID,
    ADD COLUMN revision_reason VARCHAR(512),
    ADD COLUMN revision_evidence_test_run_id UUID;

UPDATE capability_mapping_proposal mapping
SET endpoint_id = artifact.endpoint_id
FROM openapi_artifact artifact
WHERE mapping.source_artifact_id = artifact.artifact_id
  AND mapping.merchant_id = artifact.merchant_id
  AND mapping.endpoint_id IS NULL;

ALTER TABLE capability_mapping_proposal
    ALTER COLUMN endpoint_id SET NOT NULL,
    ADD CONSTRAINT fk_mapping_proposal_endpoint FOREIGN KEY (endpoint_id, merchant_id)
        REFERENCES merchant_approved_endpoint (endpoint_id, merchant_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_mapping_proposal_previous FOREIGN KEY (previous_mapping_proposal_id, merchant_id)
        REFERENCES capability_mapping_proposal (mapping_proposal_id, merchant_id) ON DELETE RESTRICT,
    ADD CONSTRAINT chk_mapping_validation_status CHECK (
        validation_status IN ('PENDING', 'VALID', 'INVALID')),
    ADD CONSTRAINT chk_mapping_timeouts CHECK (
        connect_timeout_ms BETWEEN 100 AND 10000
        AND request_timeout_ms BETWEEN 100 AND 30000
        AND request_timeout_ms >= connect_timeout_ms),
    ADD CONSTRAINT chk_mapping_sizes CHECK (
        maximum_request_bytes BETWEEN 1 AND 262144
        AND maximum_response_bytes BETWEEN 1 AND 1048576),
    ADD CONSTRAINT chk_mapping_revision CHECK (
        (previous_mapping_proposal_id IS NULL AND revision_reason IS NULL)
        OR (previous_mapping_proposal_id IS NOT NULL AND revision_reason IS NOT NULL));

CREATE INDEX idx_mapping_endpoint
    ON capability_mapping_proposal (merchant_id, endpoint_id, created_at DESC);

CREATE TABLE capability_contract_test_run (
    contract_test_run_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    agentization_run_id UUID NOT NULL,
    mapping_proposal_id UUID NOT NULL,
    capability VARCHAR(64) NOT NULL,
    mapping_version INTEGER NOT NULL,
    test_case_id VARCHAR(128) NOT NULL,
    test_version INTEGER NOT NULL,
    attempt_number INTEGER NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    outcome VARCHAR(16) NOT NULL,
    failure_code VARCHAR(64),
    structured_evidence JSONB NOT NULL,
    response_hash CHAR(64),
    evidence_hash CHAR(64) NOT NULL,
    failure_signature CHAR(64),
    CONSTRAINT uq_contract_test_tenant UNIQUE (contract_test_run_id, merchant_id),
    CONSTRAINT uq_contract_test_attempt UNIQUE (
        agentization_run_id, mapping_proposal_id, test_case_id, attempt_number),
    CONSTRAINT fk_contract_test_run FOREIGN KEY (agentization_run_id, merchant_id)
        REFERENCES agentization_run (run_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_contract_test_mapping FOREIGN KEY (mapping_proposal_id, merchant_id)
        REFERENCES capability_mapping_proposal (mapping_proposal_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT chk_contract_test_capability CHECK (capability IN (
        'SEARCH_PRODUCTS', 'GET_AVAILABILITY', 'GET_QUOTE', 'PLACE_ORDER',
        'GET_ORDER_STATE', 'CANCEL_ORDER', 'RETURN_ITEM', 'REFUND')),
    CONSTRAINT chk_contract_test_version CHECK (
        mapping_version > 0 AND test_version > 0 AND attempt_number > 0),
    CONSTRAINT chk_contract_test_outcome CHECK (outcome IN ('PASS', 'FAIL', 'UNKNOWN')),
    CONSTRAINT chk_contract_test_completion CHECK (completed_at IS NOT NULL),
    CONSTRAINT chk_contract_test_failure CHECK (
        (outcome = 'PASS' AND failure_code IS NULL)
        OR (outcome <> 'PASS' AND failure_code IS NOT NULL)),
    CONSTRAINT chk_contract_test_response_hash CHECK (
        response_hash IS NULL OR response_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_contract_test_evidence_hash CHECK (evidence_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_contract_test_failure_signature CHECK (
        failure_signature IS NULL OR failure_signature ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_contract_test_run_history
    ON capability_contract_test_run (merchant_id, agentization_run_id, started_at DESC);
CREATE INDEX idx_contract_test_failure_signature
    ON capability_contract_test_run (agentization_run_id, failure_signature)
    WHERE failure_signature IS NOT NULL;

ALTER TABLE capability_mapping_proposal
    ADD CONSTRAINT fk_mapping_revision_evidence FOREIGN KEY (revision_evidence_test_run_id, merchant_id)
        REFERENCES capability_contract_test_run (contract_test_run_id, merchant_id) ON DELETE RESTRICT;

ALTER TABLE agentization_run
    ADD COLUMN current_mapping_version INTEGER,
    ADD COLUMN last_failure_signature CHAR(64),
    ADD COLUMN repeated_failure_count INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_agentization_current_mapping CHECK (
        current_mapping_version IS NULL OR current_mapping_version > 0),
    ADD CONSTRAINT chk_agentization_failure_signature CHECK (
        last_failure_signature IS NULL OR last_failure_signature ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT chk_agentization_repeat_count CHECK (repeated_failure_count BETWEEN 0 AND 100);

ALTER TABLE agent_observation
    DROP CONSTRAINT chk_agent_observation_tool,
    ADD COLUMN mapping_version_before INTEGER,
    ADD COLUMN mapping_version_after INTEGER,
    ADD COLUMN contract_test_run_id UUID,
    ADD COLUMN contract_test_outcome VARCHAR(16),
    ADD COLUMN contract_test_failure_code VARCHAR(64),
    ADD COLUMN evidence_references JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT chk_agent_observation_tool CHECK (tool_name IN (
        'INSPECT_SPEC', 'INSPECT_SCHEMA', 'PROPOSE_MAPPING', 'VALIDATE_MAPPING',
        'RUN_CONTRACT_TEST', 'INSPECT_TEST_FAILURE', 'REVISE_MAPPING')),
    ADD CONSTRAINT fk_agent_observation_contract_test FOREIGN KEY (contract_test_run_id, merchant_id)
        REFERENCES capability_contract_test_run (contract_test_run_id, merchant_id) ON DELETE RESTRICT,
    ADD CONSTRAINT chk_agent_observation_mapping_versions CHECK (
        (mapping_version_before IS NULL OR mapping_version_before > 0)
        AND (mapping_version_after IS NULL OR mapping_version_after > 0)),
    ADD CONSTRAINT chk_agent_observation_contract_outcome CHECK (
        contract_test_outcome IS NULL OR contract_test_outcome IN ('PASS', 'FAIL', 'UNKNOWN')),
    ADD CONSTRAINT chk_agent_observation_evidence_refs CHECK (
        jsonb_typeof(evidence_references) = 'array'
        AND jsonb_array_length(evidence_references) <= 32);
