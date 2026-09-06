import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import report from "../../../../../proof/results/latest.json";
import styles from "./proof.module.css";

export const metadata: Metadata = {
  title: "Safety proof — Amana",
  description: "A measured, reproducible evaluation of Amana's deterministic commerce safety boundaries.",
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
  .map((id) => report.cases.find((evaluationCase) => evaluationCase.id === id))
  .filter((evaluationCase) => evaluationCase !== undefined);

function metricValue(key: keyof typeof report.metrics) {
  return report.metrics[key].toLocaleString("en-IN");
}

export default function ProofPage() {
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
        <div className={styles.navMeta}>
          <span className={styles.liveDot} aria-hidden="true" />
          SAFETY PROOF
        </div>
      </nav>

      <section className={styles.hero}>
        <div className={styles.heroCopy}>
          <p className={styles.eyebrow}>Deterministic safety evaluation</p>
          <h1>Safety is measured,<br />not claimed.</h1>
          <p className={styles.lede}>
            AI handles unstructured meaning and bounded action selection. Deterministic software
            controls truth, authority, capability readiness, and money.
          </p>
          <div className={styles.runStamp}>
            <span>Latest reproducible run</span>
            <time dateTime={report.timestamp}>{report.timestamp.replace("T", " ").slice(0, 19)} UTC</time>
          </div>
        </div>

        <div className={styles.scorecard} aria-label="Evaluation headline metrics">
          <div className={styles.totalMetric}>
            <span className={styles.metricLabel}>Deterministic cases</span>
            <strong>{report.totalCases}</strong>
            <span className={styles.passLine}>
              <span className={styles.check} aria-hidden="true">✓</span>
              {report.passed} passed
            </span>
          </div>
          <div className={styles.scoreDivider} />
          <div className={styles.sideMetrics}>
            <div>
              <span className={styles.metricLabel}>Hard safety violations</span>
              <strong>{report.hardSafetyViolations}</strong>
              <span>No unsafe path crossed the boundary</span>
            </div>
            <div>
              <span className={styles.metricLabel}>Fail-closed enforcement</span>
              <strong>{report.failClosedRate}</strong>
              <span>{metricValue("failClosedCorrect")} of {metricValue("failClosedCases")} cases</span>
            </div>
          </div>
        </div>
      </section>

      <section className={styles.categorySection} aria-labelledby="coverage-title">
        <div className={styles.sectionHeading}>
          <div>
            <p className={styles.eyebrow}>Measured coverage</p>
            <h2 id="coverage-title">Eight boundaries. One deterministic verdict.</h2>
          </div>
          <p>Every count below comes from the generated machine-readable report.</p>
        </div>
        <div className={styles.categoryGrid}>
          {Object.entries(report.categoryCounts).map(([category, count], index) => (
            <article className={styles.categoryCard} key={category}>
              <div className={styles.categoryTopline}>
                <span>{String(index + 1).padStart(2, "0")}</span>
                <strong>{count}</strong>
              </div>
              <h3>{category}</h3>
              <p>{categoryNotes[category]}</p>
              <div className={styles.categoryBar} aria-hidden="true">
                <span style={{ width: `${(count / report.totalCases) * 100 * 2.5}%` }} />
              </div>
            </article>
          ))}
        </div>
      </section>

      <section className={styles.matrixSection} aria-labelledby="matrix-title">
        <div className={styles.sectionHeading}>
          <div>
            <p className={styles.eyebrow}>12+ deterministic invariants defended</p>
            <h2 id="matrix-title">The evaluation matrix</h2>
          </div>
          <div className={styles.invariantCount}>
            <strong>{report.defendedInvariantCount}</strong>
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
          {report.invariants.map((invariant) => (
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
            <p className={styles.eyebrow}>Adversarial examples</p>
            <h2 id="examples-title">Failures that stayed failures.</h2>
          </div>
          <p>Selected from the same {report.totalCases}-case report shown above.</p>
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

      <section className={styles.methodology} aria-label="Methodology">
        <div>
          <p className={styles.eyebrow}>Methodology</p>
          <h2>AI does not grade its own safety.</h2>
        </div>
        <ul>
          <li><span aria-hidden="true">01</span> Deterministic cases</li>
          <li><span aria-hidden="true">02</span> Reproducible offline</li>
          <li><span aria-hidden="true">03</span> No production payment mutation</li>
          <li><span aria-hidden="true">04</span> No model-generated verdicts</li>
        </ul>
        <p className={styles.methodNote}>
          Production reducers and guards execute against inert persistence and provider boundaries.
          No Gemini call, Docker service, Razorpay credential, database, or external API is required.
        </p>
      </section>

      <footer className={styles.footer}>
        <span>Amana — Agentic commerce you can trust.</span>
        <span>{report.passed}/{report.totalCases} passed · {report.hardSafetyViolations} hard violations</span>
      </footer>
    </main>
  );
}
