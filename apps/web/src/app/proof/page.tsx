import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import concurrencyReport from "../../../../../proof/results/concurrency.json";
import evidenceIndex from "../../../../../proof/results/index.json";
import intentReport from "../../../../../proof/results/intent-eval.json";
import latencyReport from "../../../../../proof/results/latency.json";
import safetyReport from "../../../../../proof/results/latest.json";
import mutationReport from "../../../../../proof/results/mutation.json";
import retrievalReport from "../../../../../proof/results/retrieval.json";
import styles from "./proof.module.css";

export const metadata: Metadata = {
  title: "Evidence proof — Amana",
  description: "Measured, reproducible evidence for Amana's safety and commerce boundaries.",
};

const categoryNotes: Record<string, string> = {
  "Evidence & policy": "UNKNOWN, missing facts, and hostile catalogue copy",
  "Capability readiness": "BLOCKED, UNTESTED, and incomplete contracts",
  "Proposal integrity": "Expiry, bindings, and material cart changes",
  "Money integrity": "Amount, currency, account, and order identity",
  "Callback truth": "Browser evidence held below financial truth",
  "Payment idempotency": "Stable execution-to-order identity",
  "Refund integrity": "Reserved value and provider evidence bounds",
  "Refund idempotency": "Terminal replays cannot call the provider",
};

const exampleIds = [
  "AMANA-MONEY-001",
  "AMANA-PROPOSAL-019",
  "AMANA-CALLBACK-007",
  "AMANA-EVIDENCE-029",
  "AMANA-CAPABILITY-013",
];

const examples = exampleIds
  .map((id) => safetyReport.cases.find((evaluationCase) => evaluationCase.id === id))
  .filter((evaluationCase) => evaluationCase !== undefined);

const evidenceNavigation = [
  ["Overview", "#overview"],
  ["Safety", "#safety"],
  ["Retrieval", "#retrieval"],
  ["Intent", "#intent"],
  ["Negative Controls", "#negative-controls"],
  ["Concurrency", "#concurrency"],
  ["Latency", "#latency"],
] as const;

const rawEvidence = [
  "SCORECARD.md",
  "RETRIEVAL_EVAL.md",
  "INTENT_EVAL.md",
  "MUTATION.md",
  "CONCURRENCY.md",
  "LATENCY.md",
  "latest.json",
] as const;

const githubEvidenceBase =
  "https://github.com/Mahaboob-Ashraf/Agentic-Commerce-Gateway/blob/main/proof/results";

function percentage(value: number): string {
  return new Intl.NumberFormat("en-IN", {
    style: "percent",
    maximumFractionDigits: 2,
  }).format(value);
}

function percentagePointDelta(value: number): string {
  return `${value >= 0 ? "+" : ""}${new Intl.NumberFormat("en-IN", {
    maximumFractionDigits: 2,
  }).format(value * 100)} pp`;
}

function milliseconds(value: number): string {
  return `${new Intl.NumberFormat("en-IN", { maximumFractionDigits: 2 }).format(value)} ms`;
}

function count(value: number): string {
  return value.toLocaleString("en-IN");
}

function humanize(value: string): string {
  return value
    .toLowerCase()
    .split("_")
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}

export default function ProofPage() {
  const retrieval = retrievalReport.summary;
  const intent = intentReport.summary;
  const mutation = mutationReport.summary;
  const concurrency = concurrencyReport.summary;
  const providerLatencySamples = intentReport.details
    .map((detail) => detail.elapsedMillis)
    .filter(
      (value): value is number =>
        typeof value === "number" && Number.isFinite(value),
    );

  const percentile = (values: number[], q: number): number => {
    if (values.length === 0) return 0;

    const sorted = [...values].sort((a, b) => a - b);
    const index = Math.min(
      sorted.length - 1,
      Math.max(0, Math.ceil(sorted.length * q) - 1),
    );

    return sorted[index];
  };

  const providerLatency = {
    environment: "GEMINI_PROVIDER_BACKED_EVALUATION",
    n: providerLatencySamples.length,
    p50: percentile(providerLatencySamples, 0.5),
    p95: percentile(providerLatencySamples, 0.95),
    failures: intent.providerErrors,
  };

  return (
    <main className={styles.page}>
      <nav className={styles.nav} aria-label="Proof navigation">
        <Link className={styles.brand} href="/" aria-label="Amana home">
          <Image
            className={styles.brandMark}
            src="/amana/amana-mark.png"
            alt=""
            width={40}
            height={40}
            priority
          />
          <span>Amana</span>
        </Link>
        <div className={styles.navActions}>
          <Link className={styles.securityLink} href="/architecture">Architecture</Link>
          <Link className={styles.securityLink} href="/security">Security replay</Link>
          <div className={styles.navMeta}>
            <span className={styles.liveDot} aria-hidden="true" />
            EVIDENCE HUB
          </div>
        </div>
      </nav>

      <nav className={styles.evidenceNav} aria-label="Evidence sections">
        {evidenceNavigation.map(([label, href]) => (
          <a href={href} key={href}>{label}</a>
        ))}
      </nav>

      <section className={styles.hero} id="overview">
        <div className={styles.heroCopy}>
          <p className={styles.eyebrow}>Measured commerce evidence</p>
          <h1>Safety is measured,<br />not claimed.</h1>
          <p className={styles.lede}>
            AI handles unstructured meaning and bounded candidate selection. Deterministic software
            controls truth, authority, capability readiness, and money.
          </p>
          <div className={styles.runStamp}>
            <span>Latest evidence index</span>
            <time dateTime={evidenceIndex.provenance.generatedAtUtc}>
              {evidenceIndex.provenance.generatedAtUtc.replace("T", " ").slice(0, 19)} UTC
            </time>
            <span>{evidenceIndex.summary.evidenceCompleteness}</span>
          </div>
        </div>

        <div className={styles.scorecard} aria-label="Deterministic safety headline metrics">
          <div className={styles.totalMetric}>
            <span className={styles.metricLabel}>Deterministic cases</span>
            <strong>{safetyReport.totalCases}</strong>
            <span className={styles.passLine}>
              <span className={styles.check} aria-hidden="true">✓</span>
              {safetyReport.passed} passed
            </span>
          </div>
          <div className={styles.scoreDivider} />
          <div className={styles.sideMetrics}>
            <div>
              <span className={styles.metricLabel}>Hard safety violations</span>
              <strong>{safetyReport.hardSafetyViolations}</strong>
              <span>No unsafe path crossed the boundary</span>
            </div>
            <div>
              <span className={styles.metricLabel}>Fail-closed enforcement</span>
              <strong>{safetyReport.failClosedRate}</strong>
              <span>{count(safetyReport.metrics.failClosedCorrect)} of {count(safetyReport.metrics.failClosedCases)} cases</span>
            </div>
          </div>
        </div>
      </section>

      <section className={styles.overviewSection} aria-labelledby="overview-title">
        <div className={styles.sectionHeading}>
          <div>
            <p className={styles.eyebrow}>Evidence overview</p>
            <h2 id="overview-title">One hub. Distinct measurements.</h2>
          </div>
          <p>Each figure retains its own dataset, environment, and limitation. Test counts are never combined.</p>
        </div>
        <dl className={styles.overviewMetrics}>
          <div><dt>Safety</dt><dd>{safetyReport.passed}/{safetyReport.totalCases}</dd><span>deterministic cases passed</span></div>
          <div><dt>Hybrid retrieval</dt><dd>{percentage(retrieval.hybrid.recallAt5)}</dd><span>Recall@5</span></div>
          <div><dt>Lexical retrieval</dt><dd>{percentage(retrieval.postFixLexicalOnly.recallAt5)}</dd><span>Recall@5</span></div>
          <div><dt>Typed intent</dt><dd>{percentage(intent.fieldAccuracy)}</dd><span>field accuracy</span></div>
          <div><dt>Negative controls</dt><dd>{mutation.killed}/{mutation.mutationsAttempted}</dd><span>selected mutants killed</span></div>
          <div><dt>Concurrency</dt><dd>{concurrency.passed}/{concurrency.operations}</dd><span>bounded scenarios passed</span></div>
        </dl>
        <div className={styles.verificationStrip}>
          <span>Backend: {evidenceIndex.testCounts.backend.tests} tests · {evidenceIndex.testCounts.backend.failures} failures · {evidenceIndex.testCounts.backend.errors} errors · {evidenceIndex.testCounts.backend.skipped} skipped</span>
          <span>Frontend: {evidenceIndex.testCounts.frontend.passed}/{evidenceIndex.testCounts.frontend.tests} passed</span>
        </div>
      </section>

      <section className={styles.categorySection} aria-labelledby="coverage-title" id="safety">
        <div className={styles.sectionHeading}>
          <div>
            <p className={styles.eyebrow}>Deterministic safety</p>
            <h2 id="coverage-title">Eight boundaries. One deterministic verdict.</h2>
          </div>
          <p>Every count below comes from the generated machine-readable safety report.</p>
        </div>
        <div className={styles.categoryGrid}>
          {Object.entries(safetyReport.categoryCounts).map(([category, categoryCount], index) => (
            <article className={styles.categoryCard} key={category}>
              <div className={styles.categoryTopline}>
                <span>{String(index + 1).padStart(2, "0")}</span>
                <strong>{categoryCount}</strong>
              </div>
              <h3>{category}</h3>
              <p>{categoryNotes[category]}</p>
              <div className={styles.categoryBar} aria-hidden="true">
                <span style={{ width: `${(categoryCount / safetyReport.totalCases) * 100 * 2.5}%` }} />
              </div>
            </article>
          ))}
        </div>
      </section>

      <section className={styles.matrixSection} aria-labelledby="matrix-title">
        <div className={styles.sectionHeading}>
          <div>
            <p className={styles.eyebrow}>Deterministic invariants defended</p>
            <h2 id="matrix-title">The evaluation matrix</h2>
          </div>
          <div className={styles.invariantCount}>
            <strong>{safetyReport.defendedInvariantCount}</strong>
            <span>invariants executed</span>
          </div>
        </div>

        <div className={styles.matrix} role="table" aria-label="Executed deterministic safety invariants">
          <div className={`${styles.matrixRow} ${styles.matrixHeader}`} role="row">
            <span role="columnheader">Invariant</span>
            <span role="columnheader">Safety boundary</span>
            <span role="columnheader">Cases</span>
            <span role="columnheader">Result</span>
          </div>
          {safetyReport.invariants.map((invariant) => (
            <div className={styles.matrixRow} role="row" key={invariant.id}>
              <span className={styles.invariantId} role="cell">{invariant.id}</span>
              <span className={styles.invariantText} role="cell">{invariant.description}</span>
              <span className={styles.caseCount} role="cell">{invariant.caseCount}</span>
              <span className={styles.defended} role="cell"><i aria-hidden="true" />Defended</span>
            </div>
          ))}
        </div>
      </section>

      <section className={styles.examplesSection} aria-labelledby="examples-title">
        <div className={styles.sectionHeading}>
          <div>
            <p className={styles.eyebrow}>Adversarial safety examples</p>
            <h2 id="examples-title">Failures that stayed failures.</h2>
          </div>
          <p>Selected from the same {safetyReport.totalCases}-case deterministic report shown above.</p>
        </div>
        <div className={styles.examplesGrid}>
          {examples.map((example) => (
            <article className={styles.exampleCard} key={example.id}>
              <div className={styles.exampleMeta}>
                <span>{example.id}</span>
                <span className={styles.blockedBadge}>Blocked as designed</span>
              </div>
              <h3>{example.title.replace(/ \(variant \d+\)$/, "")}</h3>
              <code>{example.adversarialInput}</code>
              <div className={styles.verdictLine}>
                <span>Expected <strong>{example.expected}</strong></span>
                <span>Observed <strong>{example.actual}</strong></span>
              </div>
            </article>
          ))}
        </div>
      </section>

      <section className={styles.evidenceSection} aria-labelledby="retrieval-title" id="retrieval">
        <div className={styles.sectionHeading}>
          <div>
            <p className={styles.eyebrow}>Labelled catalogue retrieval</p>
            <h2 id="retrieval-title">Retrieval, measured honestly.</h2>
          </div>
          <p>{evidenceIndex.datasets.retrieval.sampleSize} repository-authored labelled queries against the Amazing catalogue fixture.</p>
        </div>
        <div className={styles.comparisonGrid}>
          <article className={styles.evidenceCard}>
            <span className={styles.cardLabel}>Hybrid</span>
            <strong>{percentage(retrieval.hybrid.recallAt5)}</strong>
            <h3>Recall@5</h3>
            <dl className={styles.metricList}>
              <div><dt>Recall@1</dt><dd>{percentage(retrieval.hybrid.recallAt1)}</dd></div>
              <div><dt>Generic category</dt><dd>{percentage(retrieval.hybrid.genericCategorySuccessRate)}</dd></div>
              <div><dt>Typo / ASR-style</dt><dd>{percentage(retrieval.hybrid.typoAsrSuccessRate)}</dd></div>
              <div><dt>No-match accuracy</dt><dd>{percentage(retrieval.hybrid.noMatchAccuracy)}</dd></div>
            </dl>
          </article>
          <article className={styles.evidenceCard}>
            <span className={styles.cardLabel}>Lexical post-fix</span>
            <strong>{percentage(retrieval.postFixLexicalOnly.recallAt5)}</strong>
            <h3>Recall@5</h3>
            <dl className={styles.metricList}>
              <div><dt>Recall@1</dt><dd>{percentage(retrieval.postFixLexicalOnly.recallAt1)}</dd></div>
              <div><dt>Generic category</dt><dd>{percentage(retrieval.postFixLexicalOnly.genericCategorySuccessRate)}</dd></div>
              <div><dt>Typo / ASR-style</dt><dd>{percentage(retrieval.postFixLexicalOnly.typoAsrSuccessRate)}</dd></div>
              <div><dt>No-match accuracy</dt><dd>{percentage(retrieval.postFixLexicalOnly.noMatchAccuracy)}</dd></div>
            </dl>
          </article>
          <article className={`${styles.evidenceCard} ${styles.deltaCard}`}>
            <span className={styles.cardLabel}>Hybrid improvement</span>
            <strong>{percentagePointDelta(retrieval.hybridVsLexicalRecallAt5Delta)}</strong>
            <h3>Recall@5 versus lexical</h3>
            <dl className={styles.metricList}>
              <div><dt>Fabricated products</dt><dd>{retrieval.hybrid.fabricatedProductCount} · {percentage(retrieval.hybrid.fabricatedProductRate)}</dd></div>
              <div><dt>Wrong variants</dt><dd>{retrieval.hybrid.wrongVariantSubstitutionCount} · {percentage(retrieval.hybrid.wrongVariantSubstitutionRate)}</dd></div>
              <div><dt>Multilingual subset</dt><dd>{percentage(retrieval.hybrid.multilingualSuccessRate)}</dd></div>
              <div><dt>Labelled queries</dt><dd>{retrievalReport.provenance.sampleSize}</dd></div>
            </dl>
          </article>
        </div>
        <p className={styles.qualification}>
          The multilingual subset result is reported as measured and is not evidence of strong multilingual retrieval.
          This fixture evaluation does not establish current deployed catalogue embedding readiness.
        </p>
      </section>

      <section className={`${styles.evidenceSection} ${styles.tintedSection}`} aria-labelledby="intent-title" id="intent">
        <div className={styles.sectionHeading}>
          <div>
            <p className={styles.eyebrow}>Natural-language Buyer requests → bounded typed intent</p>
            <h2 id="intent-title">Meaning, inside typed bounds.</h2>
          </div>
          <p>{intentReport.provenance.sampleSize} labelled utterances · {intent.provider} · {intent.model}</p>
        </div>
        <div className={styles.intentLayout}>
          <div className={styles.primaryEvidenceMetric}>
            <span>Overall field accuracy</span>
            <strong>{percentage(intent.fieldAccuracy)}</strong>
            <p>Model interpretation is validated into typed intent. It grants no financial authority.</p>
          </div>
          <dl className={styles.detailMetrics}>
            <div><dt>Category accuracy</dt><dd>{percentage(intent.categoryExtractionAccuracy)}</dd></div>
            <div><dt>Budget accuracy</dt><dd>{percentage(intent.budgetExtractionAccuracy)}</dd></div>
            <div><dt>Exact-identity fields</dt><dd>{percentage(intent.exactProductIdentityFieldAccuracy)}</dd></div>
            <div><dt>Correction / context</dt><dd>{percentage(intent.correctionContextAccuracy)}</dd></div>
            <div><dt>Ambiguity detection</dt><dd>{percentage(intent.ambiguityDetectionAccuracy)}</dd></div>
            <div><dt>Multilingual subset</dt><dd>{percentage(intent.multilingualSubsetAccuracy)}</dd></div>
            <div><dt>Authorization-skip attempts</dt><dd>{intent.authorizationSkipAttempts}</dd></div>
            <div><dt>Attempts gaining authority</dt><dd>{intent.authorizationSkipAttemptsThatGainedAuthority}</dd></div>
          </dl>
        </div>
      </section>

      <section className={styles.evidenceSection} aria-labelledby="negative-title" id="negative-controls">
        <div className={styles.sectionHeading}>
          <div>
            <p className={styles.eyebrow}>Mutation detection</p>
            <h2 id="negative-title">Negative Controls</h2>
          </div>
          <p>Selected production safety guards were intentionally weakened in test-only mutants to verify that the safety suite detects broken behavior.</p>
        </div>
        <div className={styles.negativeControlCard}>
          <dl>
            <div><dt>Attempted</dt><dd>{mutation.mutationsAttempted}</dd></div>
            <div><dt>Killed</dt><dd>{mutation.killed}</dd></div>
            <div><dt>Survived</dt><dd>{mutation.survived}</dd></div>
            <div><dt>Kill rate</dt><dd>{percentage(mutation.killRate)}</dd></div>
          </dl>
          <p>This is selected negative-control coverage, not repository-wide mutation coverage.</p>
        </div>
      </section>

      <section className={`${styles.evidenceSection} ${styles.tintedSection}`} aria-labelledby="concurrency-title" id="concurrency">
        <div className={styles.sectionHeading}>
          <div>
            <p className={styles.eyebrow}>PostgreSQL convergence</p>
            <h2 id="concurrency-title">Bounded concurrency evidence.</h2>
          </div>
          <p>{concurrency.passed} of {concurrency.operations} operation-and-caller scenarios passed.</p>
        </div>
        <div className={styles.tableShell}>
          <table>
            <thead>
              <tr><th>Operation</th><th>Callers</th><th>Rows expected / observed</th><th>Provider calls expected / observed</th><th>Result</th></tr>
            </thead>
            <tbody>
              {concurrencyReport.details.map((operation) => (
                <tr key={`${operation.operation}-${operation.callers}`}>
                  <td>{humanize(operation.operation)}</td>
                  <td>N = {operation.callers}</td>
                  <td>{operation.expectedPersistedRows} / {operation.observedPersistedRows}</td>
                  <td>{operation.expectedProviderCalls} / {operation.observedProviderCalls}</td>
                  <td><span className={styles.passBadge}>{operation.status}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className={styles.qualification}>This is bounded concurrency/idempotency evidence, not production-scale load testing.</p>
      </section>

      <section className={styles.evidenceSection} aria-labelledby="latency-title" id="latency">
        <div className={styles.sectionHeading}>
          <div>
            <p className={styles.eyebrow}>Measured environments, kept separate</p>
            <h2 id="latency-title">Latency without blurred boundaries.</h2>
          </div>
          <p>These are measurements from the stated evaluation environments, not production SLAs.</p>
        </div>

        <div className={styles.latencyGroup}>
          <header>
            <h3>Local deterministic / stub measurements</h3>
            <p>{latencyReport.summary.coldStartTreatment.replaceAll("_", " ").toLowerCase()}</p>
          </header>
          <div className={styles.tableShell}>
            <table>
              <thead><tr><th>Path</th><th>N</th><th>p50</th><th>p95</th><th>Environment</th></tr></thead>
              <tbody>
                {latencyReport.details.map((metric) => (
                  <tr key={metric.metric}>
                    <td>{humanize(metric.metric)}</td>
                    <td>{metric.n}</td>
                    <td>{milliseconds(metric.p50Millis)}</td>
                    <td>{milliseconds(metric.p95Millis)}</td>
                    <td>{humanize(metric.environment)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className={styles.qualification}>The Razorpay order row is a deterministic stub measurement, not live Razorpay latency.</p>
        </div>

        <div className={`${styles.latencyGroup} ${styles.providerLatency}`}>
          <header>
            <h3>Provider-backed Gemini measurement</h3>
            <p>{intent.provider} · {intent.model} · {humanize(providerLatency.environment)}</p>
          </header>
          <dl className={styles.providerMetrics}>
            <div><dt>N</dt><dd>{providerLatency.n}</dd></div>
            <div><dt>p50</dt><dd>{milliseconds(providerLatency.p50)}</dd></div>
            <div><dt>p95</dt><dd>{milliseconds(providerLatency.p95)}</dd></div>
            <div><dt>Failures</dt><dd>{providerLatency.failures}</dd></div>
          </dl>
        </div>
      </section>

      <section className={styles.methodology} aria-label="Methodology">
        <div>
          <p className={styles.eyebrow}>Methodology</p>
          <h2>AI does not grade its own safety.</h2>
        </div>
        <ul>
          <li><span aria-hidden="true">01</span> Deterministic safety cases</li>
          <li><span aria-hidden="true">02</span> Labelled retrieval and intent</li>
          <li><span aria-hidden="true">03</span> Selected negative controls</li>
          <li><span aria-hidden="true">04</span> Environment-qualified measurements</li>
        </ul>
        <p className={styles.methodNote}>
          Production reducers and guards produce the safety verdicts. Provider-backed and local deterministic
          measurements remain visibly separate, and none of these reports grants transaction authority.
        </p>
      </section>

      <section className={styles.rawEvidence} aria-labelledby="raw-evidence-title">
        <div>
          <p className={styles.eyebrow}>Reproduce and inspect</p>
          <h2 id="raw-evidence-title">Inspect the raw evidence</h2>
        </div>
        <ul>
          {rawEvidence.map((artifact) => (
            <li key={artifact}>
              <a href={`${githubEvidenceBase}/${artifact}`}>{artifact}<span aria-hidden="true">↗</span></a>
            </li>
          ))}
        </ul>
      </section>

      <footer className={styles.footer}>
        <span>Amana — Agentic commerce you can trust.</span>
        <span>{safetyReport.passed}/{safetyReport.totalCases} safety cases passed · {safetyReport.hardSafetyViolations} hard violations</span>
      </footer>
    </main>
  );
}
