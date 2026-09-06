"use client";

import {
  ArrowLeftIcon,
  ArrowRightIcon,
  CheckCircleIcon,
  Checkbox,
  CodeSnippetIcon,
  FileTextIcon,
  GlobeIcon,
  KeyIcon,
  LockIcon,
  PackageIcon,
  ServerIcon,
  ShieldIcon,
  StorefrontIcon,
  TextInput,
} from "@razorpay/blade/components";
import Link from "next/link";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { useMemo, useState, type ReactNode } from "react";
import { AmanaButton } from "@/components/amana/blade";
import {
  emptyMerchantSetup,
  hasApprovedSource,
  loadMerchantSetup,
  saveMerchantSetup,
  setupIsReviewable,
} from "@/lib/merchant/setup-store";
import type { MerchantSetup } from "@/lib/merchant/types";
import { useMerchantSession } from "./merchant-session";
import styles from "./merchant-onboarding.module.css";

const steps = [
  { label: "Store profile", short: "Store", detail: "Establish the commerce context Amana should understand." },
  { label: "Approved sources", short: "Sources", detail: "Choose the structured sources Amana is allowed to inspect." },
  { label: "Credentials & endpoints", short: "Access", detail: "Define the exact connection boundary. References only—never raw secrets." },
  { label: "Review connection", short: "Review", detail: "Verify the authority chain before any agentization handoff." },
  { label: "Start agentization", short: "Start", detail: "Hand the approved configuration to the agentization service." },
] as const;

type SourceKey = keyof MerchantSetup["sources"];

function FieldNote({ children }: { children: ReactNode }) {
  return <p className={styles.fieldNote}>{children}</p>;
}

export function MerchantOnboarding() {
  const { actor, selectedMerchant } = useMerchantSession();
  const router = useRouter();
  const [step, setStep] = useState(0);
  const [setup, setSetup] = useState<MerchantSetup>(() =>
    actor ? loadMerchantSetup(actor.actorId) : emptyMerchantSetup(),
  );
  const [error, setError] = useState("");

  const configuredSources = useMemo(
    () => Object.values(setup.sources).filter((value) => value.trim()).length,
    [setup.sources],
  );

  function persist(next: MerchantSetup) {
    if (!actor) return next;
    const saved = saveMerchantSetup(actor.actorId, next);
    setSetup(saved);
    return saved;
  }

  function patchStore(key: keyof MerchantSetup["store"], value: string) {
    setSetup((current) => ({ ...current, store: { ...current.store, [key]: value }, status: "DRAFT" }));
  }

  function patchSource(key: SourceKey, value: string) {
    setSetup((current) => ({ ...current, sources: { ...current.sources, [key]: value }, status: "DRAFT" }));
  }

  function patchConnection(key: keyof MerchantSetup["connection"], value: string) {
    setSetup((current) => ({ ...current, connection: { ...current.connection, [key]: value }, status: "DRAFT" }));
  }

  function validateCurrent() {
    if (step === 0 && (!setup.store.name.trim() || !setup.store.category.trim())) {
      setError("Add a store name and commerce category to continue.");
      return false;
    }
    if (step === 1 && !hasApprovedSource(setup)) {
      setError("Approve at least one structured commerce source to continue.");
      return false;
    }
    if (step === 2 && !setup.connection.approvedEndpoint.trim()) {
      setError("Add the approved base endpoint Amana may connect to.");
      return false;
    }
    setError("");
    return true;
  }

  function next() {
    if (!validateCurrent()) return;
    let nextSetup = persist(setup);
    if (step === 3) nextSetup = persist({ ...nextSetup, status: "REVIEWED" });
    setSetup(nextSetup);
    setStep((current) => Math.min(current + 1, steps.length - 1));
  }

  function back() {
    setError("");
    setStep((current) => Math.max(0, current - 1));
  }

  function startAgentization() {
    const authorizedSetup = selectedMerchant
      ? { ...setup, merchantId: selectedMerchant.merchantId }
      : setup;
    if (!setupIsReviewable(authorizedSetup)) {
      setError("The configuration is incomplete. Return to the earlier steps and resolve missing fields.");
      return;
    }
    persist({ ...authorizedSetup, status: "HANDOFF_REQUESTED" });
    router.push("/merchant/agentization");
  }

  return (
    <main className={styles.page}>
      <header className={styles.topbar}>
        <Link className={styles.brand} href="/merchant/overview" aria-label="Amana Merchant home">
          <Image alt="Amana" className={styles.brandMark} height={36} priority src="/amana/amana-mark.png" width={36} />
          <span>Amana <small>Merchant</small></span>
        </Link>
        <div className={styles.securityNote}><LockIcon size="small" /> Secure setup · local draft</div>
        <Link className={styles.exitLink} href="/merchant/overview">Exit setup</Link>
      </header>

      <div className={styles.composition}>
        <aside className={styles.contextPanel} aria-label="Onboarding progress">
          <div>
            <p className={styles.eyebrow}>Agentization setup</p>
            <p className={styles.stepCount}>0{step + 1} <span>/ 0{steps.length}</span></p>
            <h1>{steps[step].label}</h1>
            <p className={styles.stepDetail}>{steps[step].detail}</p>
          </div>

          <ol className={styles.progressList}>
            {steps.map((item, index) => (
              <li data-active={index === step || undefined} data-complete={index < step || undefined} key={item.label}>
                <button
                  aria-current={index === step ? "step" : undefined}
                  disabled={index > step}
                  onClick={() => index < step && setStep(index)}
                  type="button"
                >
                  <span className={styles.progressMarker}>{index < step ? "✓" : index + 1}</span>
                  <span><strong>{item.short}</strong><small>{item.label}</small></span>
                </button>
              </li>
            ))}
          </ol>

          <div className={styles.authorityStatement}>
            <ShieldIcon size="medium" />
            <p><strong>Your authority, encoded.</strong> Amana only acts on sources and capabilities you approve.</p>
          </div>
        </aside>

        <section className={styles.formPanel} aria-live="polite">
          <div className={styles.mobileProgress}>
            <span>Step {step + 1} of {steps.length}</span>
            <div><i style={{ width: `${((step + 1) / steps.length) * 100}%` }} /></div>
          </div>

          <div className={styles.formInner}>
            {step === 0 && (
              <div className={styles.stepContent}>
                <div className={styles.formHeading}>
                  <span className={styles.headingIcon}><StorefrontIcon size="medium" /></span>
                  <div><p>Commerce identity</p><h2>Tell Amana what this store represents.</h2></div>
                </div>
                <div className={styles.fieldGrid}>
                  <TextInput
                    isRequired
                    label="Store or merchant name"
                    onChange={({ value }) => patchStore("name", value ?? "")}
                    placeholder="Acme Commerce"
                    value={setup.store.name}
                  />
                  <TextInput
                    isRequired
                    label="Commerce category"
                    onChange={({ value }) => patchStore("category", value ?? "")}
                    placeholder="Electronics, grocery, fashion…"
                    value={setup.store.category}
                  />
                </div>
                <TextInput
                  helpText="Used for context and display. This does not grant crawling permission."
                  label="Primary store domain"
                  leading={GlobeIcon}
                  onChange={({ value }) => patchStore("baseUrl", value ?? "")}
                  placeholder="https://store.example.com"
                  type="url"
                  value={setup.store.baseUrl}
                />
                <div className={styles.boundaryCallout}>
                  <LockIcon size="small" />
                  <p><strong>A domain is context, not consent.</strong> Amana never treats this field as permission to crawl or automate a website.</p>
                </div>
              </div>
            )}

            {step === 1 && (
              <div className={styles.stepContent}>
                <div className={styles.formHeading}>
                  <span className={styles.headingIcon}><FileTextIcon size="medium" /></span>
                  <div><p>Explicit source authority</p><h2>Approve only the records Amana may inspect.</h2></div>
                </div>
                <p className={styles.lead}>References identify structured inputs; Amana does not perform arbitrary website scraping or browser automation.</p>
                <div className={styles.sourceList}>
                  <SourceField
                    description="Machine-readable contract supplied by your team."
                    icon={<CodeSnippetIcon size="small" />}
                    label="OpenAPI specification"
                    onChange={(value) => patchSource("openApiReference", value)}
                    placeholder="Registry URI, repository path or artifact ID"
                    value={setup.sources.openApiReference}
                  />
                  <SourceField
                    description="Structured catalogue feed, schema or existing ingestion reference."
                    icon={<PackageIcon size="small" />}
                    label="Catalogue source"
                    onChange={(value) => patchSource("catalogueReference", value)}
                    placeholder="Feed URI, schema ID or managed connector"
                    value={setup.sources.catalogueReference}
                  />
                  <SourceField
                    description="Approved cancellation, returns, shipping or commerce policy record."
                    icon={<ShieldIcon size="small" />}
                    label="Policy source"
                    onChange={(value) => patchSource("policyReference", value)}
                    placeholder="Document ID, repository path or policy registry URI"
                    value={setup.sources.policyReference}
                  />
                </div>
                <FieldNote>{configuredSources} approved source{configuredSources === 1 ? "" : "s"} configured.</FieldNote>
              </div>
            )}

            {step === 2 && (
              <div className={styles.stepContent}>
                <div className={styles.formHeading}>
                  <span className={styles.headingIcon}><ServerIcon size="medium" /></span>
                  <div><p>Connection boundary</p><h2>Define exactly where approved tests may run.</h2></div>
                </div>
                <TextInput
                  helpText="Only this configured merchant endpoint is eligible for contract testing."
                  isRequired
                  label="Approved base endpoint"
                  leading={ServerIcon}
                  onChange={({ value }) => patchConnection("approvedEndpoint", value ?? "")}
                  placeholder="https://api.store.example.com"
                  type="url"
                  value={setup.connection.approvedEndpoint}
                />
                <TextInput
                  helpText="Enter a vault or secret-manager reference—not a token, password or API key."
                  label="Credential reference"
                  leading={KeyIcon}
                  onChange={({ value }) => patchConnection("credentialReference", value ?? "")}
                  placeholder="vault://commerce/amana-readonly"
                  value={setup.connection.credentialReference}
                />
                <div className={styles.credentialRule}>
                  <Checkbox isChecked isDisabled size="large">Never display credential material after entry</Checkbox>
                  <Checkbox isChecked isDisabled size="large">Restrict execution to the approved endpoint boundary</Checkbox>
                </div>
                <div className={styles.boundaryCallout}>
                  <ShieldIcon size="small" />
                  <p><strong>Safe by construction.</strong> This UI stores references only. Credential resolution and endpoint enforcement must occur in the trusted backend.</p>
                </div>
              </div>
            )}

            {step === 3 && (
              <div className={styles.stepContent}>
                <div className={styles.formHeading}>
                  <span className={styles.headingIcon}><CheckCircleIcon size="medium" /></span>
                  <div><p>Authority review</p><h2>Confirm the connection chain.</h2></div>
                </div>
                <p className={styles.lead}>Nothing below is evidence of readiness. It is the input boundary the backend must accept before deterministic inspection and testing can begin.</p>
                <div className={styles.dependencyMap}>
                  <ReviewNode icon={<StorefrontIcon size="small" />} label="Store" value={setup.store.name} />
                  <div className={styles.mapConnector}><span>{configuredSources}</span> approved sources</div>
                  <ReviewNode icon={<FileTextIcon size="small" />} label="Structured inputs" value={`${configuredSources} source${configuredSources === 1 ? "" : "s"}`} />
                  <div className={styles.mapConnector}><span>1</span> endpoint boundary</div>
                  <ReviewNode icon={<ServerIcon size="small" />} label="Connection" value={setup.connection.approvedEndpoint} />
                </div>
                <dl className={styles.reviewDetails}>
                  <div><dt>OpenAPI</dt><dd>{setup.sources.openApiReference || "Not supplied"}</dd></div>
                  <div><dt>Catalogue</dt><dd>{setup.sources.catalogueReference || "Not supplied"}</dd></div>
                  <div><dt>Policies</dt><dd>{setup.sources.policyReference || "Not supplied"}</dd></div>
                  <div><dt>Credentials</dt><dd>{setup.connection.credentialReference ? "Reference configured · value concealed" : "No credential reference"}</dd></div>
                </dl>
              </div>
            )}

            {step === 4 && (
              <div className={styles.stepContent}>
                <div className={`${styles.formHeading} ${styles.finalHeading}`}>
                  <span className={styles.headingIcon}><ArrowRightIcon size="medium" /></span>
                  <div><p>Handoff boundary</p><h2>Your approved configuration is ready to hand off.</h2></div>
                </div>
                <div className={styles.finalStatement}>
                  <p className={styles.finalNumber}>0{configuredSources}</p>
                  <div><strong>approved structured sources</strong><span>bound to one configured endpoint</span></div>
                </div>
                <div className={styles.truthBox}>
                  <ShieldIcon size="medium" />
                  <div>
                    <h3>What happens next</h3>
                    <p>Amana can inspect, discover, map and test against these approved inputs. It may propose mappings or repairs, but it cannot declare readiness.</p>
                    <p><strong>A deterministic reducer publishes capability readiness only after required evidence exists.</strong></p>
                  </div>
                </div>
                {!selectedMerchant && (
                  <div className={styles.handoffNotice} role="status">
                    <strong>Backend handoff is not configured yet.</strong>
                    <span>The request will be saved locally and shown in Agentization as awaiting a secure setup service. No run will be fabricated.</span>
                  </div>
                )}
              </div>
            )}

            {error && <p className={styles.error} role="alert">{error}</p>}

            <footer className={styles.actions}>
              <button className={styles.backButton} disabled={step === 0} onClick={back} type="button">
                <ArrowLeftIcon size="small" /> Back
              </button>
              {step < steps.length - 1 ? (
                <AmanaButton onClick={next}>Continue</AmanaButton>
              ) : (
                <AmanaButton onClick={startAgentization}>Start agentization</AmanaButton>
              )}
            </footer>
          </div>
        </section>
      </div>
    </main>
  );
}

function SourceField({
  description,
  icon,
  label,
  onChange,
  placeholder,
  value,
}: {
  description: string;
  icon: ReactNode;
  label: string;
  onChange: (value: string) => void;
  placeholder: string;
  value: string;
}) {
  return (
    <div className={styles.sourceField}>
      <span className={styles.sourceIcon}>{icon}</span>
      <div className={styles.sourceCopy}><strong>{label}</strong><span>{description}</span></div>
      <TextInput accessibilityLabel={`${label} reference`} onChange={({ value: next }) => onChange(next ?? "")} placeholder={placeholder} value={value} />
    </div>
  );
}

function ReviewNode({ icon, label, value }: { icon: ReactNode; label: string; value: string }) {
  return (
    <div className={styles.reviewNode}>
      <span>{icon}</span>
      <div><small>{label}</small><strong>{value}</strong></div>
      <CheckCircleIcon color="feedback.icon.positive.intense" size="small" />
    </div>
  );
}
