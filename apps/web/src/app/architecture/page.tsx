import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import styles from "./architecture.module.css";

export const metadata: Metadata = {
  title: "System architecture - Amana",
  description:
    "A reviewer-facing view of Amana's AI reasoning and deterministic commerce authority boundary.",
};

const authorityCapabilities = [
  {
    title: "Catalogue & policy",
    detail: "Product grounding · policy evaluation",
  },
  {
    title: "Proposal & authority",
    detail: "Material hash · actor-bound approval",
  },
  {
    title: "Evidence & readiness",
    detail: "Contract evidence · readiness reducer",
  },
] as const;

const authorityTransitions = [
  {
    title: "TransactionProposal",
    detail: "Immutable material intent",
  },
  {
    title: "AuthorizationDecision",
    detail: "Approval bound to proposal, hash, session, action, actor, and expiry",
  },
  {
    title: "Execution",
    detail: "Deterministic revalidation and stable idempotent execution",
  },
] as const;

const merchantReadiness = [
  "Approved merchant interface",
  "AI-assisted inspection / mapping",
  "Contract tests + evidence",
  "Merchant approval where required",
  "Deterministic readiness reducer",
  "Agent Commerce Manifest",
] as const;

const paymentTruth = [
  "Checkout / callback",
  "Evidence",
  "Webhook / API reconciliation",
  "Deterministic payment reducer",
  "PAYMENT_CONFIRMED",
] as const;

export default function ArchitecturePage() {
  return (
    <main className={styles.page}>
      <nav className={styles.nav} aria-label="Architecture navigation">
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
          <span><i aria-hidden="true" /> SYSTEM ARCHITECTURE</span>
          <Link href="/performance">Performance</Link>
          <Link href="/failure-lab">Failure Lab</Link>
          <Link href="/security">Security</Link>
          <Link href="/proof">Proof</Link>
        </div>
      </nav>

      <header className={styles.hero}>
        <div className={styles.heroCopy}>
          <p className={styles.eyebrow}>System architecture</p>
          <h1>AI can reason about commerce. <em>It cannot authorize commerce.</em></h1>
        </div>
        <div className={styles.heroSupport}>
          <p>
            Amana places a deterministic authority layer between AI reasoning, merchant systems,
            and Razorpay.
          </p>
          <strong>
            AI handles unstructured meaning and planning. Deterministic software controls truth,
            authority, and money.
          </strong>
        </div>
      </header>

      <section className={styles.diagramSection} aria-labelledby="system-map-title">
        <div className={styles.diagramHeading}>
          <div>
            <p className={styles.eyebrow}>30-second system view</p>
            <h2 id="system-map-title">Reasoning above. Authority below.</h2>
          </div>
          <div className={styles.legend} aria-label="Diagram legend">
            <span data-kind="ai">AI / untrusted</span>
            <span data-kind="authority">Deterministic authority</span>
            <span data-kind="external">External authority</span>
          </div>
        </div>

        <figure className={styles.systemMap} aria-describedby="system-map-caption">
          <div className={styles.reasoningZone}>
            <div className={styles.zoneLabel}>AI / UNTRUSTED REASONING</div>
            <article className={styles.geminiNode}>
              <span className={styles.nodeKicker}>Gemini</span>
              <h3>Interpretation + bounded planning</h3>
              <p>Buyer intent · vision · realtime voice / conversation · merchant-interface reasoning</p>
              <small>Produces language, observations, candidate choices, and plans—not authority.</small>
            </article>
          </div>

          <div className={styles.trustBoundary} role="note">
            <span>TRUST BOUNDARY</span>
            <strong>AI output becomes input, never authority</strong>
            <span>UNTRUSTED OUTPUT ↓ VALIDATED APPLICATION INPUT</span>
          </div>

          <div
            aria-label="Gemini feeds bounded untrusted application input into the deterministic commerce control plane"
            className={styles.geminiIngress}
            data-flow-edge="gemini-control-plane"
          >
            <span>BOUNDED UNTRUSTED APPLICATION INPUT</span>
            <i aria-hidden="true" />
          </div>

          <div className={styles.controlGrid}>
            <div className={styles.agentColumn} aria-label="Exactly two P0 runtime commerce agents">
              <p className={styles.columnLabel}>TWO P0 RUNTIME COMMERCE AGENTS</p>
              <article className={styles.agentCard} data-flow-edge="safe-buyer-control-plane">
                <span>AGENT 01</span>
                <h3>Safe AI Buyer</h3>
                <p>Intent → grounded product → proposal → authorization → payment</p>
                <small>Guides the buyer journey; it does not grant authority.</small>
                <b>STRUCTURED INTENT TO CONTROL PLANE</b>
              </article>
              <article className={styles.agentCard} data-flow-edge="merchant-agent-control-plane">
                <span>AGENT 02</span>
                <h3>Merchant Agentization Agent</h3>
                <p>Inspect → map → test → evidence → readiness</p>
                <small>Proposes mappings and repairs; it does not publish readiness.</small>
                <b>BOUNDED PLAN TO CONTROL PLANE</b>
              </article>
            </div>

            <section className={styles.controlPlane} aria-labelledby="control-plane-title">
              <div className={styles.authorityBadge}>DETERMINISTIC AUTHORITY</div>
              <p className={styles.controlEyebrow}>Application code</p>
              <h3 id="control-plane-title">Deterministic Commerce Control Plane</h3>
              <p className={styles.controlIntro}>
                Every material transition is typed, validated, evidence-bound, and fail-closed.
              </p>

              <div className={styles.capabilityGrid}>
                {authorityCapabilities.map((capability) => (
                  <article key={capability.title}>
                    <span aria-hidden="true">✓</span>
                    <h4>{capability.title}</h4>
                    <p>{capability.detail}</p>
                  </article>
                ))}
              </div>

              <div className={styles.transitionRail} aria-label="Transaction authority transitions">
                {authorityTransitions.map((transition, index) => (
                  <div className={styles.transition} key={transition.title}>
                    <span>{String(index + 1).padStart(2, "0")}</span>
                    <strong>{transition.title}</strong>
                  </div>
                ))}
              </div>

            </section>

            <aside className={styles.merchantSystems} data-flow-edge="merchant-systems-control-plane">
              <span className={styles.externalBadge}>EXTERNAL AUTHORITY</span>
              <h3>Merchant Systems</h3>
              <p>Approved interfaces · catalogue · price · stock · serviceability · policy</p>
              <small>Approved merchant interfaces provide authoritative source facts and evidence to deterministic application processing.</small>
              <b>AUTHORITY EVIDENCE TO CONTROL PLANE</b>
            </aside>
          </div>

          <div
            aria-label="The deterministic commerce control plane flows into the Execution Gate"
            className={styles.controlDrop}
            data-flow-edge="control-plane-execution-gate"
          >
            <span>VALIDATED DETERMINISTIC AUTHORITY</span>
            <i aria-hidden="true" />
          </div>

          <div
            className={styles.paymentPath}
            aria-label="Execution Gate flows to Razorpay, provider evidence flows to reconciliation, then verified outcomes flow to fulfilment and refunds"
          >
            <article className={styles.executionGate} data-flow-order="1">
              <span>FINAL REVALIDATION</span>
              <strong>Execution Gate</strong>
              <small>Exactly one execution per proposal · at most one provider order</small>
            </article>
            <div
              aria-label="Execution Gate flows to Razorpay"
              className={styles.pathConnector}
              data-flow-edge="execution-gate-razorpay"
            >
              <span>VALIDATED EXECUTION</span>
              <i aria-hidden="true" />
            </div>
            <article className={styles.razorpayNode} data-flow-order="2">
              <span>PAYMENT PROVIDER</span>
              <h3>Razorpay Test Mode</h3>
              <p>Checkout; returns provider evidence only</p>
            </article>
            <div
              aria-label="Razorpay provider evidence flows to reconciliation"
              className={styles.pathConnector}
              data-flow-edge="razorpay-reconciliation"
            >
              <span>PROVIDER EVIDENCE</span>
              <small>Callback · webhook · API</small>
              <i aria-hidden="true" />
            </div>
            <article className={styles.reconciliationNode} data-flow-order="3">
              <span>DETERMINISTIC REDUCTION</span>
              <h3>Reconciliation</h3>
              <p>Exact merchant/account · order · amount · INR · captured payment · paid order</p>
            </article>
            <div
              aria-label="Reconciliation flows to fulfilment and refunds only after a verified outcome"
              className={styles.pathConnector}
              data-flow-edge="reconciliation-fulfilment-refunds"
            >
              <span>VERIFIED OUTCOME</span>
              <i aria-hidden="true" />
            </div>
            <article className={styles.fulfilmentNode} data-flow-order="4">
              <h3>Fulfilment / Refunds</h3>
              <p>Durable outbox · stable refund idempotency · bounded accounting</p>
            </article>
          </div>

          <div
            aria-label="The control plane, reconciliation, and lifecycle components read and write shared authoritative PostgreSQL state"
            className={styles.substrateLinks}
          >
            <span>Control plane <i aria-hidden="true" /></span>
            <span>Reconciliation <i aria-hidden="true" /></span>
            <span>Lifecycle / outbox <i aria-hidden="true" /></span>
          </div>

          <aside className={styles.postgresNode}>
            <div>
              <span>SYSTEM OF RECORD</span>
              <h3>PostgreSQL 17</h3>
            </div>
            <p>Authoritative shared state · payment evidence · audit truth · transactional outbox</p>
            <strong>Control substrate for every deterministic transition</strong>
          </aside>

          <figcaption id="system-map-caption">
            Gemini supplies bounded untrusted application input across the trust boundary. Both commerce
            agents and approved merchant interfaces feed the same deterministic control plane. Only the
            Execution Gate can advance to Razorpay; PostgreSQL is shared authority, not a payment step.
          </figcaption>
        </figure>
      </section>

      <section className={styles.detailSection} aria-labelledby="authority-detail-title">
        <div className={styles.sectionHeading}>
          <p className={styles.eyebrow}>Authority details</p>
          <h2 id="authority-detail-title">Three transitions reviewers should remember.</h2>
        </div>

        <div className={styles.detailGrid}>
          <article className={styles.flowCard}>
            <span className={styles.cardNumber}>01 / MERCHANT READINESS</span>
            <h3>Suggestions become evidence, then readiness.</h3>
            <ol>
              {merchantReadiness.map((step) => <li key={step}>{step}</li>)}
            </ol>
            <p>The model may suggest a mapping or repair. Only deterministic logic may publish readiness.</p>
          </article>

          <article className={`${styles.flowCard} ${styles.authorityCard}`}>
            <span className={styles.cardNumber}>02 / TRANSACTION AUTHORITY</span>
            <h3>Intent becomes narrow, explicit authority.</h3>
            <ol>
              {authorityTransitions.map((transition) => (
                <li key={transition.title}>
                  <strong>{transition.title}</strong>
                  <span>{transition.detail}</span>
                </li>
              ))}
            </ol>
          </article>

          <article className={styles.flowCard}>
            <span className={styles.cardNumber}>03 / PAYMENT TRUTH</span>
            <h3>Provider evidence becomes a verified outcome.</h3>
            <ol>
              {paymentTruth.map((step) => <li key={step}>{step}</li>)}
            </ol>
            <p><strong>Browser callbacks are evidence, not payment truth.</strong></p>
          </article>
        </div>
      </section>

      <section className={styles.explanationSection} aria-labelledby="principles-title">
        <div className={styles.sectionHeading}>
          <p className={styles.eyebrow}>Operating principle</p>
          <h2 id="principles-title">Useful intelligence. Narrow authority.</h2>
        </div>
        <div className={styles.explanationGrid}>
          <article>
            <span>01</span>
            <h3>AI is useful, but untrusted</h3>
            <p>Model outputs become structured hypotheses, intent, observations, or bounded plans. They are validated before use.</p>
          </article>
          <article>
            <span>02</span>
            <h3>Authority is deterministic</h3>
            <p>Application code owns grounding, readiness, policy, proposal hashing, authorization, and execution.</p>
          </article>
          <article>
            <span>03</span>
            <h3>Payment truth comes from evidence</h3>
            <p>Callback, webhook, and provider API evidence is reduced against exact bindings before confirmation.</p>
          </article>
        </div>
      </section>

      <section className={styles.destinations} aria-labelledby="destinations-title">
        <div>
          <p className={styles.eyebrow}>Explore the system</p>
          <h2 id="destinations-title">Move from the map to the working surfaces.</h2>
        </div>
        <nav aria-label="Architecture destinations">
          <Link href="/failure-lab"><span>Failure Lab</span><strong>Replay unknown-order recovery →</strong></Link>
          <Link href="/security"><span>Security replay</span><strong>See the boundary attacked →</strong></Link>
          <Link href="/proof"><span>Evidence hub</span><strong>Inspect measured proof →</strong></Link>
          <Link href="/buyer/chat"><span>Safe AI Buyer</span><strong>Open buyer workspace →</strong></Link>
          <Link href="/merchant"><span>Merchant agentization</span><strong>Open merchant workspace →</strong></Link>
        </nav>
      </section>

      <footer className={styles.footer}>
        <div>
          <Image src="/amana/amana-mark.png" alt="" width={30} height={30} />
          <span>Amana system architecture</span>
        </div>
        <p>AI interprets and plans. Deterministic software owns authority.</p>
        <Link href="/proof">View evidence →</Link>
      </footer>
    </main>
  );
}
