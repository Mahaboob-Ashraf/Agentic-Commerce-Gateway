"use client";

import {
  ActivityIcon,
  ArrowRightIcon,
  CheckCircleIcon,
  ClockIcon,
  CodeSnippetIcon,
  FileTextIcon,
  ListIcon,
  PackageIcon,
  RefreshIcon,
  ShieldIcon,
  StorefrontIcon,
  TestIcon,
} from "@razorpay/blade/components";
import Link from "next/link";
import { useEffect, useMemo, useState, type ReactNode } from "react";
import { AmanaButton } from "@/components/amana/blade";
import { loadOperationalSnapshot } from "@/lib/merchant/api";
import { loadMerchantSetup } from "@/lib/merchant/setup-store";
import type {
  CapabilityReadiness,
  MerchantOperationalSnapshot,
  MerchantSetup,
} from "@/lib/merchant/types";
import { useMerchantSession } from "./merchant-session";
import { useMerchantTour } from "./merchant-tour";
import styles from "./merchant-pages.module.css";

const emptySnapshot: MerchantOperationalSnapshot = {
  catalogue: null,
  manifest: null,
  policies: [],
  errors: [],
};

type MerchantViewState = {
  setup: MerchantSetup | null;
  snapshot: MerchantOperationalSnapshot;
  loading: boolean;
};

function useMerchantViewState(): MerchantViewState {
  const { actor, selectedMerchant } = useMerchantSession();
  const [state, setState] = useState<MerchantViewState>(() => {
    const storedSetup = actor ? loadMerchantSetup(actor.actorId) : null;
    const setup = storedSetup && selectedMerchant
      ? { ...storedSetup, merchantId: selectedMerchant.merchantId }
      : storedSetup;
    return { setup, snapshot: emptySnapshot, loading: Boolean(selectedMerchant) };
  });

  useEffect(() => {
    const storedSetup = actor ? loadMerchantSetup(actor.actorId) : null;
    const setup = storedSetup && selectedMerchant
      ? { ...storedSetup, merchantId: selectedMerchant.merchantId }
      : storedSetup;
    if (!selectedMerchant) return;
    let live = true;
    loadOperationalSnapshot(selectedMerchant.merchantId).then((snapshot) => {
      if (live) setState({ setup, snapshot, loading: false });
    });
    return () => { live = false; };
  }, [actor, selectedMerchant]);

  return state;
}

function PageHeader({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow: string;
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <header className={styles.pageHeader}>
      <div>
        <p className={styles.eyebrow}>{eyebrow}</p>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      {action}
    </header>
  );
}

function ActionLink({ children, href }: { children: ReactNode; href: string }) {
  return <Link className={styles.primaryAction} href={href}>{children}<ArrowRightIcon size="small" /></Link>;
}

type PageBandMetric = { label: string; value: string };

function PageBand({
  actionHref,
  actionLabel,
  description,
  kicker,
  metrics,
  title,
  value,
}: {
  actionHref: string;
  actionLabel: string;
  description: string;
  kicker: string;
  metrics: PageBandMetric[];
  title: string;
  value: string;
}) {
  return (
    <section className={styles.pageBand} aria-labelledby="merchant-page-band-title">
      <div className={styles.pageBandLead}>
        <p>{kicker}</p>
        <h1 id="merchant-page-band-title">{title}</h1>
        <strong>{value}</strong>
        <span>{description}</span>
      </div>
      <div className={styles.pageBandAside}>
        <dl>
          {metrics.map((metric) => (
            <div key={metric.label}><dt>{metric.label}</dt><dd>{metric.value}</dd></div>
          ))}
        </dl>
        <Link className={styles.pageBandAction} href={actionHref}>{actionLabel}<ArrowRightIcon size="small" /></Link>
      </div>
    </section>
  );
}

function statusTone(status: CapabilityReadiness | "NOT_STARTED" | "NEEDS_ATTENTION") {
  if (status === "READY") return "ready";
  if (status === "BLOCKED" || status === "NEEDS_ATTENTION") return "attention";
  return "neutral";
}

function titleCase(value: string) {
  return value.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase());
}

export function MerchantOverview() {
  const { selectedMerchant } = useMerchantSession();
  const { setup, snapshot, loading } = useMerchantViewState();
  const capabilities = snapshot.manifest?.capabilities ?? [];
  const readyCount = capabilities.filter((item) => item.readiness === "READY" && item.advertised).length;
  const blockedCount = capabilities.filter((item) => item.readiness === "BLOCKED").length;
  const incompleteCount = capabilities.filter((item) => item.readiness !== "READY" || !item.advertised).length;
  const overallStatus: CapabilityReadiness | "NOT_STARTED" | "NEEDS_ATTENTION" = snapshot.manifest
    ? capabilities.length === 0 ? "NOT_STARTED" : incompleteCount > 0 ? "NEEDS_ATTENTION" : "READY"
    : "NOT_STARTED";
  const hasDraft = Boolean(setup?.store.name || Object.values(setup?.sources ?? {}).some(Boolean));
  const actionHref = hasDraft ? "/merchant/agentization" : "/merchant/onboarding";
  const actionLabel = hasDraft ? "Open agentization" : "Set up approved sources";

  return (
    <div className={styles.page}>
      <PageHeader
        eyebrow="Merchant overview"
        title={selectedMerchant?.displayName ?? (setup?.store.name || "Prepare your merchant workspace")}
        description="Give Amana approved commerce sources. It inspects, maps, tests and prepares your store for safe agentic commerce."
        action={<ActionLink href={actionHref}>{actionLabel}</ActionLink>}
      />

      {!setup?.merchantId && (
        <div className={styles.localBoundary} role="status">
          <span><ShieldIcon size="small" /></span>
          <div><strong>Showing setup state, not authoritative readiness</strong><p>A Merchant ID and secure setup handoff are not configured. Backend-derived values remain empty.</p></div>
          <Link href="/merchant/settings">View boundary</Link>
        </div>
      )}

      {snapshot.errors.length > 0 && (
        <p className={styles.dataWarning} role="status">{snapshot.errors.join(" · ")}. No unavailable value is inferred.</p>
      )}

      <section className={styles.readiness} aria-labelledby="store-readiness-title" data-tour="readiness-summary">
        <div className={styles.readinessLead}>
          <div className={styles.readinessTopline}>
            <span className={styles.statusDot} data-tone={statusTone(overallStatus)} />
            <p>Deterministic reducer</p>
          </div>
          <h2 id="store-readiness-title">Store readiness</h2>
          <p className={styles.readinessState} data-tone={statusTone(overallStatus)}>
            {overallStatus === "NOT_STARTED" ? "Not started" : overallStatus === "NEEDS_ATTENTION" ? "Needs attention" : "Ready"}
          </p>
          <p className={styles.readinessCopy}>
            {snapshot.manifest
              ? `Manifest v${snapshot.manifest.manifestVersion} is the latest backend-published readiness record.`
              : loading ? "Reading authoritative merchant records…" : "No deterministic manifest is available for this merchant."}
          </p>
          <Link className={styles.textLink} href="/merchant/manifest">Inspect manifest <ArrowRightIcon size="small" /></Link>
        </div>

        <div className={styles.capabilitySummary}>
          <div className={styles.summaryMetric}>
            <strong>{readyCount.toString().padStart(2, "0")}</strong>
            <span>advertised capabilities</span>
          </div>
          <div className={styles.capabilityList}>
            {capabilities.length ? capabilities.slice(0, 5).map((capability) => (
              <div key={capability.capability}>
                <span className={styles.statusDot} data-tone={statusTone(capability.readiness)} />
                <strong>{titleCase(capability.capability)}</strong>
                <small>{capability.readiness}</small>
              </div>
            )) : (
              <div className={styles.emptyCapability}>
                <ShieldIcon size="medium" />
                <p><strong>No readiness evaluations published</strong><span>Capabilities stay unadvertised until the reducer has sufficient evidence.</span></p>
              </div>
            )}
          </div>
        </div>
      </section>

      <section className={styles.operations} aria-labelledby="operational-inputs-title">
        <div className={styles.sectionHeading}>
          <div><p className={styles.eyebrow}>Operational inputs</p><h2 id="operational-inputs-title">What readiness depends on</h2></div>
          <p>Backend values are labelled. Setup-only values are never promoted to evidence.</p>
        </div>

        <div className={styles.operationsGrid}>
          <OperationalRow
            detail={snapshot.catalogue ? `${snapshot.catalogue.activeProducts} active · ${snapshot.catalogue.unresolvedIdentities} unresolved identities` : "No backend catalogue health record"}
            href="/merchant/catalogue"
            icon={<PackageIcon size="small" />}
            label="Catalogue"
            meta={snapshot.catalogue ? `v${snapshot.catalogue.version}` : "Not available"}
            tone={snapshot.catalogue ? snapshot.catalogue.unresolvedIdentities ? "attention" : "ready" : "neutral"}
          />
          <OperationalRow
            detail={snapshot.policies.length ? `${snapshot.policies.length} backend policy document records available` : "No backend policy documents"}
            href="/merchant/policies"
            icon={<FileTextIcon size="small" />}
            label="Policies"
            meta={snapshot.policies.length ? `${snapshot.policies.length} documents` : "Not available"}
            tone={snapshot.policies.length ? "ready" : "neutral"}
          />
          <OperationalRow
            detail={capabilities.length ? `${blockedCount} blocked · ${capabilities.length - readyCount - blockedCount} untested` : "Awaiting deterministic evaluations"}
            href="/merchant/capabilities"
            icon={<ShieldIcon size="small" />}
            label="Capabilities"
            meta={`${readyCount}/${capabilities.length || 0} ready`}
            tone={blockedCount ? "attention" : readyCount ? "ready" : "neutral"}
          />
          <OperationalRow
            detail={snapshot.manifest ? `Published ${new Date(snapshot.manifest.publishedAt).toLocaleDateString()}` : "No evidence-backed publication"}
            href="/merchant/evidence"
            icon={<ListIcon size="small" />}
            label="Verification"
            meta={snapshot.manifest ? "Recorded" : "Not available"}
            tone={snapshot.manifest ? "ready" : "neutral"}
          />
          <OperationalRow
            detail={snapshot.manifest ? `Schema ${snapshot.manifest.schemaVersion} · ${snapshot.manifest.publicationComponent}` : "Reducer output will appear here"}
            href="/merchant/manifest"
            icon={<CodeSnippetIcon size="small" />}
            label="Manifest"
            meta={snapshot.manifest ? `v${snapshot.manifest.manifestVersion}` : "Not published"}
            tone={snapshot.manifest ? "ready" : "neutral"}
          />
        </div>
      </section>
    </div>
  );
}

function OperationalRow({
  detail,
  href,
  icon,
  label,
  meta,
  tone,
}: {
  detail: string;
  href: string;
  icon: ReactNode;
  label: string;
  meta: string;
  tone: "ready" | "attention" | "neutral";
}) {
  return (
    <Link className={styles.operationRow} href={href}>
      <span className={styles.operationIcon}>{icon}</span>
      <span className={styles.operationName}><strong>{label}</strong><small>{detail}</small></span>
      <span className={styles.operationMeta}><i className={styles.statusDot} data-tone={tone} />{meta}</span>
      <ArrowRightIcon size="small" />
    </Link>
  );
}

const lifecycle = [
  { label: "Inspect", detail: "Validate approved source structure", icon: StorefrontIcon },
  { label: "Discover", detail: "Identify supported commerce operations", icon: RefreshIcon },
  { label: "Map", detail: "Propose canonical capability mappings", icon: CodeSnippetIcon },
  { label: "Test", detail: "Run bounded contract verification", icon: TestIcon },
  { label: "Diagnose", detail: "Trace failures to submitted evidence", icon: ActivityIcon },
  { label: "Repair", detail: "Propose a versioned mapping repair", icon: RefreshIcon },
  { label: "Retest", detail: "Re-evaluate before reducer publication", icon: CheckCircleIcon },
] as const;

export function MerchantAgentization() {
  const { setup, snapshot, loading } = useMerchantViewState();
  const handoffRequested = setup?.status === "HANDOFF_REQUESTED";
  const manifest = snapshot.manifest;

  return (
    <div className={styles.page}>
      <PageHeader
        eyebrow="Agentization workbench"
        title="Inspect. Map. Verify."
        description="A bounded operating surface for turning merchant-approved sources into evidence-backed commerce capabilities."
        action={<ActionLink href="/merchant/onboarding">Review approved sources</ActionLink>}
      />

      <section className={styles.runBanner} aria-labelledby="current-run-title">
        <div className={styles.runIdentity}>
          <span className={styles.runIcon}><ActivityIcon size="medium" /></span>
          <div>
            <p>{manifest ? "Latest recorded run" : "Current run"}</p>
            <h2 id="current-run-title">{manifest ? `Published run ${manifest.runId.slice(0, 8)}` : "No active backend run"}</h2>
          </div>
        </div>
        <div className={styles.runStatus}>
          <span className={styles.statusDot} data-tone={manifest ? "ready" : handoffRequested ? "attention" : "neutral"} />
          <div><strong>{manifest ? "Manifest published" : handoffRequested ? "Handoff requested" : "Not started"}</strong><small>{loading ? "Reading backend…" : manifest ? `Manifest v${manifest.manifestVersion}` : "No run ID is available"}</small></div>
        </div>
        <div className={styles.runBoundary}>
          <ShieldIcon size="small" />
          <p>The agent proposes. Merchant authority resolves ambiguity. The deterministic reducer publishes readiness.</p>
        </div>
      </section>

      <section className={styles.lifecycleSection} aria-labelledby="lifecycle-title">
        <div className={styles.sectionHeading}>
          <div><p className={styles.eyebrow}>Lifecycle</p><h2 id="lifecycle-title">Evidence, not assertion</h2></div>
          <span className={styles.foundationLabel}>015D1 foundation</span>
        </div>
        <ol className={styles.lifecycleList}>
          {lifecycle.map(({ label, detail, icon: Icon }, index) => (
            <li data-state={manifest ? "complete" : index === 0 && handoffRequested ? "waiting" : "idle"} key={label}>
              <span className={styles.lifecycleRail} />
              <span className={styles.lifecycleIcon}><Icon size="small" /></span>
              <span className={styles.lifecycleCopy}><strong>{label}</strong><small>{detail}</small></span>
              <span className={styles.lifecycleState}>{manifest ? "Recorded" : index === 0 && handoffRequested ? "Awaiting handoff" : "Pending"}</span>
            </li>
          ))}
        </ol>
      </section>

      <div className={styles.workbenchBottom}>
        <section className={styles.nextAction} aria-labelledby="next-action-title">
          <p className={styles.eyebrow}>Next action</p>
          <h2 id="next-action-title">{handoffRequested ? "Connect the secure setup handoff" : "Approve your commerce sources"}</h2>
          <p>{handoffRequested ? "Your local request is preserved, but the product has no Merchant setup endpoint that can register the full configuration safely. No agentization run has been claimed." : "Complete the short setup to define the only sources and endpoint Amana may inspect."}</p>
          <Link className={styles.secondaryAction} href="/merchant/onboarding">{handoffRequested ? "Inspect handoff boundary" : "Begin source setup"}<ArrowRightIcon size="small" /></Link>
        </section>
        <section className={styles.evidenceRule} aria-labelledby="readiness-rule-title">
          <div><ShieldIcon size="medium" /><span>Readiness rule</span></div>
          <h2 id="readiness-rule-title">READY can only come from the reducer.</h2>
          <p>Mapping proposals, successful calls, and model confidence are inputs—not authority. Capabilities remain unadvertised until required evidence resolves deterministically.</p>
        </section>
      </div>
    </div>
  );
}

type ResourceKind = "catalogue" | "policies" | "capabilities" | "evidence" | "manifest" | "settings";

const resourceContent: Record<ResourceKind, { eyebrow: string; title: string; description: string; icon: typeof PackageIcon }> = {
  catalogue: { eyebrow: "Commerce data", title: "Catalogue", description: "Published product identity and health from approved structured catalogue sources.", icon: PackageIcon },
  policies: { eyebrow: "Merchant authority", title: "Policies", description: "Approved policy documents and versioned business rules used during deterministic resolution.", icon: FileTextIcon },
  capabilities: { eyebrow: "Deterministic readiness", title: "Capabilities", description: "Canonical commerce operations and the evidence required before they may be advertised.", icon: ShieldIcon },
  evidence: { eyebrow: "Verification ledger", title: "Verification / Evidence", description: "Inspectable contract-test, mapping, policy and readiness references produced by agentization.", icon: ListIcon },
  manifest: { eyebrow: "Published contract", title: "Manifest", description: "The reducer-owned capability publication consumed by Safe AI Buyer discovery.", icon: CodeSnippetIcon },
  settings: { eyebrow: "Workspace configuration", title: "Settings", description: "Merchant identity, approved-source boundaries and backend integration status.", icon: StorefrontIcon },
};

export function MerchantResourcePage({ kind }: { kind: ResourceKind }) {
  const { replayTour } = useMerchantTour();
  const { selectedMerchant } = useMerchantSession();
  const { setup, snapshot, loading } = useMerchantViewState();
  const content = resourceContent[kind];
  const Icon = content.icon;
  const factualRows = useMemo(() => {
    if (kind === "catalogue" && snapshot.catalogue) return [
      ["Catalogue version", `v${snapshot.catalogue.version}`],
      ["Active products", String(snapshot.catalogue.activeProducts)],
      ["Unresolved identities", String(snapshot.catalogue.unresolvedIdentities)],
      ["Stale / conflicting facts", `${snapshot.catalogue.staleFacts} / ${snapshot.catalogue.conflictingFacts}`],
    ];
    if (kind === "policies" && snapshot.policies.length) return snapshot.policies.slice(0, 6).map((policy) => [policy.title, `${titleCase(policy.documentType)} · v${policy.documentVersion}`]);
    if (kind === "capabilities" && snapshot.manifest) return snapshot.manifest.capabilities.map((capability) => [titleCase(capability.capability), capability.readiness]);
    if (kind === "manifest" && snapshot.manifest) return [
      ["Manifest", `v${snapshot.manifest.manifestVersion}`],
      ["Schema", `v${snapshot.manifest.schemaVersion}`],
      ["Catalogue", snapshot.manifest.catalogueVersion],
      ["Publication component", snapshot.manifest.publicationComponent],
      ["Published", new Date(snapshot.manifest.publishedAt).toLocaleString()],
    ];
    if (kind === "evidence" && snapshot.manifest) return snapshot.manifest.capabilities.map((capability) => [titleCase(capability.capability), capability.readinessEvaluationId ?? "No evaluation reference"]);
    if (kind === "settings" && setup) return [
      ["Store", setup.store.name || "Not supplied"],
      ["Merchant ID", setup.merchantId || "Not configured"],
      ["Approved endpoint", setup.connection.approvedEndpoint || "Not supplied"],
      ["Credential handling", setup.connection.credentialReference ? "Reference only · concealed" : "No reference"],
      ["Setup authority", "Local draft · not authoritative"],
    ];
    return [];
  }, [kind, setup, snapshot]);
  const band = useMemo(() => {
    const capabilities = snapshot.manifest?.capabilities ?? [];
    const ready = capabilities.filter((capability) => capability.advertised && capability.readiness === "READY").length;
    const untested = capabilities.filter((capability) => capability.readiness === "UNTESTED").length;
    const blocked = capabilities.filter((capability) => capability.readiness === "BLOCKED").length;
    const evaluations = capabilities.filter((capability) => capability.readinessEvaluationId).length;
    const configuredSources = setup
      ? Object.values(setup.sources).filter(Boolean).length + Number(Boolean(setup.connection.approvedEndpoint))
      : 0;

    if (kind === "catalogue") return {
      title: "Catalogue authority",
      value: snapshot.catalogue ? `${snapshot.catalogue.activeProducts} active products` : "No catalogue health published",
      description: "Structured product identity from merchant-approved catalogue sources.",
      metrics: [
        { label: "Identity", value: snapshot.catalogue ? `${snapshot.catalogue.unresolvedIdentities} unresolved` : "Awaiting evidence" },
        { label: "Publication", value: snapshot.catalogue ? `v${snapshot.catalogue.version}` : "Not published" },
      ],
    };
    if (kind === "policies") return {
      title: "Policy authority",
      value: snapshot.policies.length ? `${snapshot.policies.length} policy document${snapshot.policies.length === 1 ? "" : "s"}` : "No policy authority published",
      description: "Versioned merchant rules used during deterministic resolution.",
      metrics: [
        { label: "Records", value: snapshot.policies.length ? "Backend-backed" : "Awaiting evidence" },
        { label: "Resolution", value: "Deterministic" },
      ],
    };
    if (kind === "capabilities") return {
      title: "Capability readiness",
      value: snapshot.manifest ? `${ready} capabilities ready` : "No readiness publication",
      description: "Only reducer-owned evidence can advertise a commerce capability.",
      metrics: [
        { label: "Untested", value: snapshot.manifest ? String(untested) : "Awaiting reducer" },
        { label: "Blocked", value: snapshot.manifest ? String(blocked) : "Not evaluated" },
        { label: "Authority", value: "Reducer-owned" },
      ],
    };
    if (kind === "evidence") return {
      title: "Evidence ledger",
      value: snapshot.manifest ? `${evaluations} reducer evaluations` : "No evidence ledger published",
      description: "Inspectable lineage for mapping, contracts, policy and readiness.",
      metrics: [
        { label: "Coverage", value: "Mapping · contracts · policy" },
        { label: "Authority", value: "Reducer-owned" },
      ],
    };
    if (kind === "manifest") return {
      title: "Published commerce contract",
      value: snapshot.manifest ? `Manifest v${snapshot.manifest.manifestVersion}` : "No published manifest",
      description: "The deterministic commerce surface consumed during agent discovery.",
      metrics: [
        { label: "Schema", value: snapshot.manifest ? `v${snapshot.manifest.schemaVersion}` : "Not published" },
        { label: "Consumer", value: "Safe AI Buyer discovery" },
      ],
    };
    return {
      title: "Merchant workspace",
      value: selectedMerchant?.displayName ?? setup?.store.name ?? "No merchant selected",
      description: "Approved-source configuration and authenticated workspace context.",
      metrics: [
        { label: "Setup", value: setup ? titleCase(setup.status) : "Not configured" },
        { label: "Source boundary", value: configuredSources ? `${configuredSources} references` : "Not configured" },
      ],
    };
  }, [kind, selectedMerchant, setup, snapshot]);

  return (
    <div className={styles.page}>
      <PageBand
        actionHref={kind === "settings" ? "/merchant/onboarding" : "/merchant/agentization"}
        actionLabel={kind === "settings" ? "Edit approved sources" : "Open agentization"}
        description={band.description}
        kicker={content.eyebrow}
        metrics={band.metrics}
        title={band.title}
        value={band.value}
      />
      <section className={styles.resourceSurface}>
        <header>
          <span><Icon size="medium" /></span>
          <div><h2>{factualRows.length ? "Authoritative records" : loading ? "Reading authoritative records" : "No authoritative records yet"}</h2><p>{factualRows.length ? "Values below were returned by protected Merchant backend APIs." : "Amana will not infer or fabricate missing merchant state."}</p></div>
        </header>
        {factualRows.length ? (
          <dl className={styles.factList}>{factualRows.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>
        ) : (
          <div className={styles.resourceEmpty}>
            <ClockIcon size="medium" />
            <p><strong>{setup?.merchantId ? "Awaiting backend evidence" : "Backend connection required"}</strong><span>{setup?.merchantId ? "The configured Merchant backend has not published this record." : "Complete approved-source setup and configure a Merchant ID. Local drafts cannot become readiness evidence."}</span></p>
          </div>
        )}
      </section>
      {kind === "settings" && (
        <section className={styles.tourReplay} aria-labelledby="replay-product-tour-title">
          <div>
            <p className={styles.eyebrow}>Local presentation</p>
            <h2 id="replay-product-tour-title">Merchant console tour</h2>
            <p>Review the console walkthrough again. This does not reset merchant data, evidence, authority, or readiness.</p>
          </div>
          <AmanaButton onClick={replayTour} variant="secondary">Replay product tour</AmanaButton>
        </section>
      )}
    </div>
  );
}
