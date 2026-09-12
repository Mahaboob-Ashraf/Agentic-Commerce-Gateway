import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import concurrencyReport from "../../../../../proof/results/concurrency.json";
import evidenceIndex from "../../../../../proof/results/index.json";
import latencyReport from "../../../../../proof/results/latency.json";
import multilingualReport from "../../../../../proof/results/multilingual-e2e.json";
import retrievalReport from "../../../../../proof/results/retrieval.json";
import {
  buildPerformanceEvidence,
  formatLatency,
  operationLabel,
} from "@/lib/performance/performance-evidence";
import styles from "./performance.module.css";

export const metadata: Metadata = {
  title: "Performance evidence - Amana",
  description: "Measured Buyer latency, retrieval cost, and concurrent correctness evidence for Amana.",
};

const evidence = buildPerformanceEvidence(
  latencyReport,
  multilingualReport,
  concurrencyReport,
  retrievalReport,
  evidenceIndex,
);

const providerStage = (key: string) => {
  const stage = evidence.provider.stages.find((candidate) => candidate.key === key);
  if (!stage) throw new Error(`Missing validated provider stage ${key}`);
  return stage;
};

const localStage = (key: string) => {
  const stage = evidence.local.stages.find((candidate) => candidate.key === key);
  if (!stage) throw new Error(`Missing validated local stage ${key}`);
  return stage;
};

const intent = providerStage("intentCompile");
const embedding = providerStage("queryEmbedding");
const deterministicRetrieval = providerStage("localDeterministicRetrieval");
const retrievalWithEmbedding = providerStage("retrievalIncludingEmbedding");
const totalDiscovery = providerStage("totalPreCartDiscovery");
const localCatalogueRetrieval = localStage("CATALOGUE_RETRIEVAL");

const commerceStageKeys = [
  "CANDIDATE_CART_CONSTRUCTION",
  "AUTHORITATIVE_QUOTE",
  "CONSTRAINT_VERIFICATION",
  "PROPOSAL_CONSTRUCTION",
  "EXECUTION_GATE",
  "RAZORPAY_ORDER_CREATE_STUB",
] as const;

const architectureSteps = [
  ["FTS", "PostgreSQL full-text candidates"],
  ["Trigram", "Typo and near-string evidence"],
  ["pgvector", "One query vector candidate pass"],
  ["Qualification", "Hybrid score, then local semantic reuse"],
  ["Exact gates", "Identity, budget, safety, and authority remain deterministic"],
] as const;

export default function PerformancePage() {
  return (
    <main className={styles.page}>
      <nav className={styles.nav} aria-label="Performance navigation">
        <Link className={styles.brand} href="/" aria-label="Amana home">
          <Image src="/amana/amana-mark.png" alt="" width={40} height={40} priority />
          <span>Amana</span>
        </Link>
        <div className={styles.navLinks}>
          <span><i aria-hidden="true" /> PERFORMANCE / EVIDENCE</span>
          <Link href="/architecture">Architecture</Link>
          <Link href="/security">Security</Link>
          <Link href="/failure-lab">Failure Lab</Link>
          <Link href="/proof">Proof</Link>
        </div>
      </nav>

      <header className={styles.hero}>
        <div>
          <p className={styles.eyebrow}>Performance / evidence</p>
          <h1>The database is <em>not the bottleneck.</em></h1>
          <p className={styles.lede}>
            Measured Amana latency shows that the long tail comes primarily from model-provider
            calls, while deterministic Java/PostgreSQL commerce paths remain comparatively small.
          </p>
        </div>
        <aside className={styles.finding}>
          <span>Current measured finding</span>
          <strong>The long tail is dominated by {evidence.provider.dominantLongTailStage}, not Java/PostgreSQL retrieval.</strong>
          <p>Bounded multilingual provider run; local machine; {intent.sampleSize} recall-path samples. Not a production SLA.</p>
        </aside>
      </header>

      <section className={styles.heroMetrics} aria-label="Headline performance evidence">
        <article><span>Buyer pre-cart p50</span><strong>{formatLatency(totalDiscovery.p50Millis)}</strong><small>provider-backed</small></article>
        <article><span>Buyer pre-cart p95</span><strong>{formatLatency(totalDiscovery.p95Millis)}</strong><small>provider-backed</small></article>
        <article><span>Java + PostgreSQL p50</span><strong>{formatLatency(deterministicRetrieval.p50Millis)}</strong><small>same Buyer run</small></article>
        <article><span>Concurrent scenarios</span><strong>{evidence.concurrency.passed} / {evidence.concurrency.operations}</strong><small>correctness passed</small></article>
      </section>

      <section className={styles.section} aria-labelledby="buyer-latency-title">
        <div className={styles.sectionHeading}>
          <div>
            <p className={styles.eyebrow}>A / Provider-backed Buyer path</p>
            <h2 id="buyer-latency-title">The model call owns the tail.</h2>
          </div>
          <p>Multilingual utterance to pre-cart discovery, with real Gemini intent and embedding providers.</p>
        </div>

        <div className={styles.runMeta}>
          <span>Provider <strong>{evidence.provider.provider}</strong></span>
          <span>Intent <strong>{evidence.provider.intentModel}</strong></span>
          <span>Embedding <strong>{evidence.provider.embeddingModel}</strong></span>
          <span>Ranker <strong>{evidence.provider.ranker}</strong></span>
          <span>Sample <strong>n={intent.sampleSize}</strong></span>
        </div>

        <div className={styles.latencyRows}>
          {[intent, embedding, deterministicRetrieval, totalDiscovery].map((stage) => (
            <article key={stage.key} data-provider-backed={stage.providerBacked}>
              <div className={styles.stageIdentity}>
                <span>{stage.providerBacked ? "PROVIDER / PIPELINE" : "LOCAL DETERMINISTIC"}</span>
                <h3>{stage.label}</h3>
              </div>
              <dl>
                <div><dt>p50</dt><dd>{formatLatency(stage.p50Millis)}</dd></div>
                <div><dt>p95</dt><dd>{formatLatency(stage.p95Millis)}</dd></div>
              </dl>
              {stage.note ? <p>{stage.note}</p> : null}
            </article>
          ))}
        </div>
        <p className={styles.annotation}>
          Percentiles describe the same small recall cohort but are not additive; no percentage-of-total claim is made.
          Gemini intent p95 is {formatLatency(intent.p95Millis)}, versus {formatLatency(deterministicRetrieval.p95Millis)}
          for estimated local deterministic retrieval.
        </p>
      </section>

      <section className={`${styles.section} ${styles.retrievalSection}`} aria-labelledby="retrieval-title">
        <div className={styles.sectionHeading}>
          <div>
            <p className={styles.eyebrow}>Retrieval performance</p>
            <h2 id="retrieval-title">Retrieval is two very different costs.</h2>
          </div>
          <p>External embedding latency is materially larger than local scoring and database work in this measured run.</p>
        </div>

        <div className={styles.retrievalMetrics}>
          {[deterministicRetrieval, embedding, retrievalWithEmbedding].map((stage) => (
            <article key={stage.key}>
              <span>{stage.label}</span>
              <strong>{formatLatency(stage.p50Millis)}</strong>
              <dl><dt>p95</dt><dd>{formatLatency(stage.p95Millis)}</dd><dt>n</dt><dd>{stage.sampleSize}</dd></dl>
            </article>
          ))}
        </div>

        <div className={styles.architectureGrid}>
          <ol aria-label="Hybrid retrieval architecture">
            {architectureSteps.map(([title, detail], index) => (
              <li key={title}><span>{String(index + 1).padStart(2, "0")}</span><div><strong>{title}</strong><small>{detail}</small></div></li>
            ))}
          </ol>
          <aside className={styles.callProof} aria-label="Verified hybrid-v3 provider call count">
            <p className={styles.eyebrow}>Focused counting proof</p>
            <h3>Semantic qualification reuses retrieved evidence.</h3>
            <dl>
              <div><dt>Query embeddings per retrieval</dt><dd>1</dd></div>
              <div><dt>Vector candidate retrieval passes</dt><dd>1</dd></div>
              <div><dt>Semantic qualification</dt><dd>Local reuse</dd></div>
            </dl>
            <p>
              Verified on the semantic-only success path by <code>semanticQualificationReusesOneQueryEmbeddingAndOneVectorCandidatePass</code>.
              No second embedding or semantic search is invoked.
            </p>
          </aside>
        </div>
      </section>

      <section className={styles.section} aria-labelledby="local-title">
        <div className={styles.sectionHeading}>
          <div>
            <p className={styles.eyebrow}>B / Local deterministic + stub measurements</p>
            <h2 id="local-title">Commerce authority stays comparatively small.</h2>
          </div>
          <p>PostgreSQL 17 Testcontainers with deterministic merchant and payment adapters; one warm-up journey excluded.</p>
        </div>

        <div className={styles.localSpotlight}>
          <div><span>Local catalogue retrieval p50</span><strong>{formatLatency(localCatalogueRetrieval.p50Millis)}</strong></div>
          <div><span>Local catalogue retrieval p95</span><strong>{formatLatency(localCatalogueRetrieval.p95Millis)}</strong></div>
          <p>n={localCatalogueRetrieval.sampleSize} · {evidence.local.environment.replaceAll("_", " ").toLowerCase()}</p>
        </div>

        <div className={styles.commerceGrid}>
          {commerceStageKeys.map((key) => {
            const stage = localStage(key);
            return (
              <article key={key}>
                <span>{stage.label}</span>
                <dl><div><dt>p50</dt><dd>{formatLatency(stage.p50Millis)}</dd></div><div><dt>p95</dt><dd>{formatLatency(stage.p95Millis)}</dd></div></dl>
                <small>n={stage.sampleSize}</small>
              </article>
            );
          })}
        </div>
        <p className={styles.annotation}>
          The same artifact also measures the local intent stub at {formatLatency(localStage("BUYER_INTENT_COMPILATION").p50Millis)} p50 /
          {" "}{formatLatency(localStage("BUYER_INTENT_COMPILATION").p95Millis)} p95. Gemini and live Razorpay latency are excluded.
        </p>
      </section>

      <section className={`${styles.section} ${styles.concurrencySection}`} aria-labelledby="concurrency-title">
        <div className={styles.sectionHeading}>
          <div>
            <p className={styles.eyebrow}>Concurrent correctness</p>
            <h2 id="concurrency-title">Convergence, not a throughput claim.</h2>
          </div>
          <p>Critical operations were called concurrently at N={evidence.concurrency.callers.join(" and N=")} against PostgreSQL/Testcontainers.</p>
        </div>

        <div className={styles.concurrencyTableWrap}>
          <table>
            <thead><tr><th>Operation</th><th>Callers</th><th>Rows expected / observed</th><th>Provider calls expected / observed</th><th>Result</th></tr></thead>
            <tbody>
              {evidence.concurrency.results.map((result) => (
                <tr key={`${result.operation}-${result.callers}`}>
                  <th>{operationLabel(result.operation)}</th>
                  <td>N={result.callers}</td>
                  <td>{result.expectedPersistedRows} / {result.observedPersistedRows}</td>
                  <td>{result.expectedProviderCalls} / {result.observedProviderCalls}</td>
                  <td><span className={styles.pass}>Pass</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className={styles.concurrencyCallout}>
          <strong>What this measures</strong><span>Correctness and convergence under concurrent callers.</span>
          <strong>What it does not measure</strong><span>Maximum production throughput or production-scale load.</span>
        </div>
      </section>

      <section className={`${styles.section} ${styles.limitations}`} aria-labelledby="limitations-title">
        <div>
          <p className={styles.eyebrow}>Evidence boundaries</p>
          <h2 id="limitations-title">What these numbers do not prove.</h2>
        </div>
        <ul>
          <li>These measurements are not production SLAs.</li>
          <li>Production-scale throughput/load testing has not been performed.</li>
          <li>No maximum throughput is claimed.</li>
          <li>Provider and model revisions, quota, and network conditions can change latency.</li>
          <li>Render cold starts are not represented in local deterministic metrics.</li>
          <li>The provider-backed pipeline stops at pre-cart discovery; it does not execute authorization or payment.</li>
          <li>No deployed smoke latency was measured for this page.</li>
        </ul>
        <p>Generated at <time dateTime={evidence.provider.generatedAtUtc}>{evidence.provider.generatedAtUtc}</time> via <code>{evidence.provider.command}</code>.</p>
      </section>

      <section className={styles.destinations} aria-labelledby="performance-destinations">
        <div><p className={styles.eyebrow}>Continue reviewing</p><h2 id="performance-destinations">Follow the evidence into the system.</h2></div>
        <nav aria-label="Performance destinations">
          <Link href="/architecture"><span>Architecture</span><strong>See the authority boundary →</strong></Link>
          <Link href="/security"><span>Security</span><strong>Inspect adversarial proof →</strong></Link>
          <Link href="/failure-lab"><span>Failure Lab</span><strong>Replay unknown recovery →</strong></Link>
          <Link href="/proof"><span>Proof</span><strong>Open generated evidence →</strong></Link>
        </nav>
      </section>

      <footer className={styles.footer}>
        <div><Image src="/amana/amana-mark.png" alt="" width={30} height={30} /><span>Amana performance evidence</span></div>
        <p>Measured locally and with bounded provider-backed evidence. Not an SLA.</p>
        <Link href="/proof">Inspect proof →</Link>
      </footer>
    </main>
  );
}
