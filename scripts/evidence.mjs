import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const resultsDir = path.join(root, "proof", "results");
const retrievalPath = path.join(root, "evaluation", "retrieval", "amazing-labelled-v1.json");
const multilingualPath = path.join(root, "evaluation", "retrieval", "multilingual-e2e-labelled-v1.json");
const intentPath = path.join(root, "evaluation", "intent", "buyer-intent-labelled-v1.json");
const command = process.argv[2] ?? "validate";

function readJson(file) { return JSON.parse(fs.readFileSync(file, "utf8")); }
function countClass(cases, name) { return cases.filter(value => value.classes.includes(name)).length; }
function requireValue(condition, message) { if (!condition) throw new Error(message); }

function validateDatasets() {
  const catalogue = readJson(path.join(root, "evaluation", "demo-data", "amazing-catalogue-v1.json"));
  const skus = new Set(catalogue.products.map(product => product.merchantSku));
  const retrieval = readJson(retrievalPath);
  requireValue(retrieval.schemaVersion === "amana-retrieval-dataset-v1", "retrieval schemaVersion is invalid");
  requireValue(retrieval.cases.length >= 72, "retrieval dataset must contain at least 72 cases");
  const retrievalMinimums = new Map([
    ["EXACT_NEAR_EXACT", 10], ["GENERIC_CATEGORY_BUDGET", 12], ["PARAPHRASE_SEMANTIC", 10],
    ["TYPO_ASR", 10], ["MULTILINGUAL", 12], ["HONEST_NO_MATCH", 10], ["INJECTION_BEARING", 8]
  ]);
  for (const [name, minimum] of retrievalMinimums) {
    requireValue(countClass(retrieval.cases, name) >= minimum, `${name} requires ${minimum} retrieval cases`);
  }
  const retrievalIds = new Set();
  for (const item of retrieval.cases) {
    requireValue(!retrievalIds.has(item.id), `duplicate retrieval id ${item.id}`);
    retrievalIds.add(item.id);
    requireValue(Array.isArray(item.classes) && item.classes.length > 0, `${item.id} has no class`);
    requireValue(typeof item.query === "string" && item.query.trim(), `${item.id} has no query`);
    requireValue(["MATCH", "NO_TRUSTWORTHY_MATCH"].includes(item.expectedOutcome), `${item.id} outcome is invalid`);
    requireValue(Array.isArray(item.expectedSkus), `${item.id} expectedSkus is invalid`);
    for (const sku of item.expectedSkus) requireValue(skus.has(sku), `${item.id} labels unknown SKU ${sku}`);
    requireValue(item.expectedOutcome === "MATCH" ? item.expectedSkus.length > 0 : item.expectedSkus.length === 0,
      `${item.id} expected outcome and labels disagree`);
  }

  const multilingual = readJson(multilingualPath);
  requireValue(multilingual.schemaVersion === "amana-multilingual-e2e-dataset-v1", "multilingual E2E schemaVersion is invalid");
  requireValue(multilingual.cases.length === 17, "multilingual E2E dataset must contain 12 recall cases and 5 negative controls");
  requireValue(multilingual.cases.filter(value => value.cohort === "RECALL").length === 12, "multilingual E2E recall cohort must contain 12 cases");
  requireValue(multilingual.cases.filter(value => value.cohort === "NEGATIVE_CONTROL").length === 5, "multilingual E2E negative cohort must contain 5 cases");
  for (const language of ["hi", "hinglish", "te", "ur"]) {
    requireValue(multilingual.cases.filter(value => value.cohort === "RECALL" && value.language === language).length === 3,
      `multilingual E2E ${language} recall cohort must contain 3 cases`);
  }
  const multilingualIds = new Set();
  for (const item of multilingual.cases) {
    requireValue(!multilingualIds.has(item.id), `duplicate multilingual E2E id ${item.id}`);
    multilingualIds.add(item.id);
    requireValue(typeof item.utterance === "string" && item.utterance.trim(), `${item.id} has no utterance`);
    requireValue(typeof item.expectedNormalizedMeaning === "string" && item.expectedNormalizedMeaning.trim(), `${item.id} has no normalized meaning label`);
    requireValue(["VALID_MATCH", "NO_TRUSTWORTHY_MATCH", "CLARIFICATION"].includes(item.expectedOutcome), `${item.id} outcome is invalid`);
    requireValue(Array.isArray(item.expectedSkus), `${item.id} expectedSkus is invalid`);
    for (const sku of item.expectedSkus) requireValue(skus.has(sku), `${item.id} labels unknown SKU ${sku}`);
  }

  const intent = readJson(intentPath);
  requireValue(intent.schemaVersion === "amana-buyer-intent-dataset-v1", "intent schemaVersion is invalid");
  requireValue(intent.cases.length >= 48, "intent dataset must contain at least 48 cases");
  const intentClasses = ["NORMAL_PURCHASE", "BUDGET", "QUANTITY", "CATEGORY", "COLOUR", "SIZE_STORAGE",
    "BRAND_VARIANT", "SUBSTITUTION", "CORRECTION_CONTEXT", "AMBIGUOUS", "MALFORMED_IMPOSSIBLE",
    "MULTILINGUAL", "AUTHORIZATION_SKIP", "PROMPT_INJECTION", "ASR_STYLE"];
  for (const name of intentClasses) requireValue(countClass(intent.cases, name) > 0, `intent class ${name} is empty`);
  const intentIds = new Set();
  for (const item of intent.cases) {
    requireValue(!intentIds.has(item.id), `duplicate intent id ${item.id}`);
    intentIds.add(item.id);
    requireValue(typeof item.utterance === "string" && item.utterance.trim(), `${item.id} has no utterance`);
    requireValue(item.expected && typeof item.expected === "object", `${item.id} has no expected label`);
    if (item.classes.includes("AUTHORIZATION_SKIP")) {
      requireValue(item.expected.authorizationGranted === false, `${item.id} must label authorization as false`);
    }
  }
  return {
    retrieval: { sampleSize: retrieval.cases.length, classCounts: Object.fromEntries([...retrievalMinimums.keys()].map(name => [name, countClass(retrieval.cases, name)])) },
    intent: { sampleSize: intent.cases.length, classCounts: Object.fromEntries(intentClasses.map(name => [name, countClass(intent.cases, name)])) },
    multilingualE2e: { sampleSize: multilingual.cases.length, recallCases: 12, negativeControls: 5 }
  };
}

function output(commandName) {
  const result = spawnSync(commandName, commandName === "git" ? ["rev-parse", "HEAD"] : ["-version"],
    { cwd: root, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  if (result.error || result.status !== 0) return "unavailable";
  return `${result.stdout ?? ""}${result.stderr ?? ""}`.trim() || "unavailable";
}

function gitDirty() {
  try { return execFileSync("git", ["status", "--porcelain"], { cwd: root, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim().length > 0; }
  catch { return null; }
}

function provenance(sampleSize, exactCommand) {
  return {
    commitSha: output("git").split(/\r?\n/)[0] || null,
    workingTreeDirty: gitDirty(),
    generatedAtUtc: new Date().toISOString(),
    runtime: { node: process.version, java: output("java").split(/\r?\n/)[0] },
    operatingSystem: `${os.type()} ${os.release()} ${os.arch()}`,
    hostLabel: process.env.RUNNER_NAME || os.hostname(),
    sampleSize,
    command: exactCommand
  };
}

function markdownProvenance(artifact) {
  const p = artifact.provenance;
  const limitations = (artifact.limitations ?? []).map(value => `- ${value}`).join("\n");
  return ["", "## Provenance", "", `- Schema version: \`${artifact.schemaVersion}\``,
    `- Commit SHA: \`${p.commitSha}\``, `- Working tree dirty: \`${p.workingTreeDirty}\``,
    `- Generated UTC: \`${p.generatedAtUtc}\``, `- Runtime: Java \`${p.runtime?.java}\`; Node \`${p.runtime?.node}\``,
    `- OS / host: \`${p.operatingSystem}\` / \`${p.hostLabel}\``, `- Sample size: ${p.sampleSize}`,
    `- Exact command: \`${p.command}\``, `- Summary: **${artifact.summary?.status}**`, "",
    "## Limitations", "", limitations, "", "## What this does not prove", "",
    artifact.whatThisDoesNotProve, ""].join("\n");
}

function placeholder(fileName, schemaVersion, sampleSize, reason, exactCommand, doesNotProve) {
  const file = path.join(resultsDir, fileName);
  if (fs.existsSync(file)) return;
  const value = {
    schemaVersion,
    provenance: provenance(sampleSize, exactCommand),
    summary: { status: reason, passed: 0, failed: 0 },
    limitations: [reason],
    whatThisDoesNotProve: doesNotProve
  };
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
}

function ensureMarkdown(jsonName, markdownName, title) {
  const markdown = path.join(resultsDir, markdownName);
  if (fs.existsSync(markdown)) return;
  const value = readResult(jsonName);
  fs.writeFileSync(markdown, `# ${title}\n\nStatus: **${statusOf(value)}**\n\nCommand: \`${value?.provenance?.command ?? "NOT_AVAILABLE"}\`\n\n${value?.whatThisDoesNotProve ?? "No result has been established."}\n`);
}

function bootstrap() {
  const validated = validateDatasets();
  fs.mkdirSync(resultsDir, { recursive: true });
  placeholder("retrieval.json", "amana-retrieval-evidence-v1", validated.retrieval.sampleSize,
    "NOT_RUN_TESTCONTAINERS_UNAVAILABLE", "pnpm proof:evaluate:retrieval",
    "No retrieval correctness, vector quality, or hybrid improvement is established until the evaluator runs.");
  placeholder("intent-eval.json", "amana-intent-evidence-v1", validated.intent.sampleSize,
    "NOT_RUN_PROVIDER_CREDENTIAL_UNAVAILABLE", "pnpm proof:evaluate:intent",
    "Dataset validity does not establish Gemini intent accuracy.");
  placeholder("mutation.json", "amana-mutation-evidence-v1", 12,
    "NOT_RUN", "pnpm proof:mutation", "No mutation kill rate is established until the negative controls execute.");
  placeholder("attacks.json", "amana-adversarial-evidence-v1", 20,
    "NOT_RUN", "pnpm proof:adversarial", "No attack is reported blocked until its boundary test executes.");
  placeholder("concurrency.json", "amana-concurrency-evidence-v1", 10,
    "NOT_RUN_TESTCONTAINERS_UNAVAILABLE", "pnpm proof:concurrency",
    "No production load or distributed-system behavior is established by this bounded concurrency suite.");
  placeholder("latency.json", "amana-latency-evidence-v1", 0,
    "NOT_RUN", "pnpm proof:latency", "No SLA or external-provider latency is established.");
  ensureMarkdown("retrieval.json", "RETRIEVAL_EVAL.md", "Retrieval evaluation");
  ensureMarkdown("intent-eval.json", "INTENT_EVAL.md", "Buyer intent evaluation");
  ensureMarkdown("mutation.json", "MUTATION.md", "Selected negative controls");
  ensureMarkdown("attacks.json", "ATTACKS.md", "Focused adversarial evidence");
  ensureMarkdown("concurrency.json", "CONCURRENCY.md", "Concurrency and idempotency evidence");
  ensureMarkdown("latency.json", "LATENCY.md", "Measured latency");
}

function readResult(name) {
  const file = path.join(resultsDir, name);
  return fs.existsSync(file) ? readJson(file) : null;
}

function statusOf(value) {
  if (!value) return "MISSING";
  if (value.summary?.status) return value.summary.status;
  if (typeof value.failed === "number") return value.failed === 0 ? "PASS" : "FAIL";
  return "UNKNOWN";
}

function buildIndex(requireOffline) {
  bootstrap();
  const datasets = validateDatasets();
  const entries = [
    ["deterministicSafety", "latest.json", ".\\apps\\backend\\mvnw.cmd \"-Dtest=dev.agenticcommerce.gateway.proof.SafetyEvaluationTest\" test"],
    ["retrieval", "retrieval.json", "pnpm proof:evaluate:retrieval"],
    ["intent", "intent-eval.json", "pnpm proof:evaluate:intent"],
    ["mutation", "mutation.json", "pnpm proof:mutation"],
    ["adversarial", "attacks.json", "pnpm proof:adversarial"],
    ["concurrency", "concurrency.json", "pnpm proof:concurrency"],
    ["latency", "latency.json", "pnpm proof:latency"]
  ];
  const evidence = {};
  const artifacts = {};
  for (const [key, file, reproductionCommand] of entries) {
    const value = readResult(file);
    artifacts[key] = value;
    evidence[key] = { artifact: `proof/results/${file}`, status: statusOf(value), reproductionCommand };
  }
  if (requireOffline) {
    for (const key of ["deterministicSafety", "mutation", "adversarial", "concurrency", "latency"])
      requireValue(evidence[key].status === "PASS", `${key} evidence is ${evidence[key].status}`);
  }
  const backendSuites = [];
  const reportDirectory = path.join(root, "apps", "backend", "target", "surefire-reports");
  if (fs.existsSync(reportDirectory)) for (const name of fs.readdirSync(reportDirectory).filter(name => name.startsWith("TEST-") && name.endsWith(".xml"))) {
    const xml = fs.readFileSync(path.join(reportDirectory, name), "utf8");
    const match = xml.match(/<testsuite[^>]*\btests="(\d+)"[^>]*\berrors="(\d+)"[^>]*\bskipped="(\d+)"[^>]*\bfailures="(\d+)"/);
    if (match) backendSuites.push({ tests: Number(match[1]), errors: Number(match[2]), skipped: Number(match[3]), failures: Number(match[4]) });
  }
  const backendTests = backendSuites.length ? backendSuites.reduce((sum, value) => ({ tests: sum.tests + value.tests, errors: sum.errors + value.errors, skipped: sum.skipped + value.skipped, failures: sum.failures + value.failures }), { tests: 0, errors: 0, skipped: 0, failures: 0 }) : null;
  const frontendTests = readResult("frontend-tests.json");
  const offlineIncomplete = Object.values(evidence).some(item => item.status.startsWith("NOT_RUN") || item.status === "MISSING");
  const providerPartial = artifacts.retrieval?.summary?.hybridStatus?.startsWith("NOT_RUN")
    || !artifacts.latency?.details?.some(detail => detail.providerBacked === true);
  const index = {
    schemaVersion: "amana-evidence-index-v1",
    provenance: provenance(datasets.retrieval.sampleSize + datasets.intent.sampleSize, "node scripts/evidence.mjs index"),
    summary: { status: Object.values(evidence).some(item => item.status === "FAIL" || item.status === "BREACH") ? "FAIL" : "PASS",
      evidenceCompleteness: offlineIncomplete ? "PARTIAL" : providerPartial ? "OFFLINE_COMPLETE_PROVIDER_PARTIAL" : "COMPLETE" },
    datasets,
    testCounts: { backend: backendTests, frontend: frontendTests?.summary ?? null },
    evidence,
    limitations: ["Provider-backed and Testcontainers-dependent rows retain explicit NOT_RUN states when unavailable."],
    whatThisDoesNotProve: "The index aggregates distinct suites; it is not a combined test count or a certification."
  };
  fs.writeFileSync(path.join(resultsDir, "index.json"), `${JSON.stringify(index, null, 2)}\n`);
  const retrievalSummary = artifacts.retrieval?.summary;
  const intentSummary = artifacts.intent?.summary;
  const mutationSummary = artifacts.mutation?.summary;
  const attackSummary = artifacts.adversarial?.summary;
  const concurrencySummary = artifacts.concurrency?.summary;
  const latencySummary = artifacts.latency?.summary;
  const latencyActual = artifacts.latency?.details?.map(detail => `${detail.metric}: p50=${detail.p50Millis}ms/p95=${detail.p95Millis}ms`).join("<br>");
  const rows = [
    ["Backend report snapshot", backendTests ? `${backendTests.tests} tests; ${backendTests.failures} failures; ${backendTests.errors} errors; ${backendTests.skipped} skipped` : "NOT_AVAILABLE", "Latest Surefire reports; focused reruns can replace individual class totals.", "This snapshot is not a full-suite run total; the measured full verify result is reported separately in README.", ".\\apps\\backend\\mvnw.cmd test"],
    ["Frontend tests", frontendTests ? `${frontendTests.summary.tests} tests; ${frontendTests.summary.failed} failures; ${frontendTests.summary.skipped} skipped` : "NOT_AVAILABLE", "Buyer and Merchant Node test results, kept separate.", "It is not a backend or proof count.", "pnpm web:test"],
    ["Existing deterministic safety", `${evidence.deterministicSafety.status}; ${artifacts.deterministicSafety?.summary?.passed ?? "?"}/${artifacts.deterministicSafety?.provenance?.sampleSize ?? "?"}; hard violations=${artifacts.deterministicSafety?.summary?.hardSafetyViolations ?? "?"}`, "Production deterministic guards pass their labelled corpus.", "It is not mutation, HTTP, concurrency, or provider evidence.", evidence.deterministicSafety.reproductionCommand],
    ["Retrieval discovery", `${evidence.retrieval.status}; legacy lexical R@5=${retrievalSummary?.baseline?.recallAt5 ?? "?"}; post-fix lexical=${retrievalSummary?.postFixLexicalOnly?.recallAt5 ?? "?"}; hybrid-v2=${retrievalSummary?.previousHybridV2?.recallAt5 ?? "NOT_RUN"}; hybrid-v3=${retrievalSummary?.ranker === "hybrid-v3" ? retrievalSummary.hybrid?.recallAt5 : "NOT_RUN"}`, "Same labelled Amazing corpus; valid matches plus related alternatives.", "Related alternatives are not Buyer-eligible matches; no production-traffic claim.", evidence.retrieval.reproductionCommand],
    ["Retrieval valid matches", `v2 R@5=${retrievalSummary?.previousHybridV2ValidMatches?.recallAt5 ?? "NOT_RUN"}; v3=${retrievalSummary?.hybridV3ValidMatches?.recallAt5 ?? "NOT_RUN"}; semantic threshold=${retrievalSummary?.semanticMinimumSimilarity ?? "NOT_RUN"}; new unexpected valid products=${retrievalSummary?.newUnexpectedValidProducts ?? "NOT_RUN"}`, "Measured Buyer qualification with deterministic authority gates.", "Calibration on 80 labels is not held-out validation or execution authority.", evidence.retrieval.reproductionCommand],
    ["Intent", `${evidence.intent.status}; fields=${intentSummary?.fieldAccuracy ?? "?"}; multilingual=${intentSummary?.multilingualSubsetAccuracy ?? "?"}; authority gained=${intentSummary?.authorizationSkipAttemptsThatGainedAuthority ?? "?"}`, "Typed intent accuracy against human labels when provider-backed evaluation runs.", "It does not grant financial authority or prove all languages.", evidence.intent.reproductionCommand],
    ["Negative controls", `${evidence.mutation.status}; killed=${mutationSummary?.killed ?? "?"}/${mutationSummary?.mutationsAttempted ?? "?"}; survivors=${mutationSummary?.survived ?? "?"}`, "Whether selected safety tests detect intentional guard weakening.", "It is not exhaustive mutation coverage.", evidence.mutation.reproductionCommand],
    ["Adversarial", `${evidence.adversarial.status}; blocked=${attackSummary?.blocked ?? "?"}/${attackSummary?.attacks ?? "?"}; breaches=${attackSummary?.breaches ?? "?"}`, "Observed outcomes for the bounded attack catalogue.", "It is not a penetration test.", evidence.adversarial.reproductionCommand],
    ["Concurrency", `${evidence.concurrency.status}; passed=${concurrencySummary?.passed ?? "?"}/${concurrencySummary?.operations ?? "?"} operation/N scenarios`, "Database/idempotency convergence at N=8 and N=32.", "It is not production load testing.", evidence.concurrency.reproductionCommand],
    ["Latency", `${evidence.latency.status}; ${latencyActual ?? `${latencySummary?.metricCount ?? "?"} metrics`}`, "Measured local/stub path latency.", "It is not an SLA and excludes unrun live providers.", evidence.latency.reproductionCommand]
  ];
  const markdown = ["# Amana evidence scorecard", "", `Generated: ${index.provenance.generatedAtUtc}`,
    `Commit: ${index.provenance.commitSha} (workingTreeDirty=${index.provenance.workingTreeDirty})`, "",
    `Evidence completeness: **${index.summary.evidenceCompleteness}**. Backend and frontend counts remain separate and are never summed.`, "",
    "| Evidence | Actual result | What it proves | What it does not prove | Reproduction command |",
    "|---|---|---|---|---|", ...rows.map(row => `| ${row.map(value => String(value).replaceAll("|", "\\|")).join(" | ")} |`), "",
    "## Dataset inventory", "", `- Retrieval cases: ${datasets.retrieval.sampleSize}`,
    `- Intent utterances: ${datasets.intent.sampleSize}`, ""];
  fs.writeFileSync(path.join(resultsDir, "SCORECARD.md"), `${markdown.join("\n").trimEnd()}\n${markdownProvenance(index)}`);
}

function regradeIntent() {
  const artifact = readResult("intent-eval.json");
  requireValue(artifact?.schemaVersion === "amana-intent-evidence-v1" && Array.isArray(artifact.details), "provider-backed intent artifact is unavailable");
  const same = (expected, observed) => typeof expected === "number" && typeof observed === "number"
    ? expected === observed : typeof expected === "string" && typeof observed === "string"
      ? expected.trim().toLocaleLowerCase() === observed.trim().toLocaleLowerCase() : JSON.stringify(expected) === JSON.stringify(observed);
  for (const detail of artifact.details) {
    const matches = {};
    for (const [field, expected] of Object.entries(detail.expected ?? {})) if (field !== "authorizationGranted") matches[field] = same(expected, detail.observed?.[field]);
    detail.fieldMatches = matches; detail.allLabelledFieldsCorrect = Object.values(matches).every(Boolean);
  }
  const values = artifact.details;
  const fieldValues = field => values.filter(value => Object.hasOwn(value.fieldMatches, field)).map(value => value.fieldMatches[field]);
  const accuracy = selected => selected.length ? Math.round(selected.filter(Boolean).length * 10000 / selected.length) / 10000 : 0;
  const classAccuracy = name => { const selected = values.filter(value => value.classes?.includes(name)); return accuracy(selected.map(value => value.allLabelledFieldsCorrect)); };
  const exactFields = new Set(["exactMerchantSku", "exactGtin", "exactBrand", "exactVariant", "exactSizeStorage", "exactColour"]);
  const allFields = values.flatMap(value => Object.values(value.fieldMatches));
  const exact = values.flatMap(value => Object.entries(value.fieldMatches).filter(([field]) => exactFields.has(field)).map(([, passed]) => passed));
  const timings = values.map(value => value.elapsedMillis).filter(Number.isFinite).sort((left, right) => left - right);
  const timing = quantile => timings[Math.max(0, Math.min(timings.length - 1, Math.ceil(quantile * timings.length) - 1))];
  Object.assign(artifact.summary, { fieldAccuracy: accuracy(allFields), categoryExtractionAccuracy: accuracy(fieldValues("categoryRequest")),
    budgetExtractionAccuracy: accuracy(fieldValues("budgetAmountMinor")), exactProductIdentityFieldAccuracy: accuracy(exact),
    correctionContextAccuracy: classAccuracy("CORRECTION_CONTEXT"), ambiguityDetectionAccuracy: accuracy(fieldValues("ambiguityState")),
    multilingualSubsetAccuracy: classAccuracy("MULTILINGUAL"),
    providerLatencyMillis: timings.length ? { environment: "LOCAL_WINDOWS_LIVE_GEMINI", providerBacked: true, n: timings.length,
      min: timings[0], p50: timing(0.50), p95: timing(0.95), p99: timing(0.99), max: timings.at(-1),
      failures: artifact.summary.providerErrors ?? 0,
      rateLimitFailures: values.filter(value => String(value.errorCode ?? "").includes("429")).length } : null });
  artifact.provenance.generatedAtUtc = new Date().toISOString();
  artifact.provenance.providerEvaluationCommand = ".\\mvnw.cmd \"-Dtest=dev.agenticcommerce.gateway.intent.IntentProviderEvaluationTest\" test (RUN_PROVIDER_EVALUATION=true; GEMINI_API_KEY supplied via environment)";
  artifact.provenance.command = "node scripts/evidence.mjs regrade-intent";
  artifact.limitations = [...new Set([...(artifact.limitations ?? []), "Stored normalized provider outputs were regraded after correcting numeric JsonNode equality; Gemini was not called again."])];
  fs.writeFileSync(path.join(resultsDir, "intent-eval.json"), `${JSON.stringify(artifact, null, 2)}\n`);
  const summary = artifact.summary;
  const providerLatency = summary.providerLatencyMillis;
  const markdown = `# Buyer intent evaluation\n\nStatus: **${summary.status}**\n\n- Provider/model: \`${summary.provider}/${summary.model}\`\n- Labelled utterances: ${artifact.provenance.sampleSize}\n- Field accuracy: ${summary.fieldAccuracy}\n- Category accuracy: ${summary.categoryExtractionAccuracy}\n- Budget accuracy: ${summary.budgetExtractionAccuracy}\n- Exact identity-field accuracy: ${summary.exactProductIdentityFieldAccuracy}\n- Correction/context accuracy: ${summary.correctionContextAccuracy}\n- Ambiguity accuracy: ${summary.ambiguityDetectionAccuracy}\n- Multilingual subset accuracy: ${summary.multilingualSubsetAccuracy}\n- Authorization-skip attempts gaining authority: ${summary.authorizationSkipAttemptsThatGainedAuthority}\n- Live provider latency (ms): N=${providerLatency?.n}; min=${providerLatency?.min}; p50=${providerLatency?.p50}; p95=${providerLatency?.p95}; p99=${providerLatency?.p99}; max=${providerLatency?.max}; failures=${providerLatency?.failures}; rate-limit failures=${providerLatency?.rateLimitFailures}\n- Provider evaluation command: \`${artifact.provenance.providerEvaluationCommand}\`\n\nStored normalized outputs were regraded without another provider call after correcting numeric equality in the evaluator.`;
  fs.writeFileSync(path.join(resultsDir, "INTENT_EVAL.md"), `${markdown}\n${markdownProvenance(artifact)}`);
}

try {
  if (command === "validate") {
    process.stdout.write(`${JSON.stringify(validateDatasets(), null, 2)}\n`);
  } else if (command === "bootstrap") {
    bootstrap();
  } else if (command === "index") {
    buildIndex(process.argv.includes("--require-offline"));
  } else if (command === "regrade-intent") {
    regradeIntent();
  } else {
    throw new Error(`unknown command ${command}`);
  }
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
