import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import attackReport from "../../../../../proof/results/attacks.json";
import safetyReport from "../../../../../proof/results/latest.json";
import mutationReport from "../../../../../proof/results/mutation.json";
import { buildSecurityHeadline, buildSecurityScenarios } from "@/lib/security/security-evidence";
import { SecurityExperience } from "./security-experience";
import styles from "./security.module.css";

export const metadata: Metadata = {
  title: "Red team security — Amana",
  description: "Trace executed adversarial cases through Amana's deterministic authority boundary.",
};

const scenarios = buildSecurityScenarios(
  attackReport.details,
  safetyReport.cases,
  safetyReport.provenance.command,
);
const headline = buildSecurityHeadline(
  safetyReport,
  attackReport.summary,
  mutationReport.summary,
);

const authorityPath = [
  ["AI output", "Interpretation"],
  ["Untrusted intent", "Typed structure"],
  ["Validation", "Fail closed"],
  ["Grounded product", "Catalogue truth"],
  ["Immutable proposal", "Material hash"],
  ["Authorization", "Actor + expiry"],
  ["Execution gate", "Exactly once"],
  ["Razorpay evidence", "Captured + paid"],
  ["Reconciliation", "Unknown recovery"],
] as const;

const deniedPowers = [
  "Create financial authority",
  "Alter an approved proposal",
  "Declare payment success",
  "Override merchant, account, or currency bindings",
  "Bypass idempotency",
  "Promote UNKNOWN into PASS",
] as const;

export default function SecurityPage() {
  return (
    <main className={styles.page}>
      <nav className={styles.nav} aria-label="Security navigation">
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
        <div className={styles.navLinks}>
          <span><i aria-hidden="true" /> RED TEAM / SECURITY</span>
          <Link href="/architecture">Architecture</Link>
          <Link href="/proof">View proof</Link>
        </div>
      </nav>

      <section className={styles.hero}>
        <div className={styles.heroCopy}>
          <p className={styles.eyebrow}>Red team / security</p>
          <h1>Attack the agent.<br /><em>The authority boundary stays intact.</em></h1>
          <p className={styles.lede}>
            Amana lets AI interpret and plan, but deterministic software owns catalogue truth,
            transaction authority, payment evidence, and recovery.
          </p>
          <a className={styles.heroAction} href="#attack-matrix">Inspect executed attacks <span>↓</span></a>
        </div>

        <aside className={styles.heroPanel} aria-label="Current generated safety evidence">
          <div className={styles.heroPanelTop}>
            <span>Latest deterministic run</span>
            <span className={styles.passStatus}><i aria-hidden="true" /> Pass</span>
          </div>
          <div className={styles.primaryMetric}>
            <strong>{headline.passedCases}<span> / {headline.totalCases}</span></strong>
            <p>deterministic safety cases passed</p>
          </div>
          <div className={styles.metricGrid}>
            <div><strong>{headline.hardSafetyViolations}</strong><span>hard safety violations</span></div>
            <div><strong>{headline.defendedInvariants}</strong><span>invariants defended</span></div>
            <div><strong>{headline.blockedAttacks} / {headline.totalAttacks}</strong><span>catalogued attacks blocked</span></div>
            <div><strong>{headline.killedMutations} / {headline.attemptedMutations}</strong><span>selected mutations killed</span></div>
          </div>
          <p className={styles.runMeta}>
            Generated {safetyReport.provenance.generatedAtUtc.slice(0, 10)} · commit {safetyReport.provenance.commitSha.slice(0, 8)}
          </p>
        </aside>
      </section>

      <section className={styles.attackSection} id="attack-matrix">
        <div className={styles.sectionHeading}>
          <div>
            <p className={styles.eyebrow}>Attack matrix</p>
            <h2>Eight attempts. Four deterministic moves.</h2>
          </div>
          <p>
            Select a case to trace <strong>attack → detection → block → reason</strong>. Replay uses
            recorded generated evidence; the browser does not invoke payment or attack endpoints.
          </p>
        </div>

        <SecurityExperience scenarios={scenarios} />

        <p className={styles.boundaryNote}>
          Proof boundary: the selected cards are in-process boundary tests or offline deterministic
          component proofs. They are not represented as HTTP black-box attacks or live exploits.
        </p>
      </section>

      <section className={styles.authoritySection}>
        <div className={styles.sectionHeading}>
          <div>
            <p className={styles.eyebrow}>Authority model</p>
            <h2>Why the model cannot spend money</h2>
          </div>
          <p>
            The model produces an interpretation. Each later transition requires deterministic,
            grounded evidence before the system can advance.
          </p>
        </div>

        <div className={styles.authorityFlow} aria-label="Authority path from model output to reconciliation">
          {authorityPath.map(([label, note], index) => (
            <div className={styles.authorityNode} key={label}>
              <span>{String(index + 1).padStart(2, "0")}</span>
              <strong>{label}</strong>
              <small>{note}</small>
            </div>
          ))}
        </div>

        <div className={styles.deniedPanel}>
          <div>
            <p className={styles.eyebrow}>Model permissions</p>
            <h3>Interpretation stops before authority.</h3>
          </div>
          <ul>
            {deniedPowers.map((power) => <li key={power}><span aria-hidden="true">×</span>{power}</li>)}
          </ul>
        </div>
      </section>

      <section className={styles.mutationSection}>
        <div className={styles.mutationMetric}>
          <span>Selected negative controls</span>
          <strong>{headline.killedMutations}<i> / {headline.attemptedMutations}</i></strong>
          <p>safety mutations killed</p>
        </div>
        <div className={styles.mutationCopy}>
          <p className={styles.eyebrow}>Tests that fail when guards fail</p>
          <h2>Passing is meaningful only if weakening a guard gets caught.</h2>
          <p>
            Production guards were deliberately weakened in test-only mutants; the safety suite
            detected every selected regression. This is selected negative-control coverage, not
            repository-wide mutation testing.
          </p>
          <Link href="/proof#negative-controls">Inspect negative-control evidence <span>→</span></Link>
        </div>
      </section>

      <footer className={styles.footer}>
        <div>
          <Image src="/amana/amana-mark.png" alt="" width={30} height={30} />
          <span>Amana security evidence</span>
        </div>
        <p>Generated proof replay · no live payment state is touched</p>
        <Link href="/proof">Full evidence hub →</Link>
      </footer>
    </main>
  );
}
