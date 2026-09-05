"use client";

import {
  ActivityIcon,
  CheckCircleIcon,
  ClockIcon,
  CodeSnippetIcon,
  FileTextIcon,
  ListIcon,
  RefreshIcon,
  ShieldIcon,
  TextInput,
} from "@razorpay/blade/components";
import Image from "next/image";
import { useEffect, useMemo, useState, type ReactNode } from "react";
import { AmanaButton } from "@/components/amana/blade";
import { loadOperationalSnapshot, loadWorkbenchRun } from "@/lib/merchant/api";
import type {
  AgentCommerceManifest,
  ManifestCapability,
  WorkbenchRunData,
} from "@/lib/merchant/types";
import {
  addReplayClarification,
  approveMoneyRepair,
  createMoneyRepairReplay,
  publishDeterministicReplayReadiness,
  rejectMoneyRepair,
  runDeterministicRetest,
  type MoneyRepairReplay,
  type ReplayStage,
  type WorkbenchCapabilityState,
} from "@/lib/merchant/workbench-replay";
import { manifestCapabilityState } from "@/lib/merchant/workbench-state";
import { useMerchantSession } from "./merchant-session";
import styles from "./merchant-workbench.module.css";

const canonicalCapabilities = [
  "SEARCH_PRODUCTS",
  "GET_AVAILABILITY",
  "GET_QUOTE",
  "PLACE_ORDER",
  "GET_ORDER_STATE",
  "CANCEL_ORDER",
  "RETURN_ITEM",
  "REFUND",
] as const;

const lifecycle: ReplayStage[] = [
  "INSPECT",
  "DISCOVER",
  "MAP",
  "TEST",
  "OBSERVE",
  "DIAGNOSE",
  "REPAIR",
  "RETEST",
  "REDUCE",
];

const labels: Record<string, string> = {
  SEARCH_PRODUCTS: "Catalogue search",
  GET_AVAILABILITY: "Inventory & availability",
  GET_QUOTE: "Quote",
  PLACE_ORDER: "Place order",
  GET_ORDER_STATE: "Order status",
  CANCEL_ORDER: "Cancel order",
  RETURN_ITEM: "Return item",
  REFUND: "Refund",
};

function compactId(value: string | null | undefined) {
  if (!value) return "Not recorded";
  return value.length > 18 ? `${value.slice(0, 8)}…${value.slice(-6)}` : value;
}

function formatTime(value: string | null | undefined) {
  if (!value) return "Not recorded";
  const parsed = new Date(value);
  return Number.isNaN(parsed.valueOf()) ? value : parsed.toLocaleString();
}

function StatusPill({ state }: { state: WorkbenchCapabilityState }) {
  return <span className={styles.statusPill} data-state={state}>{state.replaceAll("_", " ")}</span>;
}

function PaneHeading({ eyebrow, title, trailing }: { eyebrow: string; title: string; trailing?: ReactNode }) {
  return (
    <header className={styles.paneHeading}>
      <div><p>{eyebrow}</p><h2>{title}</h2></div>
      {trailing}
    </header>
  );
}

export function MerchantWorkbench() {
  const { actor, merchantError, merchantLoading, selectedMerchant } = useMerchantSession();
  const [selectedCapability, setSelectedCapability] = useState<string>("GET_QUOTE");
  const [manifest, setManifest] = useState<AgentCommerceManifest | null>(null);
  const [liveData, setLiveData] = useState<WorkbenchRunData | null>(null);
  const [loadedMerchantId, setLoadedMerchantId] = useState<string | null>(null);
  const [liveError, setLiveError] = useState("");
  const [replay, setReplay] = useState<MoneyRepairReplay>(() =>
    createMoneyRepairReplay(actor?.actorId ?? "unavailable"),
  );

  useEffect(() => {
    if (!selectedMerchant) return;
    let active = true;
    loadOperationalSnapshot(selectedMerchant.merchantId).then(async (snapshot) => {
      if (!active) return;
      setManifest(snapshot.manifest);
      setLiveError("");
      if (!snapshot.manifest) {
        setLiveData(null);
        return;
      }
      const data = await loadWorkbenchRun(selectedMerchant.merchantId, snapshot.manifest.runId);
      if (active) setLiveData(data);
    }).catch((error) => {
      if (active) setLiveError(error instanceof Error ? error.message : "Workbench evidence is unavailable");
    }).finally(() => {
      if (active) setLoadedMerchantId(selectedMerchant.merchantId);
    });
    return () => { active = false; };
  }, [selectedMerchant]);

  useEffect(() => {
    if (replay.phase !== "RETESTING") return;
    const timer = window.setTimeout(() => setReplay((current) => runDeterministicRetest(current)), 700);
    return () => window.clearTimeout(timer);
  }, [replay.phase]);

  useEffect(() => {
    if (replay.phase !== "REDUCING") return;
    const timer = window.setTimeout(
      () => setReplay((current) => publishDeterministicReplayReadiness(current)),
      700,
    );
    return () => window.clearTimeout(timer);
  }, [replay.phase]);

  const liveCapabilities = useMemo(
    () => new Map((manifest?.capabilities ?? []).map((item) => [item.capability, item])),
    [manifest],
  );
  const selectedLive = liveCapabilities.get(selectedCapability);
  const liveLoading = Boolean(selectedMerchant) && loadedMerchantId !== selectedMerchant?.merchantId;
  const hasAmazingLogo = Boolean(
    selectedMerchant
    && (selectedMerchant.merchantKey.toLowerCase().includes("amazing")
      || selectedMerchant.displayName.trim().toLowerCase() === "amazing"),
  );

  return (
    <div className={styles.workbenchPage}>
      <header className={styles.workbenchHeader}>
        <div>
          <p className={styles.eyebrow}>Agentization Workbench</p>
          <h1>Turn approved sources into verified capabilities.</h1>
          <p>Inspect · Map · Test · Repair · Reduce</p>
        </div>
        <div className={styles.headerContext}>
          {hasAmazingLogo
            ? <span className={styles.headerMerchantLogo}><Image alt="Amazing" height={30} priority src="/amana/merchant/amazing.png" width={88} /></span>
            : <span className={styles.liveDot} data-live={Boolean(manifest)} />}
          <div>
            <strong>{selectedMerchant?.displayName ?? "No merchant workspace"}</strong>
            <small>{merchantLoading ? "Resolving membership…" : manifest ? `Live manifest v${manifest.manifestVersion}` : "No published manifest"}</small>
          </div>
        </div>
      </header>

      {(merchantError || liveError) && (
        <p className={styles.errorStrip} role="status">{merchantError || liveError}. Unavailable state is not inferred.</p>
      )}

      <div className={styles.workbenchGrid}>
        <CapabilityRail
          liveCapabilities={liveCapabilities}
          replay={replay}
          selectedCapability={selectedCapability}
          onSelect={setSelectedCapability}
        />
        {selectedCapability === "GET_QUOTE" ? (
          <>
            <ReplayActivity replay={replay} />
            <ReplayEvidence
              actorId={actor?.actorId ?? "unavailable"}
              replay={replay}
              reset={() => setReplay(createMoneyRepairReplay(actor?.actorId ?? "unavailable"))}
              setReplay={setReplay}
            />
          </>
        ) : (
          <>
            <LiveActivity capability={selectedCapability} data={liveData} loading={liveLoading} />
            <LiveEvidence capability={selectedCapability} data={liveData} manifestCapability={selectedLive} />
          </>
        )}
      </div>
    </div>
  );
}

function CapabilityRail({
  liveCapabilities,
  onSelect,
  replay,
  selectedCapability,
}: {
  liveCapabilities: Map<string, ManifestCapability>;
  onSelect: (capability: string) => void;
  replay: MoneyRepairReplay;
  selectedCapability: string;
}) {
  return (
    <aside className={styles.capabilityPane} aria-label="Capabilities" data-tour="capability-rail">
      <PaneHeading eyebrow="Capability rail" title="Commerce surface" trailing={<span className={styles.count}>{canonicalCapabilities.length}</span>} />
      <div className={styles.capabilityList}>
        {canonicalCapabilities.map((capability) => {
          const selected = capability === selectedCapability;
          const state = capability === "GET_QUOTE"
            ? replay.capabilityState
            : manifestCapabilityState(liveCapabilities.get(capability));
          return (
            <button
              aria-pressed={selected}
              className={styles.capabilityButton}
              data-selected={selected || undefined}
              key={capability}
              onClick={() => onSelect(capability)}
              type="button"
            >
              <span className={styles.capabilityMarker} data-state={state} />
              <span className={styles.capabilityName}>
                <strong>{labels[capability]}</strong>
                <small>{capability === "GET_QUOTE"
                  ? `Isolated replay · Live ${liveCapabilities.get(capability)?.readiness ?? "not published"}`
                  : capability}</small>
              </span>
              <StatusPill state={state} />
            </button>
          );
        })}
      </div>
      <div className={styles.sourceBoundary}>
        <ShieldIcon size="small" />
        <p><strong>Approved sources only</strong><span>No browser-side endpoint calls. Live records remain tenant-scoped.</span></p>
      </div>
    </aside>
  );
}

function ReplayActivity({ replay }: { replay: MoneyRepairReplay }) {
  return (
    <section className={styles.activityPane} aria-labelledby="activity-title" data-tour="activity-timeline">
      <PaneHeading
        eyebrow="Deterministic replay"
        title="Quote repair activity"
        trailing={<StatusPill state={replay.capabilityState} />}
      />
      <div className={styles.replayNotice}>
        <RefreshIcon size="small" />
        <p><strong>Recorded sandbox scenario</strong><span>Reproducible fixture · no live endpoint or Amazing merchant mutation</span></p>
      </div>
      <ol className={styles.lifecycleRail} aria-label="Agentization lifecycle">
        {lifecycle.map((stage) => {
          const currentIndex = lifecycle.indexOf(replay.currentStage);
          const stageIndex = lifecycle.indexOf(stage);
          const complete = stageIndex < currentIndex || replay.phase === "COMPLETE";
          return (
            <li data-active={stage === replay.currentStage || undefined} data-complete={complete || undefined} key={stage}>
              <span>{stage === "REDUCE" ? "Reducer" : stage[0] + stage.slice(1).toLowerCase()}</span>
            </li>
          );
        })}
      </ol>
      <div className={styles.activityToolbar}>
        <div><ActivityIcon size="small" /><strong>Run replay_money_quote_001</strong></div>
        <span>{replay.timeline.length} events</span>
      </div>
      <ol className={styles.timeline} aria-live="polite">
        {replay.timeline.map((entry) => (
          <li data-outcome={entry.outcome} data-stage={entry.stage} key={entry.id}>
            <time>{entry.offset}</time>
            <span className={styles.eventMarker} />
            <div>
              <p><span>{entry.stage}</span><strong>{entry.title}</strong></p>
              <small>{entry.detail}</small>
            </div>
          </li>
        ))}
        {(replay.phase === "RETESTING" || replay.phase === "REDUCING") && (
          <li className={styles.pendingEvent}>
            <time>{replay.phase === "RETESTING" ? "T+00:14" : "T+00:16"}</time>
            <span className={styles.eventMarker} />
            <div><p><span>{replay.phase === "RETESTING" ? "RETEST" : "REDUCE"}</span><strong>{replay.phase === "RETESTING" ? "Running bounded contract test" : "Evaluating seven evidence gates"}</strong></p><small>Deterministic operation in progress…</small></div>
          </li>
        )}
      </ol>
    </section>
  );
}

function ReplayEvidence({
  actorId,
  replay,
  reset,
  setReplay,
}: {
  actorId: string;
  replay: MoneyRepairReplay;
  reset: () => void;
  setReplay: (updater: (current: MoneyRepairReplay) => MoneyRepairReplay) => void;
}) {
  const [clarification, setClarification] = useState("");
  const waiting = replay.phase === "AWAITING_APPROVAL";
  return (
    <aside className={styles.evidencePane} aria-labelledby="evidence-title" data-tour="evidence-authority">
      <PaneHeading
        eyebrow="Evidence & authority"
        title={replay.readinessDecision === "READY" ? "Quote readiness evidence" : "Why Quote is blocked"}
        trailing={<ShieldIcon size="small" />}
      />

      <section className={styles.decisionCard} data-state={replay.readinessDecision}>
        <div><span className={styles.decisionIcon}>{replay.readinessDecision === "READY" ? <CheckCircleIcon size="small" /> : <ShieldIcon size="small" />}</span><p><small>Deterministic readiness decision</small><strong>{replay.readinessDecision === "NOT_EVALUATED" ? "Not evaluated" : replay.readinessDecision}</strong></p></div>
        <p>{replay.readinessDecision === "READY" ? "All replay evidence gates passed. This does not alter the authoritative live manifest." : replay.phase === "REDUCING" ? "Reducer is checking the complete evidence set." : "An agent proposal is never sufficient to publish READY."}</p>
      </section>

      {!waiting && (
        <section className={styles.postDecision} aria-live="polite">
          <div><ClockIcon size="small" /><p><strong>{replay.phase === "REJECTED" ? "Repair rejected" : replay.phase === "COMPLETE" ? "Replay complete" : "Approval recorded"}</strong><span>Actor {compactId(replay.decisionActorId)}</span></p></div>
          {replay.retestOutcome && (
            <p className={styles.resultComparison}>
              <span className={styles.beforeResult}>Before <b>FAIL</b></span>
              <span className={styles.afterResult}>After <b>PASS</b></span>
              {replay.phase === "COMPLETE" && <span className={styles.reducerResult}>Reducer <b>{replay.readinessDecision}</b></span>}
            </p>
          )}
          <button onClick={reset} type="button">Reset isolated replay</button>
        </section>
      )}

      <section className={styles.evidenceBlock}>
        <header><ListIcon size="small" /><h3>Failure evidence</h3><span>FAIL</span></header>
        <dl className={styles.evidenceGrid}>
          <div><dt>Endpoint</dt><dd>POST /quotes</dd></div>
          <div><dt>Observed</dt><dd className={styles.failureValue}>2999</dd></div>
          <div><dt>Business meaning</dt><dd className={styles.moneyValue}>₹2,999</dd></div>
          <div><dt>Expected</dt><dd className={styles.expectedValue}>299900 minor units</dd></div>
          <div><dt>Test ID</dt><dd>test_quote_money_001</dd></div>
          <div><dt>Mapping</dt><dd>v{replay.mappingVersion}</dd></div>
        </dl>
        <details className={styles.details}>
          <summary>Evidence lineage</summary>
          <dl>
            <div><dt>Source</dt><dd>Recorded approved-source fixture</dd></div>
            <div><dt>Capability</dt><dd>GET_QUOTE</dd></div>
            <div><dt>Observation</dt><dd>obs_quote_money_001</dd></div>
            <div><dt>Repair version</dt><dd>{replay.repairVersion ? `v${replay.repairVersion}` : "Not applied"}</dd></div>
            <div><dt>Replay time</dt><dd>T+00:05</dd></div>
          </dl>
        </details>
      </section>

      <section className={styles.proposalBlock}>
        <header><CodeSnippetIcon size="small" /><div><p>Agent proposal</p><h3>Normalize rupees → minor units</h3></div></header>
        <code>amount_minor = amount_rupees × 100</code>
        <dl>
          <div><dt>Scope</dt><dd>Quote capability only</dd></div>
          <div><dt>Impact</dt><dd>2999 → 299900 paise</dd></div>
          <div><dt>Risk</dt><dd>Money-affecting semantic mapping</dd></div>
          <div><dt>Authority</dt><dd>Merchant approval required</dd></div>
        </dl>
      </section>

      {waiting && (
        <section className={styles.approvalBlock} aria-label="Merchant approval">
          <p><strong>Merchant decision required</strong><span>Does amount represent rupees, and may Amana normalize this field ×100?</span></p>
          <div className={styles.approvalActions}>
            <AmanaButton onClick={() => setReplay((current) => rejectMoneyRepair(current, actorId))} variant="secondary">Reject</AmanaButton>
            <AmanaButton onClick={() => setReplay((current) => approveMoneyRepair(current, actorId))}>Approve repair</AmanaButton>
          </div>
          <div className={styles.clarificationInput}>
            <TextInput label="Provide clarification" onChange={({ value }) => setClarification(value ?? "")} placeholder="For example: amount is authoritative in whole rupees" value={clarification} />
            <AmanaButton
              isDisabled={!clarification.trim()}
              onClick={() => {
                setReplay((current) => addReplayClarification(current, actorId, clarification));
                setClarification("");
              }}
              variant="tertiary"
            >Record clarification</AmanaButton>
          </div>
        </section>
      )}

      <p className={styles.manifestBoundary}><ShieldIcon size="small" /> Live manifest unchanged · replay output is not authoritative</p>
    </aside>
  );
}

function LiveActivity({ capability, data, loading }: { capability: string; data: WorkbenchRunData | null; loading: boolean }) {
  const observations = data?.observations.filter((item) => item.capability === capability) ?? [];
  return (
    <section className={styles.activityPane} aria-labelledby="activity-title" data-tour="activity-timeline">
      <PaneHeading eyebrow="Authoritative run" title={`${labels[capability]} activity`} trailing={loading ? <ClockIcon size="small" /> : undefined} />
      <div className={styles.liveRunStrip}>
        <ActivityIcon size="small" />
        <p><strong>{data ? `Run ${compactId(data.run.runId)}` : "No recorded run"}</strong><span>{data ? data.run.state.replaceAll("_", " ") : "No agent activity is fabricated when a run is unavailable."}</span></p>
      </div>
      {observations.length ? (
        <ol className={styles.timeline}>
          {observations.map((entry) => (
            <li data-outcome={entry.outcome === "FAIL" ? "FAIL" : "INFO"} key={entry.observationId}>
              <time>{formatTime(entry.createdAt)}</time><span className={styles.eventMarker} />
              <div><p><span>{entry.toolName}</span><strong>{entry.conciseRationale}</strong></p><small>{entry.reasonCode ?? entry.orchestrationState.replaceAll("_", " ")}</small></div>
            </li>
          ))}
        </ol>
      ) : (
        <EmptyRecord icon={<ActivityIcon size="medium" />} title={loading ? "Reading run evidence" : "No activity for this capability"} detail="The center pane will only show durable backend observations." />
      )}
    </section>
  );
}

function LiveEvidence({ capability, data, manifestCapability }: { capability: string; data: WorkbenchRunData | null; manifestCapability?: ManifestCapability }) {
  const mapping = data?.mappings.filter((item) => item.capability === capability).at(-1);
  const test = data?.tests.filter((item) => item.capability === capability).at(-1);
  const readiness = data?.readiness.filter((item) => item.capability === capability).at(-1);
  const openClarifications = data?.clarifications.filter((item) => item.capability === capability && item.status === "OPEN") ?? [];
  return (
    <aside className={styles.evidencePane} aria-labelledby="evidence-title" data-tour="evidence-authority">
      <PaneHeading eyebrow="Evidence & authority" title="Authoritative inspector" trailing={<ShieldIcon size="small" />} />
      <section className={styles.decisionCard} data-state={manifestCapability?.readiness ?? "NOT_EVALUATED"}>
        <div><span className={styles.decisionIcon}><ShieldIcon size="small" /></span><p><small>Latest published readiness</small><strong>{manifestCapability?.readiness ?? "Not published"}</strong></p></div>
        <p>{manifestCapability ? `Manifest advertises this capability: ${manifestCapability.advertised ? "yes" : "no"}.` : "No reducer-owned publication exists for this capability."}</p>
      </section>
      {mapping || test || readiness ? (
        <section className={styles.evidenceBlock}>
          <header><FileTextIcon size="small" /><h3>Recorded evidence</h3><span>{test?.outcome ?? "OPEN"}</span></header>
          <dl className={styles.evidenceGrid}>
            <div><dt>Endpoint</dt><dd>{mapping ? `${mapping.httpMethod} ${mapping.pathTemplate}` : "Not recorded"}</dd></div>
            <div><dt>Mapping</dt><dd>{mapping ? `v${mapping.mappingVersion} · ${mapping.validationStatus}` : "Not recorded"}</dd></div>
            <div><dt>Contract test</dt><dd>{test?.testName ?? "Not recorded"}</dd></div>
            <div><dt>Result</dt><dd>{test?.outcome ?? "Not recorded"}</dd></div>
            <div><dt>Evaluation</dt><dd>{compactId(readiness?.readinessEvaluationId)}</dd></div>
            <div><dt>Open clarifications</dt><dd>{openClarifications.length}</dd></div>
          </dl>
          <details className={styles.details}><summary>Evidence lineage</summary><dl><div><dt>Mapping ID</dt><dd>{compactId(mapping?.mappingProposalId)}</dd></div><div><dt>Test ID</dt><dd>{compactId(test?.contractTestRunId)}</dd></div><div><dt>Evidence hash</dt><dd>{compactId(test?.evidenceHash)}</dd></div><div><dt>Reducer hash</dt><dd>{compactId(readiness?.evaluationHash)}</dd></div><div><dt>Evaluated</dt><dd>{formatTime(readiness?.evaluatedAt)}</dd></div></dl></details>
        </section>
      ) : (
        <EmptyRecord icon={<ListIcon size="medium" />} title="No evidence recorded" detail="No mapping, contract test, or readiness evaluation exists for this capability in the latest manifest run." />
      )}
    </aside>
  );
}

function EmptyRecord({ icon, title, detail }: { icon: ReactNode; title: string; detail: string }) {
  return <div className={styles.emptyRecord}>{icon}<p><strong>{title}</strong><span>{detail}</span></p></div>;
}
