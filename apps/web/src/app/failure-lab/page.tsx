import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import failureReport from "../../../../../proof/results/failure-lab.json";
import { buildFailureLabEvidence, type FailureLabReport } from "@/lib/failure-lab/failure-lab-evidence";
import { FailureLabExperience } from "./failure-lab-experience";
import styles from "./failure-lab.module.css";

export const metadata: Metadata = {
  title: "Unknown order recovery — Amana Failure Lab",
  description: "Executed evidence for Amana's ambiguous Razorpay order-creation recovery invariant.",
};

const evidence = buildFailureLabEvidence(failureReport as FailureLabReport);

export default function FailureLabPage() {
  return (
    <main className={styles.page}>
      <nav className={styles.nav} aria-label="Failure Lab navigation">
        <Link className={styles.brand} href="/" aria-label="Amana home">
          <Image src="/amana/amana-mark.png" alt="" width={40} height={40} priority />
          <span>Amana</span>
        </Link>
        <div className={styles.navLinks}>
          <span><i aria-hidden="true" /> FAILURE INJECTION</span>
          <Link href="/performance">Performance</Link>
          <Link href="/architecture">Architecture</Link>
          <Link href="/security">Security</Link>
          <Link href="/proof">Proof</Link>
        </div>
      </nav>

      <header className={styles.hero}>
        <div>
          <p className={styles.eyebrow}>Failure injection</p>
          <h1>What if Razorpay creates the order, <em>but Amana never receives the response?</em></h1>
        </div>
        <div className={styles.heroSupport}>
          <p>Amana treats the outcome as unknown, reconciles provider state, and refuses to create a second order until truth is known.</p>
          <strong>An unknown create-order outcome must be reconciled before another provider order can be created.</strong>
        </div>
      </header>

      <FailureLabExperience evidence={evidence} />

      <section className={styles.evidenceSection} aria-labelledby="evidence-title">
        <div className={styles.sectionHeading}>
          <p className={styles.eyebrow}>Measured fixture evidence</p>
          <h2 id="evidence-title">Production logic. Inert boundary. Exact result.</h2>
          <p>This is a replay of a generated artifact from the real execution and payment services running against PostgreSQL 17 with an in-process provider adapter.</p>
        </div>

        <div className={styles.evidenceGrid}>
          <article className={styles.identityCard}>
            <span>Stable authority identity</span>
            <h3>Execution and receipt remain fixed</h3>
            <dl>
              <div><dt>Execution ID</dt><dd>{evidence.executionId}</dd></div>
              <div><dt>Execution idempotency key</dt><dd>{evidence.executionIdempotencyKey}</dd></div>
              <div><dt>Stable receipt</dt><dd>{evidence.stableReceipt}</dd></div>
              <div><dt>Recovered provider order</dt><dd>{evidence.providerOrderIdRecovered}</dd></div>
            </dl>
          </article>

          <article className={styles.stateCard}>
            <span>Actual state transitions</span>
            <h3>Order recovery is not payment confirmation</h3>
            <div className={styles.stateFlow}>
              <div><small>Execution</small><strong>{evidence.initialExecutionState}</strong></div>
              <b aria-hidden="true">→</b>
              <div><small>Attempt</small><strong>{evidence.uncertainAttemptOutcome}</strong></div>
              <b aria-hidden="true">→</b>
              <div><small>Final execution</small><strong>{evidence.finalExecutionState}</strong></div>
            </div>
            <p>Final payment state: <strong>{evidence.finalPaymentState}</strong>. Recovered order existence is not captured-payment evidence, so this proof does not claim <code>PAYMENT_CONFIRMED</code>.</p>
          </article>

          <article className={styles.guardCard}>
            <span>Production guard</span>
            <h3>Uncertain means reconcile first</h3>
            <p><code>PaymentControlService.initiate</code> persists the attempt. <code>PaymentControlService.reconcile</code> resolves the stable receipt, while <code>PaymentRepository</code> enforces one provider-order binding per execution.</p>
            <ul>
              {evidence.productionLogic.map((logic) => <li key={logic}>{logic}</li>)}
            </ul>
          </article>
        </div>
      </section>

      <aside className={styles.proofBoundary} aria-label="Failure Lab proof boundary">
        <strong>No real Razorpay call is possible from this page.</strong>
        <p>{evidence.whatThisDoesNotProve} It replays one passing isolated fixture; it does not run when the button is clicked.</p>
        <span>{evidence.testClass}.{evidence.testMethod}</span>
      </aside>

      <footer className={styles.footer}>
        <div><Image src="/amana/amana-mark.png" alt="" width={30} height={30} /><span>Amana Failure Lab</span></div>
        <p>Unknown means reconcile first.</p>
        <Link href="/proof">Inspect full evidence →</Link>
      </footer>
    </main>
  );
}
