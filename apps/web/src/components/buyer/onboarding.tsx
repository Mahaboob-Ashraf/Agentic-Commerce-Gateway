"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { buyerApi, BuyerApiError, demoMerchants } from "@/lib/buyer/api";
import { buyerOnboardingSeenKey, canEnterBuyer } from "@/lib/buyer/buyer-experience";
import type { AddressInput, BuyerAddress, MerchantAccountLink, OnboardingStatus, ProfileInput } from "@/lib/buyer/types";
import { AmanaMark } from "./amana-mark";
import { useBuyerSession } from "./buyer-session";
import { Icon } from "./icons";
import { MerchantLogo } from "./merchant-logo";
import styles from "./onboarding.module.css";

const steps = ["Welcome", "About you", "Delivery", "Merchants", "Preferences", "Ready"];
const emptyProfile: ProfileInput = { recipientName: "", phone: "", email: "" };

export function Onboarding() {
  const router = useRouter();
  const { actor } = useBuyerSession();
  const [step, setStep] = useState(0);
  const [profile, setProfile] = useState(emptyProfile);
  const [status, setStatus] = useState<OnboardingStatus | null>(null);
  const [addresses, setAddresses] = useState<BuyerAddress[]>([]);
  const [links, setLinks] = useState<MerchantAccountLink[]>([]);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    Promise.all([
      buyerApi.onboardingStatus(),
      buyerApi.addresses(),
      buyerApi.links(),
      buyerApi.profile().catch((error) => {
        if (error instanceof BuyerApiError && error.status === 404) return null;
        throw error;
      }),
    ]).then(([nextStatus, nextAddresses, nextLinks, nextProfile]) => {
      setStatus(nextStatus); setAddresses(nextAddresses); setLinks(nextLinks);
      if (nextProfile) setProfile({ recipientName: nextProfile.recipientName, phone: nextProfile.phone, email: nextProfile.email });
    }).catch((error: Error) => setMessage(error.message));
  }, []);

  async function saveProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setMessage("");
    try { await buyerApi.saveProfile(profile); setStatus(await buyerApi.onboardingStatus()); setStep(2); }
    catch (error) { setMessage(error instanceof Error ? error.message : "Could not save profile"); }
    finally { setBusy(false); }
  }

  async function saveAddress(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget); setBusy(true); setMessage("");
    try { const saved = await buyerApi.addAddress(addressFrom(data)); await buyerApi.selectAddress(saved.id); const [nextAddresses, nextStatus] = await Promise.all([buyerApi.addresses(), buyerApi.onboardingStatus()]); setAddresses(nextAddresses); setStatus(nextStatus); setStep(3); }
    catch (error) { setMessage(error instanceof Error ? error.message : "Could not save address"); }
    finally { setBusy(false); }
  }

  function enterBuyer() {
    if (!actor || !canEnterBuyer(status)) return;
    localStorage.setItem(buyerOnboardingSeenKey(actor.actorId), "true");
    localStorage.setItem(`amana:tour-pending:${actor.actorId}`, "true");
    router.push("/buyer/chat?tour=1");
  }

  return <main className={styles.page}>
    <header className={styles.topbar}><button className={styles.wordmark} onClick={() => router.push("/")} type="button"><AmanaMark className={styles.brandMark} priority />Amana</button><button className={styles.exit} onClick={() => router.push("/")} type="button">Exit setup</button></header>
    <div className={styles.progress} aria-label={`Onboarding step ${step + 1} of ${steps.length}`}><div className={styles.progressTrack}>{steps.map((label, index) => <span data-active={index <= step} key={label} />)}</div><p><strong>{String(step + 1).padStart(2, "0")}</strong> / {String(steps.length).padStart(2, "0")} · {steps[step]}</p></div>
    <section className={styles.stage} data-step={step}>
      {step === 0 && <Welcome onNext={() => setStep(1)} />}
      {step === 1 && <form className={styles.step} onSubmit={saveProfile}><StepHeading eyebrow="A familiar face" title="How should we know you?" description="These details become your delivery identity. You can change them later." /><div className={styles.formGrid}><Field label="Full name" value={profile.recipientName} onChange={(value) => setProfile({ ...profile, recipientName: value })} autoComplete="name" /><Field label="Phone number" value={profile.phone} onChange={(value) => setProfile({ ...profile, phone: value })} autoComplete="tel" /><Field label="Email address" value={profile.email} onChange={(value) => setProfile({ ...profile, email: value })} type="email" autoComplete="email" /></div><StepActions back={() => setStep(0)} busy={busy} label="Save and continue" /></form>}
      {step === 2 && <div className={styles.step}><StepHeading eyebrow="Where things arrive" title="Choose a delivery address." description="Amana binds the exact selected address version before a purchase can move forward." />{addresses.length > 0 && <div className={styles.existingAddresses}>{addresses.map((address) => <button data-selected={address.selected} key={address.id} onClick={async () => { await buyerApi.selectAddress(address.id); setStatus(await buyerApi.onboardingStatus()); setStep(3); }} type="button"><span>{address.label}</span><strong>{address.addressLine1}</strong><small>{address.city} · {address.postalCode}</small>{address.selected && <Icon name="check" />}</button>)}</div>}<form className={styles.addressForm} onSubmit={saveAddress}><Field name="label" label="Label" defaultValue="Home" /><Field name="recipientName" label="Recipient name" defaultValue={profile.recipientName} /><Field name="phone" label="Phone" defaultValue={profile.phone} /><Field name="addressLine1" label="Address line 1" /><Field name="addressLine2" label="Address line 2 (optional)" required={false} /><Field name="locality" label="Locality" /><Field name="city" label="City" /><Field name="state" label="State" /><Field name="postalCode" label="PIN code" inputMode="numeric" pattern="[1-9][0-9]{5}" /><StepActions back={() => setStep(1)} busy={busy} label="Save address" /></form></div>}
      {step === 3 && <MerchantStep links={links} setLinks={setLinks} onBack={() => setStep(2)} onNext={() => setStep(4)} setMessage={setMessage} />}
      {step === 4 && <PreferenceStep actorId={actor?.actorId ?? "buyer"} onBack={() => setStep(3)} onNext={() => setStep(5)} />}
      {step === 5 && <div className={styles.step}><div className={styles.readyMark}><Icon name="check" /></div><StepHeading eyebrow="Your space is ready" title={`Welcome${profile.recipientName ? `, ${profile.recipientName.split(" ")[0]}` : ""}.`} description="Begin with a request, or take a moment to see where conversations, orders, and your safety preferences live." /><dl className={styles.summary}><div><dt>Delivery</dt><dd>{status?.addressSelected ? "Address selected" : "Still required"}</dd></div><div><dt>Merchants</dt><dd>{links.filter((link) => link.status === "LINKED").length || "None yet"}</dd></div><div><dt>Safety</dt><dd>Fail-closed boundaries stay on</dd></div></dl><div className={styles.finalActions}><button className={styles.backButton} onClick={() => setStep(4)} type="button">Back</button><button className={styles.continueButton} disabled={!canEnterBuyer(status)} onClick={enterBuyer} type="button">Enter Buyer <Icon name="arrow" /></button></div></div>}
    </section>
    {message && <p className={styles.alert} role="alert">{message}</p>}
  </main>;
}

function Welcome({ onNext }: { onNext: () => void }) { return <div className={`${styles.step} ${styles.welcome}`}><StepHeading eyebrow="Welcome to Amana" title="Commerce, with calm built in." description="Set up the few details Amana needs to ground delivery and respect your preferences. You stay in control at every consequential step." /><div className={styles.promise}><div><span>01</span><p><strong>Tell us the essentials.</strong> Keep setup short; adjust anything later.</p></div><div><span>02</span><p><strong>Connect when you choose.</strong> Merchant accounts are optional during setup.</p></div><div><span>03</span><p><strong>Nothing moves silently.</strong> Money and safety remain behind deterministic checks.</p></div></div><button className={styles.continueButton} onClick={onNext} type="button">Begin setup <Icon name="arrow" /></button></div>; }

function MerchantStep({ links, setLinks, onBack, onNext, setMessage }: { links: MerchantAccountLink[]; setLinks: (links: MerchantAccountLink[]) => void; onBack: () => void; onNext: () => void; setMessage: (message: string) => void }) {
  const [connecting, setConnecting] = useState<string | null>(null);
  async function connect(event: FormEvent<HTMLFormElement>, id: string) { event.preventDefault(); const data = new FormData(event.currentTarget); try { await buyerApi.linkMerchant(id, String(data.get("username")), String(data.get("password"))); setLinks(await buyerApi.links()); setConnecting(null); } catch (error) { setMessage(error instanceof Error ? error.message : "Merchant connection failed"); } }
  return <div className={styles.step}><StepHeading eyebrow="Shop where you already shop" title="Connect merchant accounts." description="Optional for now. A real connection is required only when you choose to transact." /><div className={styles.merchants}>{demoMerchants.map((merchant) => { const linked = merchant.merchantId && links.some((link) => link.merchantId === merchant.merchantId && link.status === "LINKED"); return <article key={merchant.key}><MerchantLogo className={styles.merchantLogo} name={merchant.name} logoUrl={merchant.logoUrl} /><div><h3>{merchant.name}</h3><p>{merchant.description}</p><span data-connected={Boolean(linked)}>{linked ? "Connected" : merchant.merchantId ? "Ready to connect" : "Needs deployment configuration"}</span></div>{!linked && <button disabled={!merchant.merchantId} onClick={() => setConnecting(merchant.key)} type="button">Connect</button>}{connecting === merchant.key && merchant.merchantId && <form onSubmit={(event) => void connect(event, merchant.merchantId!)}><Field name="username" label="Merchant username" autoComplete="username" /><Field name="password" label="Merchant password" type="password" autoComplete="current-password" /><button type="submit">Confirm connection</button></form>}</article>; })}</div><p className={styles.securityNote}><Icon name="shield" />Credentials cross the existing merchant-link provider once; only opaque references persist.</p><div className={styles.skipRow}><button className={styles.backButton} onClick={onBack} type="button">Back</button><button className={styles.skipButton} onClick={onNext} type="button">Skip for now</button><button className={styles.continueButton} onClick={onNext} type="button">Continue <Icon name="arrow" /></button></div></div>;
}

function PreferenceStep({ actorId, onBack, onNext }: { actorId: string; onBack: () => void; onNext: () => void }) {
  const [vegetarian, setVegetarian] = useState(false); const [unknown, setUnknown] = useState(true); const [allergies, setAllergies] = useState("");
  function save() { localStorage.setItem(`amana:preferences:${actorId}`, JSON.stringify({ vegetarian, avoidUnknownAllergens: unknown, allergies, localFirst: false })); onNext(); }
  return <div className={styles.step}><StepHeading eyebrow="Preference, not presumption" title="What should Amana keep in mind?" description="These help shape future requests. Authoritative evidence—not a checkbox—still decides whether safety constraints pass." /><div className={styles.preferenceList}><Choice checked={vegetarian} title="Prefer vegetarian products" detail="Use as a requirement where food is involved." onChange={setVegetarian} /><Choice checked={unknown} title="Pause on unknown allergen evidence" detail="Amana’s backend remains fail-closed for safety independently." onChange={setUnknown} /><label className={styles.allergies}>Ingredients or allergens to avoid<input value={allergies} onChange={(event) => setAllergies(event.target.value)} placeholder="For example: peanuts, soy" /></label></div><p className={styles.localNote}>Preferences are stored on this device in C1 and are not sent into commerce yet.</p><div className={styles.finalActions}><button className={styles.backButton} onClick={onBack} type="button">Back</button><button className={styles.continueButton} onClick={save} type="button">Save and continue <Icon name="arrow" /></button></div></div>;
}

function StepHeading({ eyebrow, title, description }: { eyebrow: string; title: string; description: string }) { return <header className={styles.stepHeading}><p>{eyebrow}</p><h1>{title}</h1><span>{description}</span></header>; }
function StepActions({ back, busy, label }: { back: () => void; busy: boolean; label: string }) { return <div className={styles.finalActions}><button className={styles.backButton} onClick={back} type="button">Back</button><button className={styles.continueButton} disabled={busy} type="submit">{busy ? "Saving…" : label}<Icon name="arrow" /></button></div>; }
function Field({ label, onChange, ...props }: Omit<React.InputHTMLAttributes<HTMLInputElement>, "onChange"> & { label: string; onChange?: (value: string) => void }) { return <label className={styles.field}>{label}<input required {...props} onChange={onChange ? (event) => onChange(event.target.value) : undefined} /></label>; }
function Choice({ checked, title, detail, onChange }: { checked: boolean; title: string; detail: string; onChange: (value: boolean) => void }) { return <label className={styles.choice}><input type="checkbox" checked={checked} onChange={(event) => onChange(event.target.checked)} /><span><Icon name="check" /></span><p><strong>{title}</strong><small>{detail}</small></p></label>; }
function addressFrom(data: FormData): AddressInput { return { label: String(data.get("label")), recipientName: String(data.get("recipientName")), phone: String(data.get("phone")), addressLine1: String(data.get("addressLine1")), addressLine2: String(data.get("addressLine2")) || null, locality: String(data.get("locality")), city: String(data.get("city")), state: String(data.get("state")), postalCode: String(data.get("postalCode")) }; }
