export type MerchantActor = {
  actorId: string;
  identityHandle: string;
  role: "BUYER" | "MERCHANT_ADMIN" | "PLATFORM_ADMIN" | "SYSTEM";
};

export type MerchantAccess = {
  merchantId: string;
  merchantKey: string;
  displayName: string;
};

export type MerchantSetup = {
  version: 1;
  merchantId: string | null;
  store: {
    name: string;
    category: string;
    baseUrl: string;
  };
  sources: {
    openApiReference: string;
    catalogueReference: string;
    policyReference: string;
  };
  connection: {
    approvedEndpoint: string;
    credentialReference: string;
  };
  status: "DRAFT" | "REVIEWED" | "HANDOFF_REQUESTED";
  updatedAt: string;
};

export type CapabilityReadiness = "READY" | "BLOCKED" | "UNTESTED";

export type ManifestCapability = {
  capability: string;
  advertised: boolean;
  readiness: CapabilityReadiness;
  executableMappingProposalId: string | null;
  readinessEvaluationId: string | null;
};

export type AgentCommerceManifest = {
  manifestId: string;
  schemaVersion: number;
  merchantId: string;
  runId: string;
  manifestVersion: number;
  policySnapshotId: string;
  catalogueVersion: string;
  publicationActorId: string;
  publicationComponent: string;
  publishedAt: string;
  manifestHash: string;
  capabilities: ManifestCapability[];
};

export type CatalogueHealth = {
  merchantId: string;
  catalogueVersionId: string;
  version: number;
  products: number;
  activeProducts: number;
  exactIdentities: number;
  unresolvedIdentities: number;
  enrichedProducts: number;
  readyEmbeddings: number;
  failedEmbeddings: number;
  staleFacts: number;
  conflictingFacts: number;
};

export type PolicyDocument = {
  policyDocumentId: string;
  merchantId: string;
  documentType: string;
  documentVersion: number;
  title: string;
  contentHash: string;
  createdAt: string;
};

export type MerchantOperationalSnapshot = {
  catalogue: CatalogueHealth | null;
  manifest: AgentCommerceManifest | null;
  policies: PolicyDocument[];
  errors: string[];
};

export type AgentizationRun = {
  runId: string;
  merchantId: string;
  state: string;
  currentCapability: string;
  currentMappingVersion: number | null;
  updatedAt: string;
};

export type AgentObservation = {
  observationId: string;
  runId: string;
  merchantId: string;
  capability: string;
  stepNumber: number;
  orchestrationState: string;
  toolName: string;
  outcome: string;
  reasonCode: string | null;
  conciseRationale: string;
  mappingVersionBefore: number | null;
  mappingVersionAfter: number | null;
  contractTestRunId: string | null;
  contractTestOutcome: string | null;
  contractTestFailureCode: string | null;
  evidenceReferences: unknown;
  createdAt: string;
};

export type CapabilityMappingProposal = {
  mappingProposalId: string;
  runId: string;
  merchantId: string;
  capability: string;
  mappingVersion: number;
  endpointId: string;
  operationId: string | null;
  httpMethod: string;
  pathTemplate: string;
  transformations: Record<string, unknown>;
  validationStatus: string;
  previousMappingProposalId: string | null;
  revisionReason: string | null;
  evidenceContractTestRunId: string | null;
  createdAt: string;
};

export type CapabilityContractTestRun = {
  contractTestRunId: string;
  merchantId: string;
  runId: string;
  mappingProposalId: string;
  capability: string;
  mappingVersion: number;
  testName: string;
  startedAt: string;
  completedAt: string;
  outcome: "PASS" | "FAIL" | "UNKNOWN";
  failureCode: string | null;
  structuredEvidence: Record<string, unknown>;
  evidenceHash: string;
};

export type ReadinessEvaluation = {
  readinessEvaluationId: string;
  merchantId: string;
  runId: string;
  capability: string;
  readiness: CapabilityReadiness;
  mappingProposalId: string | null;
  mappingVersion: number | null;
  contractTestRunId: string | null;
  requiredEvidence: string[];
  satisfiedEvidence: string[];
  missingEvidence: string[];
  blockingReasons: string[];
  evidenceReferences: string[];
  evaluationHash: string;
  evaluatedAt: string;
};

export type MerchantClarification = {
  clarificationId: string;
  runId: string;
  merchantId: string;
  capability: string;
  question: string;
  kind: string;
  status: "OPEN" | "ANSWERED";
  response: string | null;
  responseActorId: string | null;
  createdAt: string;
  answeredAt: string | null;
};

export type WorkbenchRunData = {
  run: AgentizationRun;
  observations: AgentObservation[];
  mappings: CapabilityMappingProposal[];
  tests: CapabilityContractTestRun[];
  readiness: ReadinessEvaluation[];
  clarifications: MerchantClarification[];
};
