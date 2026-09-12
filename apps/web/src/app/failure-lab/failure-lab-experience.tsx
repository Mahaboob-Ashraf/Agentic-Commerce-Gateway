"use client";

import { useEffect, useState } from "react";
import type { FailureLabEvidence } from "@/lib/failure-lab/failure-lab-evidence";
import styles from "./failure-lab.module.css";

type FailureLabExperienceProps = {
  evidence: FailureLabEvidence;
};

const steps = [
  { number: "01", tone: "safe", title: "Authorization already valid", detail: "Immutable proposal and bound approval enter the Execution Gate." },
  { number: "02", tone: "safe", title: "Stable execution reserved", detail: "One execution owns a stable idempotency identity and receipt." },
  { number: "03", tone: "safe", title: "Provider create sent", detail: "The inert provider receives exactly one create-order command." },
  { number: "04", tone: "failure", title: "Response lost", detail: "The provider creates the order, then a TIMEOUT hides the response." },
  { number: "05", tone: "uncertain", title: "PAYMENT_UNCERTAIN", detail: "The creation attempt is persisted as UNCERTAIN—never guessed failed or successful." },
  { number: "06", tone: "guard", title: "Blind retry refused", detail: "The uncertain attempt prevents another provider create call." },
  { number: "07", tone: "reconcile", title: "Reconciliation", detail: "Amana queries the inert provider by the stable execution receipt." },
  { number: "08", tone: "reconcile", title: "Existing order found", detail: "The original provider order is validated and bound to the execution." },
  { number: "09", tone: "recovered", title: "Execution converges", detail: "Execution advances to PAYMENT_PENDING; payment truth remains uncertain." },
  { number: "10", tone: "recovered", title: "No duplicate provider order", detail: "One reservation. One create call. One provider order." },
] as const;

export function FailureLabExperience({ evidence }: FailureLabExperienceProps) {
  const [visibleSteps, setVisibleSteps] = useState<number>(steps.length);

  useEffect(() => {
    if (visibleSteps === 0 || visibleSteps >= steps.length) return;
    const timer = window.setTimeout(() => setVisibleSteps((value) => value + 1), 260);
    return () => window.clearTimeout(timer);
  }, [visibleSteps]);

  const replay = () => {
    setVisibleSteps(window.matchMedia("(prefers-reduced-motion: reduce)").matches ? steps.length : 1);
  };

  return (
    <section className={styles.labPanel} aria-labelledby="trace-title">
      <div className={styles.labHeader}>
        <div>
          <p className={styles.eyebrow}>Executed evidence trace</p>
          <h2 id="trace-title">A side effect happened. The response did not.</h2>
        </div>
        <div className={styles.runControl}>
          <button
            className={styles.replayButton}
            disabled={visibleSteps > 0 && visibleSteps < steps.length}
            onClick={replay}
            type="button"
          >
            <span aria-hidden="true">↻</span> Replay failure trace
          </button>
          <p>Visualization of an executed deterministic Testcontainers proof case. No live provider call is performed.</p>
        </div>
      </div>

      <ol className={styles.timeline} aria-live="polite">
        {steps.map((step, index) => (
          <li key={step.number} data-revealed={visibleSteps > index} data-tone={step.tone}>
            <span className={styles.stepNumber}>{step.number}</span>
            <div><strong>{step.title}</strong><p>{step.detail}</p></div>
          </li>
        ))}
      </ol>

      <div className={styles.resultBand}>
        <div>
          <span>Invariant held</span>
          <strong>{evidence.verdict.replaceAll("_", " ")}</strong>
        </div>
        <dl>
          <div><dt>Execution reservations</dt><dd>{evidence.executionReservations}</dd></div>
          <div><dt>Provider create calls</dt><dd>{evidence.providerCreateCalls}</dd></div>
          <div><dt>Provider orders</dt><dd>{evidence.providerOrdersObserved}</dd></div>
          <div><dt>Recovered by reconciliation</dt><dd>{evidence.recoveredByReconciliation ? "Yes" : "No"}</dd></div>
          <div><dt>Duplicate orders</dt><dd>{evidence.duplicateProviderOrders}</dd></div>
        </dl>
      </div>
    </section>
  );
}
