"use client";

import { useEffect, useState } from "react";
import type { SecurityScenario } from "@/lib/security/security-evidence";
import styles from "./security.module.css";

type SecurityExperienceProps = {
  scenarios: SecurityScenario[];
};

const replaySteps = ["Attack", "Detected", "Blocked", "Reason"] as const;

export function SecurityExperience({ scenarios }: SecurityExperienceProps) {
  const [selectedKey, setSelectedKey] = useState(scenarios[0]?.key ?? "");
  const [replayStage, setReplayStage] = useState(4);
  const selected = scenarios.find((scenario) => scenario.key === selectedKey) ?? scenarios[0];

  useEffect(() => {
    if (replayStage < 1 || replayStage >= replaySteps.length) return;

    const timer = window.setTimeout(() => {
      setReplayStage((stage) => Math.min(stage + 1, replaySteps.length));
    }, 420);

    return () => window.clearTimeout(timer);
  }, [replayStage]);

  if (!selected) return null;

  const selectScenario = (key: string) => {
    setSelectedKey(key);
    setReplayStage(replaySteps.length);
  };

  const replayEvidence = () => {
    setReplayStage(1);
  };

  return (
    <div className={styles.experienceGrid}>
      <div className={styles.attackList} aria-label="Executed attack scenarios">
        {scenarios.map((scenario) => (
          <button
            aria-controls="attack-detail"
            aria-pressed={selected.key === scenario.key}
            className={styles.attackSelector}
            data-active={selected.key === scenario.key}
            key={scenario.key}
            onClick={() => selectScenario(scenario.key)}
            type="button"
          >
            <span className={styles.attackNumber}>{String(scenario.order).padStart(2, "0")}</span>
            <span className={styles.attackSelectorCopy}>
              <strong>{scenario.title}</strong>
              <small>{scenario.boundary}</small>
            </span>
            <span className={styles.blockedPill}>Blocked</span>
          </button>
        ))}
      </div>

      <article
        aria-live="polite"
        className={styles.attackDetail}
        id="attack-detail"
        key={selected.key}
        tabIndex={-1}
      >
        <div className={styles.detailHeader}>
          <div>
            <p className={styles.detailEyebrow}>Executed attack · {selected.proofCaseId}</p>
            <h3>{selected.title}</h3>
          </div>
          <span className={styles.largeBlocked}>Blocked</span>
        </div>

        <div className={styles.replayTrack} aria-label="Attack proof sequence">
          <div className={styles.replayLine} aria-hidden="true" />
          <div className={styles.replayStep} data-revealed={replayStage >= 1}>
            <span>01</span>
            <p>Attack</p>
            <strong>{selected.attackInput}</strong>
          </div>
          <div className={styles.replayStep} data-revealed={replayStage >= 2}>
            <span>02</span>
            <p>Detected</p>
            <strong>{selected.reasonCode}</strong>
          </div>
          <div className={styles.replayStep} data-revealed={replayStage >= 3}>
            <span>03</span>
            <p>Blocked</p>
            <strong>{selected.observed}</strong>
          </div>
          <div className={styles.replayStep} data-revealed={replayStage >= 4}>
            <span>04</span>
            <p>Reason</p>
            <strong>{selected.reason}</strong>
          </div>
        </div>

        <div className={styles.replayActions}>
          <button
            className={styles.replayButton}
            disabled={replayStage > 0 && replayStage < replaySteps.length}
            onClick={replayEvidence}
            type="button"
          >
            <span aria-hidden="true">↻</span>
            Replay evidence trace
          </button>
          <p className={styles.replayDisclosure}>
            Visualization of an executed deterministic proof case. No live attack is performed.
          </p>
        </div>

        <dl className={styles.engineeringDetails}>
          <div>
            <dt>Boundary</dt>
            <dd>{selected.boundary}</dd>
          </div>
          <div>
            <dt>Production guard</dt>
            <dd>{selected.guard}</dd>
          </div>
          <div>
            <dt>Expected verdict</dt>
            <dd>{selected.expected}</dd>
          </div>
          <div>
            <dt>Observed verdict</dt>
            <dd>{selected.observed}</dd>
          </div>
          <div>
            <dt>Proof boundary</dt>
            <dd>{selected.proofBoundary}</dd>
          </div>
          <div>
            <dt>Evidence source</dt>
            <dd>{selected.proofArtifact} · {selected.evidenceTest}</dd>
          </div>
        </dl>
      </article>
    </div>
  );
}
