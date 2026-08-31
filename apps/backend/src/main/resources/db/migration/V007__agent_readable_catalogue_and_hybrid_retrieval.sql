ALTER TABLE agent_observation
    DROP CONSTRAINT chk_agent_observation_tool,
    ADD CONSTRAINT chk_agent_observation_tool CHECK (tool_name IN (
        'INSPECT_SPEC', 'INSPECT_SCHEMA', 'PROPOSE_MAPPING', 'VALIDATE_MAPPING',
        'RUN_CONTRACT_TEST', 'INSPECT_TEST_FAILURE', 'REVISE_MAPPING',
        'INSPECT_POLICY', 'EXTRACT_POLICY_RULES', 'INSPECT_CATALOG_SAMPLE',
        'REQUEST_MERCHANT_CLARIFICATION', 'REQUEST_MERCHANT_APPROVAL',
        'PUBLISH_MANIFEST_CANDIDATE'));

CREATE TABLE catalogue_version (
    catalogue_version_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL REFERENCES merchant (merchant_id) ON DELETE RESTRICT,
    version_number INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    source_format VARCHAR(8) NOT NULL,
    source_hash CHAR(64) NOT NULL,
    content_hash CHAR(64),
    accepted_count INTEGER NOT NULL DEFAULT 0,
    rejected_count INTEGER NOT NULL DEFAULT 0,
    enriched_count INTEGER NOT NULL DEFAULT 0,
    unresolved_count INTEGER NOT NULL DEFAULT 0,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    uploaded_by_actor_id UUID NOT NULL REFERENCES application_actor (actor_id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    CONSTRAINT uq_catalogue_version_tenant UNIQUE (catalogue_version_id, merchant_id),
    CONSTRAINT uq_catalogue_version_number UNIQUE (merchant_id, version_number),
    CONSTRAINT chk_catalogue_version_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'REJECTED')),
    CONSTRAINT chk_catalogue_source_format CHECK (source_format IN ('JSON', 'CSV')),
    CONSTRAINT chk_catalogue_hashes CHECK (source_hash ~ '^[0-9a-f]{64}$'
        AND (content_hash IS NULL OR content_hash ~ '^[0-9a-f]{64}$')),
    CONSTRAINT chk_catalogue_counts CHECK (version_number > 0 AND accepted_count >= 0
        AND rejected_count >= 0 AND enriched_count >= 0 AND unresolved_count >= 0),
    CONSTRAINT chk_catalogue_evidence CHECK (jsonb_typeof(evidence) = 'object'),
    CONSTRAINT chk_catalogue_published CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL AND content_hash IS NOT NULL AND accepted_count > 0)
        OR (status <> 'PUBLISHED' AND published_at IS NULL))
);

CREATE INDEX idx_catalogue_latest ON catalogue_version (merchant_id, status, version_number DESC);

CREATE TABLE merchant_product (
    product_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    catalogue_version_id UUID NOT NULL,
    merchant_sku VARCHAR(128) NOT NULL,
    gtin VARCHAR(14),
    brand VARCHAR(256),
    canonical_name VARCHAR(512) NOT NULL,
    normalized_name VARCHAR(512) NOT NULL,
    variant VARCHAR(256),
    size_storage VARCHAR(128),
    colour VARCHAR(128),
    category VARCHAR(256),
    description VARCHAR(4000),
    active BOOLEAN NOT NULL,
    source_record_id VARCHAR(256) NOT NULL,
    search_document TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(canonical_name, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(brand, '')), 'B') ||
        setweight(to_tsvector('simple', coalesce(variant, '') || ' ' || coalesce(category, '') || ' ' || coalesce(description, '')), 'C')) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_merchant_product_tenant UNIQUE (product_id, merchant_id),
    CONSTRAINT uq_merchant_product_version_sku UNIQUE (merchant_id, catalogue_version_id, merchant_sku),
    CONSTRAINT uq_merchant_product_source UNIQUE (merchant_id, catalogue_version_id, source_record_id),
    CONSTRAINT fk_product_catalogue FOREIGN KEY (catalogue_version_id, merchant_id)
        REFERENCES catalogue_version (catalogue_version_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT chk_product_sku CHECK (length(trim(merchant_sku)) BETWEEN 1 AND 128),
    CONSTRAINT chk_product_gtin CHECK (gtin IS NULL OR gtin ~ '^[0-9]{8,14}$'),
    CONSTRAINT chk_product_name CHECK (length(trim(canonical_name)) BETWEEN 1 AND 512)
);

CREATE UNIQUE INDEX uq_merchant_product_version_gtin
    ON merchant_product (merchant_id, catalogue_version_id, gtin) WHERE gtin IS NOT NULL;
CREATE INDEX idx_product_search_document ON merchant_product USING GIN (search_document);
CREATE INDEX idx_product_name_trgm ON merchant_product USING GIN (normalized_name gin_trgm_ops);
CREATE INDEX idx_product_identity ON merchant_product (merchant_id, catalogue_version_id, merchant_sku, gtin);

CREATE FUNCTION reject_published_catalogue_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM catalogue_version WHERE catalogue_version_id=OLD.catalogue_version_id AND status='PUBLISHED') THEN
        RAISE EXCEPTION 'published catalogue product identity is immutable';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER trg_product_published_immutable BEFORE UPDATE OR DELETE ON merchant_product
    FOR EACH ROW EXECUTE FUNCTION reject_published_catalogue_mutation();

CREATE FUNCTION reject_published_version_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status='PUBLISHED' THEN RAISE EXCEPTION 'published catalogue version is immutable'; END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER trg_catalogue_version_published_immutable BEFORE UPDATE OR DELETE ON catalogue_version
    FOR EACH ROW EXECUTE FUNCTION reject_published_version_mutation();

CREATE TABLE merchant_product_commerce_state (
    commerce_state_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    catalogue_version_id UUID NOT NULL,
    product_id UUID NOT NULL,
    price_minor BIGINT,
    currency CHAR(3),
    stock_quantity BIGINT,
    availability VARCHAR(16) NOT NULL,
    observation_source VARCHAR(128) NOT NULL,
    source_version VARCHAR(128),
    observed_at TIMESTAMPTZ NOT NULL,
    discovery_only BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_product_commerce_tenant UNIQUE (commerce_state_id, merchant_id),
    CONSTRAINT uq_product_commerce_version UNIQUE (merchant_id, catalogue_version_id, product_id),
    CONSTRAINT fk_commerce_product FOREIGN KEY (product_id, merchant_id)
        REFERENCES merchant_product (product_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_commerce_catalogue FOREIGN KEY (catalogue_version_id, merchant_id)
        REFERENCES catalogue_version (catalogue_version_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT chk_commerce_values CHECK ((price_minor IS NULL OR price_minor >= 0)
        AND (stock_quantity IS NULL OR stock_quantity >= 0)
        AND (currency IS NULL OR currency ~ '^[A-Z]{3}$')),
    CONSTRAINT chk_commerce_availability CHECK (availability IN ('IN_STOCK', 'OUT_OF_STOCK', 'UNKNOWN')),
    CONSTRAINT chk_commerce_discovery_only CHECK (discovery_only)
);
CREATE INDEX idx_commerce_discovery ON merchant_product_commerce_state
    (merchant_id, catalogue_version_id, availability, price_minor);

CREATE TABLE product_identity_resolution (
    identity_resolution_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    catalogue_version_id UUID NOT NULL,
    product_id UUID NOT NULL,
    external_source VARCHAR(32) NOT NULL,
    external_record_id VARCHAR(256) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    matched_fields JSONB NOT NULL,
    conflicting_fields JSONB NOT NULL,
    evidence_hash CHAR(64) NOT NULL,
    resolved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_identity_resolution_tenant UNIQUE (identity_resolution_id, merchant_id),
    CONSTRAINT fk_identity_product FOREIGN KEY (product_id, merchant_id)
        REFERENCES merchant_product (product_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_identity_catalogue FOREIGN KEY (catalogue_version_id, merchant_id)
        REFERENCES catalogue_version (catalogue_version_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT chk_identity_source CHECK (external_source IN ('MERCHANT', 'MANUFACTURER', 'OPEN_FOOD_FACTS')),
    CONSTRAINT chk_identity_outcome CHECK (outcome IN ('EXACT', 'PROBABLE', 'UNRESOLVED', 'CONFLICT')),
    CONSTRAINT chk_identity_json CHECK (jsonb_typeof(matched_fields) = 'object' AND jsonb_typeof(conflicting_fields) = 'object'),
    CONSTRAINT chk_identity_hash CHECK (evidence_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_identity_product_latest ON product_identity_resolution
    (merchant_id, catalogue_version_id, product_id, resolved_at DESC);

CREATE TABLE product_external_fact (
    external_fact_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    catalogue_version_id UUID NOT NULL,
    product_id UUID NOT NULL,
    identity_resolution_id UUID NOT NULL,
    fact_type VARCHAR(32) NOT NULL,
    normalized_value JSONB NOT NULL,
    source VARCHAR(32) NOT NULL,
    source_record_id VARCHAR(256) NOT NULL,
    source_version VARCHAR(128),
    authority_tier VARCHAR(16) NOT NULL,
    resolution_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    observed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    fact_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_external_fact_tenant UNIQUE (external_fact_id, merchant_id),
    CONSTRAINT uq_external_fact_hash UNIQUE (merchant_id, catalogue_version_id, product_id, fact_type, source, fact_hash),
    CONSTRAINT fk_fact_product FOREIGN KEY (product_id, merchant_id)
        REFERENCES merchant_product (product_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_fact_catalogue FOREIGN KEY (catalogue_version_id, merchant_id)
        REFERENCES catalogue_version (catalogue_version_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_fact_resolution FOREIGN KEY (identity_resolution_id, merchant_id)
        REFERENCES product_identity_resolution (identity_resolution_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT chk_fact_type CHECK (fact_type IN ('INGREDIENTS', 'ALLERGEN', 'VEGETARIAN', 'DIET', 'NUTRITION', 'PROTEIN', 'IMAGE', 'BARCODE', 'BRAND')),
    CONSTRAINT chk_fact_source CHECK (source IN ('MERCHANT', 'MANUFACTURER', 'OPEN_FOOD_FACTS')),
    CONSTRAINT chk_fact_authority CHECK (authority_tier IN ('PRIMARY', 'SECONDARY')),
    CONSTRAINT chk_fact_resolution CHECK (resolution_state IN ('ACTIVE', 'STALE', 'CONFLICT', 'UNRESOLVED')),
    CONSTRAINT chk_fact_value CHECK (jsonb_typeof(normalized_value) IN ('object', 'array', 'string', 'number', 'boolean')),
    CONSTRAINT chk_fact_expiry CHECK (expires_at IS NULL OR expires_at > observed_at),
    CONSTRAINT chk_fact_hash CHECK (fact_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_external_fact_resolution ON product_external_fact
    (merchant_id, catalogue_version_id, product_id, fact_type, authority_tier, observed_at DESC);

CREATE TABLE product_embedding (
    product_embedding_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    catalogue_version_id UUID NOT NULL,
    product_id UUID NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    output_dimensions INTEGER NOT NULL,
    input_hash CHAR(64) NOT NULL,
    embedding VECTOR(768),
    indexing_state VARCHAR(16) NOT NULL,
    failure_code VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_product_embedding_tenant UNIQUE (product_embedding_id, merchant_id),
    CONSTRAINT uq_product_embedding_input UNIQUE (merchant_id, catalogue_version_id, product_id, model_name, input_hash),
    CONSTRAINT fk_embedding_product FOREIGN KEY (product_id, merchant_id)
        REFERENCES merchant_product (product_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_embedding_catalogue FOREIGN KEY (catalogue_version_id, merchant_id)
        REFERENCES catalogue_version (catalogue_version_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT chk_embedding_dimension CHECK (output_dimensions = 768),
    CONSTRAINT chk_embedding_state CHECK (indexing_state IN ('READY', 'FAILED')),
    CONSTRAINT chk_embedding_value CHECK ((indexing_state = 'READY' AND embedding IS NOT NULL AND failure_code IS NULL)
        OR (indexing_state = 'FAILED' AND embedding IS NULL AND failure_code IS NOT NULL)),
    CONSTRAINT chk_embedding_hash CHECK (input_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_product_embedding_hnsw ON product_embedding USING hnsw (embedding vector_cosine_ops)
    WHERE indexing_state = 'READY';

CREATE TABLE catalogue_retrieval_evidence (
    retrieval_evidence_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    catalogue_version_id UUID NOT NULL,
    evidence_type VARCHAR(40) NOT NULL,
    outcome VARCHAR(8) NOT NULL,
    query_evidence JSONB NOT NULL,
    result_references JSONB NOT NULL,
    evidence_hash CHAR(64) NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_retrieval_evidence_tenant UNIQUE (retrieval_evidence_id, merchant_id),
    CONSTRAINT fk_retrieval_catalogue FOREIGN KEY (catalogue_version_id, merchant_id)
        REFERENCES catalogue_version (catalogue_version_id, merchant_id) ON DELETE RESTRICT,
    CONSTRAINT chk_retrieval_type CHECK (evidence_type IN ('CATALOGUE_SCHEMA', 'EXACT_PRODUCT_RETRIEVAL', 'NO_MATCH', 'IDENTITY_GATE')),
    CONSTRAINT chk_retrieval_outcome CHECK (outcome IN ('PASS', 'FAIL', 'UNKNOWN')),
    CONSTRAINT chk_retrieval_json CHECK (jsonb_typeof(query_evidence) = 'object' AND jsonb_typeof(result_references) = 'array'),
    CONSTRAINT chk_retrieval_hash CHECK (evidence_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_retrieval_evidence_latest ON catalogue_retrieval_evidence
    (merchant_id, catalogue_version_id, evidence_type, evaluated_at DESC);

ALTER TABLE agent_commerce_manifest_capability
    DROP CONSTRAINT chk_manifest_executable,
    ADD CONSTRAINT chk_manifest_executable CHECK (
        (readiness = 'READY' AND (capability = 'SEARCH_PRODUCTS' OR executable_mapping_proposal_id IS NOT NULL))
        OR (readiness <> 'READY' AND executable_mapping_proposal_id IS NULL));
