export type PercentileMetric = {
  key: string;
  label: string;
  p50Millis: number;
  p95Millis: number;
  sampleSize: number;
  providerBacked: boolean;
  note?: string;
};

export type LocalMetric = PercentileMetric & {
  environment: string;
};

export type ConcurrencyResult = {
  operation: string;
  callers: number;
  expectedPersistedRows: number;
  observedPersistedRows: number;
  expectedProviderCalls: number;
  observedProviderCalls: number;
  status: "PASS";
};

export type PerformanceEvidence = {
  provider: {
    generatedAtUtc: string;
    command: string;
    provider: string;
    intentModel: string;
    embeddingModel: string;
    ranker: string;
    stages: PercentileMetric[];
    dominantLongTailStage: string;
    limitations: string[];
  };
  local: {
    generatedAtUtc: string;
    command: string;
    environment: string;
    stages: LocalMetric[];
    limitations: string[];
  };
  concurrency: {
    generatedAtUtc: string;
    command: string;
    passed: number;
    operations: number;
    results: ConcurrencyResult[];
    callers: number[];
    limitation: string;
  };
  retrieval: {
    ranker: string;
    semanticMinimumSimilarity: number;
    status: string;
  };
};

type JsonObject = Record<string, unknown>;

function object(value: unknown, path: string): JsonObject {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`Missing or invalid evidence object: ${path}`);
  }
  return value as JsonObject;
}

function string(value: unknown, path: string): string {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error(`Missing or invalid evidence string: ${path}`);
  }
  return value;
}

function number(value: unknown, path: string): number {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    throw new Error(`Missing or invalid evidence number: ${path}`);
  }
  return value;
}

function integer(value: unknown, path: string): number {
  const parsed = number(value, path);
  if (!Number.isInteger(parsed)) throw new Error(`Evidence value must be an integer: ${path}`);
  return parsed;
}

function array(value: unknown, path: string): unknown[] {
  if (!Array.isArray(value) || value.length === 0) {
    throw new Error(`Missing or empty evidence array: ${path}`);
  }
  return value;
}

function expectSchema(report: JsonObject, expected: string, path: string): void {
  if (report.schemaVersion !== expected) {
    throw new Error(`Unsupported ${path} schema: ${String(report.schemaVersion)}`);
  }
}

function expectPass(value: unknown, path: string): void {
  if (value !== "PASS") throw new Error(`Cannot present failed evidence: ${path}`);
}

function readProvenance(report: JsonObject, path: string) {
  const provenance = object(report.provenance, `${path}.provenance`);
  return {
    generatedAtUtc: string(provenance.generatedAtUtc, `${path}.provenance.generatedAtUtc`),
    command: string(provenance.command, `${path}.provenance.command`),
  };
}

const localLabels: Record<string, string> = {
  BUYER_INTENT_COMPILATION: "Intent compilation (local stub)",
  CATALOGUE_RETRIEVAL: "Catalogue retrieval",
  CANDIDATE_CART_CONSTRUCTION: "Candidate cart",
  AUTHORITATIVE_QUOTE: "Authoritative quote",
  CONSTRAINT_VERIFICATION: "Constraint verification",
  PROPOSAL_CONSTRUCTION: "Proposal construction",
  EXECUTION_GATE: "Execution gate",
  RAZORPAY_ORDER_CREATE_STUB: "Razorpay order boundary (stub)",
};

const providerLabels: Record<string, { label: string; providerBacked: boolean; note?: string }> = {
  intentCompile: { label: "Gemini intent", providerBacked: true },
  queryEmbedding: { label: "Query embedding", providerBacked: true },
  localDeterministicRetrieval: {
    label: "Java + PostgreSQL retrieval",
    providerBacked: false,
    note: "Estimated as hybrid-v3 wall time minus the separately timed embedding call.",
  },
  retrievalIncludingEmbedding: { label: "Retrieval including embedding", providerBacked: true },
  totalPreCartDiscovery: { label: "Total pre-cart discovery", providerBacked: true },
};

export function buildPerformanceEvidence(
  latencyInput: unknown,
  multilingualInput: unknown,
  concurrencyInput: unknown,
  retrievalInput: unknown,
  indexInput: unknown,
): PerformanceEvidence {
  const latency = object(latencyInput, "latency");
  const multilingual = object(multilingualInput, "multilingual");
  const concurrency = object(concurrencyInput, "concurrency");
  const retrieval = object(retrievalInput, "retrieval");
  const index = object(indexInput, "index");

  expectSchema(latency, "amana-latency-evidence-v1", "latency");
  expectSchema(multilingual, "amana-multilingual-e2e-evidence-v1", "multilingual");
  expectSchema(concurrency, "amana-concurrency-evidence-v1", "concurrency");
  expectSchema(index, "amana-evidence-index-v1", "index");

  const latencySummary = object(latency.summary, "latency.summary");
  const multilingualSummary = object(multilingual.summary, "multilingual.summary");
  const concurrencySummary = object(concurrency.summary, "concurrency.summary");
  const retrievalSummary = object(retrieval.summary, "retrieval.summary");
  const indexSummary = object(index.summary, "index.summary");
  expectPass(latencySummary.status, "latency.summary.status");
  expectPass(multilingualSummary.status, "multilingual.summary.status");
  expectPass(concurrencySummary.status, "concurrency.summary.status");
  expectPass(retrievalSummary.status, "retrieval.summary.status");
  expectPass(indexSummary.status, "index.summary.status");

  const indexedEvidence = object(index.evidence, "index.evidence");
  for (const artifact of ["latency", "concurrency", "retrieval"]) {
    expectPass(object(indexedEvidence[artifact], `index.evidence.${artifact}`).status, `index.evidence.${artifact}.status`);
  }

  const localStages = array(latency.details, "latency.details").map((item, indexPosition) => {
    const metric = object(item, `latency.details[${indexPosition}]`);
    const key = string(metric.metric, `latency.details[${indexPosition}].metric`);
    const label = localLabels[key];
    if (!label) throw new Error(`Unknown local latency metric: ${key}`);
    if (metric.providerBacked !== false) throw new Error(`Local metric ${key} is unexpectedly provider-backed`);
    return {
      key,
      label,
      p50Millis: number(metric.p50Millis, `${key}.p50Millis`),
      p95Millis: number(metric.p95Millis, `${key}.p95Millis`),
      sampleSize: integer(metric.n, `${key}.n`),
      providerBacked: false,
      environment: string(metric.environment, `${key}.environment`),
    } satisfies LocalMetric;
  });
  if (localStages.length !== Object.keys(localLabels).length) {
    throw new Error("Local latency evidence does not contain the expected stage set");
  }
  const environments = [...new Set(localStages.map(({ environment }) => environment))];
  if (environments.length !== 1) throw new Error("Local latency stages do not share one environment");

  const latencyMillis = object(multilingualSummary.latencyMillis, "multilingual.summary.latencyMillis");
  const providerStages = Object.entries(providerLabels).map(([key, presentation]) => {
    const metric = object(latencyMillis[key], `multilingual.summary.latencyMillis.${key}`);
    return {
      key,
      label: presentation.label,
      p50Millis: number(metric.p50, `${key}.p50`),
      p95Millis: number(metric.p95, `${key}.p95`),
      sampleSize: integer(metric.sampleSize, `${key}.sampleSize`),
      providerBacked: presentation.providerBacked,
      note: presentation.note,
    } satisfies PercentileMetric;
  });
  const providerSampleSizes = new Set(providerStages.map(({ sampleSize }) => sampleSize));
  if (providerSampleSizes.size !== 1) throw new Error("Provider-backed stage sample sizes do not match");

  const comparableStages = providerStages.filter(({ key }) =>
    ["intentCompile", "queryEmbedding", "localDeterministicRetrieval"].includes(key),
  );
  const dominantLongTail = comparableStages.reduce((largest, candidate) =>
    candidate.p95Millis > largest.p95Millis ? candidate : largest,
  );
  if (dominantLongTail.key !== "intentCompile") {
    throw new Error("Current evidence does not support the configured long-tail conclusion");
  }

  const concurrencyResults = array(concurrency.details, "concurrency.details").map((item, indexPosition) => {
    const result = object(item, `concurrency.details[${indexPosition}]`);
    expectPass(result.status, `concurrency.details[${indexPosition}].status`);
    const parsed = {
      operation: string(result.operation, `concurrency.details[${indexPosition}].operation`),
      callers: integer(result.callers, `concurrency.details[${indexPosition}].callers`),
      expectedPersistedRows: integer(result.expectedPersistedRows, `concurrency.details[${indexPosition}].expectedPersistedRows`),
      observedPersistedRows: integer(result.observedPersistedRows, `concurrency.details[${indexPosition}].observedPersistedRows`),
      expectedProviderCalls: integer(result.expectedProviderCalls, `concurrency.details[${indexPosition}].expectedProviderCalls`),
      observedProviderCalls: integer(result.observedProviderCalls, `concurrency.details[${indexPosition}].observedProviderCalls`),
      status: "PASS" as const,
    };
    if (
      parsed.expectedPersistedRows !== parsed.observedPersistedRows ||
      parsed.expectedProviderCalls !== parsed.observedProviderCalls
    ) {
      throw new Error(`Concurrency result ${parsed.operation} did not converge as expected`);
    }
    return parsed;
  });
  const passed = integer(concurrencySummary.passed, "concurrency.summary.passed");
  const operations = integer(concurrencySummary.operations, "concurrency.summary.operations");
  if (passed !== operations || operations !== concurrencyResults.length) {
    throw new Error("Concurrency summary does not match the passing detail rows");
  }

  const retrievalRanker = string(retrievalSummary.ranker, "retrieval.summary.ranker");
  if (retrievalRanker !== string(multilingualSummary.ranker, "multilingual.summary.ranker")) {
    throw new Error("Retrieval and multilingual artifacts disagree on ranker");
  }

  return {
    provider: {
      ...readProvenance(multilingual, "multilingual"),
      provider: string(multilingualSummary.provider, "multilingual.summary.provider"),
      intentModel: string(multilingualSummary.intentModel, "multilingual.summary.intentModel"),
      embeddingModel: string(multilingualSummary.embeddingModel, "multilingual.summary.embeddingModel"),
      ranker: retrievalRanker,
      stages: providerStages,
      dominantLongTailStage: dominantLongTail.label,
      limitations: array(multilingual.limitations, "multilingual.limitations").map((value, indexPosition) =>
        string(value, `multilingual.limitations[${indexPosition}]`),
      ),
    },
    local: {
      ...readProvenance(latency, "latency"),
      environment: environments[0],
      stages: localStages,
      limitations: array(latency.limitations, "latency.limitations").map((value, indexPosition) =>
        string(value, `latency.limitations[${indexPosition}]`),
      ),
    },
    concurrency: {
      ...readProvenance(concurrency, "concurrency"),
      passed,
      operations,
      results: concurrencyResults,
      callers: [...new Set(concurrencyResults.map(({ callers }) => callers))].sort((a, b) => a - b),
      limitation: string(concurrency.whatThisDoesNotProve, "concurrency.whatThisDoesNotProve"),
    },
    retrieval: {
      ranker: retrievalRanker,
      semanticMinimumSimilarity: number(retrievalSummary.semanticMinimumSimilarity, "retrieval.summary.semanticMinimumSimilarity"),
      status: string(retrievalSummary.hybridStatus, "retrieval.summary.hybridStatus"),
    },
  };
}

export function formatLatency(milliseconds: number): string {
  if (!Number.isFinite(milliseconds) || milliseconds < 0) throw new Error("Latency must be a finite non-negative number");
  return milliseconds >= 1000 ? `${(milliseconds / 1000).toFixed(2)} s` : `${milliseconds.toFixed(1)} ms`;
}

export function operationLabel(operation: string): string {
  return operation
    .toLowerCase()
    .split("_")
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}
