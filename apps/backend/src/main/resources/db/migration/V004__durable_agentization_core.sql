CREATE TABLE merchant_approved_endpoint (
    endpoint_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    base_uri TEXT NOT NULL,
    approval_status VARCHAR(32) NOT NULL DEFAULT 'APPROVED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_merchant_approved_endpoint_tenant UNIQUE (endpoint_id, merchant_id),
    CONSTRAINT fk_merchant_approved_endpoint_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchant (merchant_id) ON DELETE RESTRICT,
    CONSTRAINT chk_merchant_approved_endpoint_status CHECK (approval_status = 'APPROVED'),
    CONSTRAINT chk_merchant_approved_endpoint_uri CHECK (
        base_uri LIKE 'https://%' OR base_uri LIKE 'http://localhost:%'
    )
);

CREATE INDEX idx_merchant_approved_endpoint_merchant
    ON merchant_approved_endpoint (merchant_id);

CREATE TABLE openapi_artifact (
    artifact_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    endpoint_id UUID NOT NULL,
    artifact_type VARCHAR(32) NOT NULL,
    artifact_version VARCHAR(64) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    document JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_openapi_artifact_tenant UNIQUE (artifact_id, merchant_id),
    CONSTRAINT uq_openapi_artifact_content UNIQUE (merchant_id, endpoint_id, content_hash),
    CONSTRAINT fk_openapi_artifact_endpoint FOREIGN KEY (endpoint_id, merchant_id)
        REFERENCES merchant_approved_endpoint (endpoint_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT chk_openapi_artifact_type CHECK (artifact_type = 'OPENAPI'),
    CONSTRAINT chk_openapi_artifact_hash CHECK (content_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_openapi_artifact_merchant_endpoint
    ON openapi_artifact (merchant_id, endpoint_id, created_at DESC);

CREATE TABLE agentization_run (
    run_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    created_by_actor_id UUID NOT NULL,
    source_artifact_id UUID NOT NULL,
    target_capability VARCHAR(64) NOT NULL,
    current_capability VARCHAR(64) NOT NULL,
    orchestration_state VARCHAR(64) NOT NULL,
    step_count INTEGER NOT NULL DEFAULT 0,
    max_step_budget INTEGER NOT NULL,
    wall_clock_deadline TIMESTAMPTZ NOT NULL,
    last_observation_id UUID,
    terminal_reason VARCHAR(512),
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_agentization_run_tenant UNIQUE (run_id, merchant_id),
    CONSTRAINT fk_agentization_run_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchant (merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_agentization_run_actor FOREIGN KEY (created_by_actor_id)
        REFERENCES application_actor (actor_id) ON DELETE RESTRICT,
    CONSTRAINT fk_agentization_run_artifact FOREIGN KEY (source_artifact_id, merchant_id)
        REFERENCES openapi_artifact (artifact_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT chk_agentization_run_capability CHECK (
        target_capability IN ('SEARCH_PRODUCTS', 'GET_AVAILABILITY', 'GET_QUOTE', 'PLACE_ORDER',
            'GET_ORDER_STATE', 'CANCEL_ORDER', 'RETURN_ITEM', 'REFUND')
        AND current_capability IN ('SEARCH_PRODUCTS', 'GET_AVAILABILITY', 'GET_QUOTE', 'PLACE_ORDER',
            'GET_ORDER_STATE', 'CANCEL_ORDER', 'RETURN_ITEM', 'REFUND')
    ),
    CONSTRAINT chk_agentization_run_state CHECK (orchestration_state IN (
        'AGENTIZATION_CREATED', 'INPUTS_VALIDATING', 'INSPECTING_API', 'MAPPING_CAPABILITY',
        'WAITING_FOR_MERCHANT_APPROVAL', 'TESTING_CAPABILITY', 'DIAGNOSING_FAILURE',
        'REVISING_MAPPING', 'WAITING_FOR_MERCHANT_CLARIFICATION', 'READY_CANDIDATE',
        'COMPLETE', 'BLOCKED', 'BUDGET_EXHAUSTED', 'FAILED')),
    CONSTRAINT chk_agentization_run_budget CHECK (
        max_step_budget BETWEEN 1 AND 100 AND step_count BETWEEN 0 AND max_step_budget
    ),
    CONSTRAINT chk_agentization_run_deadline CHECK (wall_clock_deadline > created_at),
    CONSTRAINT chk_agentization_run_completion CHECK (
        (orchestration_state IN ('COMPLETE', 'BLOCKED', 'BUDGET_EXHAUSTED', 'FAILED')
            AND completed_at IS NOT NULL)
        OR
        (orchestration_state NOT IN ('COMPLETE', 'BLOCKED', 'BUDGET_EXHAUSTED', 'FAILED')
            AND completed_at IS NULL)
    )
);

CREATE INDEX idx_agentization_run_merchant_created
    ON agentization_run (merchant_id, created_at DESC);
CREATE INDEX idx_agentization_run_active_deadline
    ON agentization_run (wall_clock_deadline)
    WHERE completed_at IS NULL;

CREATE TABLE agent_observation (
    observation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    capability VARCHAR(64) NOT NULL,
    step_number INTEGER NOT NULL,
    orchestration_state VARCHAR(64) NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    input_hash CHAR(64) NOT NULL,
    structured_result JSONB NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    concise_rationale VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_observation_step UNIQUE (run_id, step_number),
    CONSTRAINT uq_agent_observation_tenant UNIQUE (observation_id, run_id, merchant_id),
    CONSTRAINT fk_agent_observation_run FOREIGN KEY (run_id, merchant_id)
        REFERENCES agentization_run (run_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT chk_agent_observation_capability CHECK (capability IN (
        'SEARCH_PRODUCTS', 'GET_AVAILABILITY', 'GET_QUOTE', 'PLACE_ORDER',
        'GET_ORDER_STATE', 'CANCEL_ORDER', 'RETURN_ITEM', 'REFUND')),
    CONSTRAINT chk_agent_observation_state CHECK (orchestration_state IN (
        'AGENTIZATION_CREATED', 'INPUTS_VALIDATING', 'INSPECTING_API', 'MAPPING_CAPABILITY',
        'WAITING_FOR_MERCHANT_APPROVAL', 'TESTING_CAPABILITY', 'DIAGNOSING_FAILURE',
        'REVISING_MAPPING', 'WAITING_FOR_MERCHANT_CLARIFICATION', 'READY_CANDIDATE',
        'COMPLETE', 'BLOCKED', 'BUDGET_EXHAUSTED', 'FAILED')),
    CONSTRAINT chk_agent_observation_tool CHECK (
        tool_name IN ('INSPECT_SPEC', 'INSPECT_SCHEMA', 'PROPOSE_MAPPING')),
    CONSTRAINT chk_agent_observation_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
    CONSTRAINT chk_agent_observation_hash CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_agent_observation_step CHECK (step_number > 0)
);

CREATE INDEX idx_agent_observation_run_step
    ON agent_observation (run_id, step_number);

ALTER TABLE agentization_run
    ADD CONSTRAINT fk_agentization_run_last_observation
    FOREIGN KEY (last_observation_id, run_id, merchant_id)
    REFERENCES agent_observation (observation_id, run_id, merchant_id) ON DELETE RESTRICT;

CREATE TABLE capability_mapping_proposal (
    mapping_proposal_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    run_id UUID NOT NULL,
    capability VARCHAR(64) NOT NULL,
    mapping_version INTEGER NOT NULL,
    source_artifact_id UUID NOT NULL,
    source_operation_id VARCHAR(256),
    http_method VARCHAR(16) NOT NULL,
    path_template VARCHAR(1024) NOT NULL,
    request_bindings JSONB NOT NULL,
    response_bindings JSONB NOT NULL,
    transformations JSONB NOT NULL,
    amount_interpretation JSONB NOT NULL,
    currency_interpretation JSONB NOT NULL,
    model_provider VARCHAR(128),
    model_name VARCHAR(256),
    proposal_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_mapping_proposal_tenant UNIQUE (mapping_proposal_id, merchant_id),
    CONSTRAINT uq_mapping_proposal_version UNIQUE (run_id, capability, mapping_version),
    CONSTRAINT fk_mapping_proposal_run FOREIGN KEY (run_id, merchant_id)
        REFERENCES agentization_run (run_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_mapping_proposal_artifact FOREIGN KEY (source_artifact_id, merchant_id)
        REFERENCES openapi_artifact (artifact_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT chk_mapping_proposal_capability CHECK (capability IN (
        'SEARCH_PRODUCTS', 'GET_AVAILABILITY', 'GET_QUOTE', 'PLACE_ORDER',
        'GET_ORDER_STATE', 'CANCEL_ORDER', 'RETURN_ITEM', 'REFUND')),
    CONSTRAINT chk_mapping_proposal_version CHECK (mapping_version > 0),
    CONSTRAINT chk_mapping_proposal_method CHECK (
        http_method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')),
    CONSTRAINT chk_mapping_proposal_status CHECK (
        proposal_status IN ('PROPOSED', 'SUPERSEDED', 'AWAITING_APPROVAL'))
);

CREATE INDEX idx_mapping_proposal_merchant_run
    ON capability_mapping_proposal (merchant_id, run_id, created_at DESC);
