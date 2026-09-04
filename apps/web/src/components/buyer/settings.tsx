"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { buyerApi, BuyerApiError, demoMerchants } from "@/lib/buyer/api";
import type { AddressInput, BuyerAddress, BuyerProfile, MerchantAccountLink } from "@/lib/buyer/types";
import { useBuyerSession } from "./buyer-session";
import { Icon } from "./icons";
import styles from "./settings.module.css";

const sections = [
  ["/buyer/settings/profile", "Profile"],
  ["/buyer/settings/addresses", "Addresses"],
  ["/buyer/settings/merchants", "Connected merchants"],
  ["/buyer/settings/preferences", "Preferences / safety"],
  ["/buyer/settings/voice", "Voice"],
] as const;

export type SettingsKind = "profile" | "addresses" | "merchants" | "preferences" | "voice";

export function SettingsPage({ kind }: { kind: SettingsKind }) {
  const pathname = usePathname();
  return <div className={styles.page}><header className={styles.header}><p>Buyer settings</p><h1>Make Amana yours.</h1><span>Account details and shopping preferences stay clear, editable, and under your control.</span></header><div className={styles.settingsGrid}><nav className={styles.sectionNav} aria-label="Settings sections">{sections.map(([href, label]) => <Link data-active={pathname === href} href={href} key={href}>{label}<Icon name="chevron" /></Link>)}</nav><div className={styles.content}>{kind === "profile" && <ProfileSettings />}{kind === "addresses" && <AddressSettings />}{kind === "merchants" && <MerchantSettings />}{kind === "preferences" && <PreferenceSettings />}{kind === "voice" && <VoiceSettings />}</div></div></div>;
}

function ProfileSettings() {
  const [profile, setProfile] = useState<BuyerProfile | null>(null);
  const [missing, setMissing] = useState(false);
  const [message, setMessage] = useState("");
  useEffect(() => { buyerApi.profile().then(setProfile).catch((error) => error instanceof BuyerApiError && error.status === 404 ? setMissing(true) : setMessage(error.message)); }, []);
  async function submit(event: FormEvent<HTMLFormElement>) { event.preventDefault(); const data = new FormData(event.currentTarget); setMessage("Saving…"); try { const result = await buyerApi.saveProfile({ recipientName: String(data.get("recipientName")), phone: String(data.get("phone")), email: String(data.get("email")) }); setProfile(result); setMissing(false); setMessage("Profile saved."); } catch (error) { setMessage(error instanceof Error ? error.message : "Could not save profile"); } }
  return <SettingsSection title="Profile" description="Used for delivery identity and account communication."><form className={styles.form} key={profile?.version ?? "new"} onSubmit={submit}><Field label="Full name" name="recipientName" defaultValue={profile?.recipientName} required /><Field label="Phone" name="phone" defaultValue={profile?.phone} autoComplete="tel" required /><Field label="Email" name="email" type="email" defaultValue={profile?.email} autoComplete="email" required /><div className={styles.formFooter}><p aria-live="polite">{message || (missing ? "Complete your profile to continue onboarding." : profile ? `Profile version ${profile.version}` : "Loading profile…")}</p><button type="submit">Save profile</button></div></form></SettingsSection>;
}

function AddressSettings() {
  const [addresses, setAddresses] = useState<BuyerAddress[]>([]);
  const [adding, setAdding] = useState(false);
  const [message, setMessage] = useState("");
  const load = () => buyerApi.addresses().then(setAddresses).catch((error: Error) => setMessage(error.message));
  useEffect(() => { void load(); }, []);
  async function add(event: FormEvent<HTMLFormElement>) { event.preventDefault(); const d = new FormData(event.currentTarget); const input = addressFrom(d); setMessage("Saving…"); try { const saved = await buyerApi.addAddress(input); await buyerApi.selectAddress(saved.id); await load(); setAdding(false); setMessage("Address saved and selected."); } catch (error) { setMessage(error instanceof Error ? error.message : "Could not save address"); } }
  return <SettingsSection title="Delivery addresses" description="The selected address is versioned by Amana before a purchase can proceed."><div className={styles.addressList}>{addresses.map((address) => <article className={styles.address} data-selected={address.selected} key={address.id}><div><span>{address.selected ? "Selected" : address.label}</span><h3>{address.recipientName}</h3><p>{address.addressLine1}{address.addressLine2 ? `, ${address.addressLine2}` : ""}<br />{address.locality}, {address.city} {address.postalCode}</p></div>{!address.selected && <button className={styles.secondary} onClick={async () => { await buyerApi.selectAddress(address.id); await load(); }} type="button">Use this address</button>}</article>)}</div>{adding ? <form className={styles.form} onSubmit={add}><div className={styles.twoColumns}><Field label="Label" name="label" placeholder="Home" required /><Field label="Recipient name" name="recipientName" required /><Field label="Phone" name="phone" required /><Field label="Address line 1" name="addressLine1" required /><Field label="Address line 2" name="addressLine2" /><Field label="Locality" name="locality" required /><Field label="City" name="city" required /><Field label="State" name="state" required /><Field label="PIN code" name="postalCode" inputMode="numeric" pattern="[1-9][0-9]{5}" required /></div><div className={styles.formFooter}><button className={styles.secondary} onClick={() => setAdding(false)} type="button">Cancel</button><button type="submit">Save address</button></div></form> : <button className={styles.addButton} onClick={() => setAdding(true)} type="button"><Icon name="add" />Add another address</button>}<p className={styles.message} aria-live="polite">{message}</p></SettingsSection>;
}

function MerchantSettings() {
  const [links, setLinks] = useState<MerchantAccountLink[]>([]);
  const [connecting, setConnecting] = useState<string | null>(null);
  const [message, setMessage] = useState("");
  const load = () => buyerApi.links().then(setLinks).catch((error: Error) => setMessage(error.message));
  useEffect(() => { void load(); }, []);
  async function connect(event: FormEvent<HTMLFormElement>, merchantId: string) { event.preventDefault(); const data = new FormData(event.currentTarget); setMessage("Connecting securely…"); try { await buyerApi.linkMerchant(merchantId, String(data.get("username")), String(data.get("password"))); setConnecting(null); await load(); setMessage("Merchant connected."); } catch (error) { setMessage(error instanceof Error ? error.message : "Connection failed"); } }
  return <SettingsSection title="Connected merchants" description="Merchant identity is linked through the backend. Amana never stores a merchant password in the browser."><div className={styles.merchantList}>{demoMerchants.map((merchant) => { const link = merchant.merchantId ? links.find((item) => item.merchantId === merchant.merchantId && item.status === "LINKED") : null; return <article className={styles.merchant} key={merchant.key}><div className={styles.merchantMonogram}>{merchant.name.slice(0, 1)}</div><div><h3>{merchant.name}</h3><p>{merchant.description}</p><span data-connected={Boolean(link)}>{link ? "Connected" : merchant.merchantId ? "Not connected" : "Merchant ID not configured"}</span></div>{link ? <button className={styles.secondary} onClick={async () => { await buyerApi.revokeLink(link.id); await load(); }} type="button">Disconnect</button> : <button disabled={!merchant.merchantId} onClick={() => setConnecting(merchant.key)} type="button">Connect</button>}{connecting === merchant.key && merchant.merchantId && <form className={styles.connectForm} onSubmit={(event) => void connect(event, merchant.merchantId!)}><Field label="Merchant username" name="username" autoComplete="username" required /><Field label="Merchant password" name="password" type="password" autoComplete="current-password" required /><div><button className={styles.secondary} onClick={() => setConnecting(null)} type="button">Cancel</button><button type="submit">Connect account</button></div></form>}</article>; })}</div><p className={styles.boundaryNote}><Icon name="shield" />Merchant IDs come from deployment configuration; without them, Amana does not guess which tenant a connection belongs to.</p><p className={styles.message} aria-live="polite">{message}</p></SettingsSection>;
}

type Preferences = { vegetarian: boolean; avoidUnknownAllergens: boolean; allergies: string; localFirst: boolean };
const defaultPreferences: Preferences = { vegetarian: false, avoidUnknownAllergens: true, allergies: "", localFirst: false };

function PreferenceSettings() {
  const { actor } = useBuyerSession();
  const key = `amana:preferences:${actor?.actorId}`;
  const [value, setValue] = useState(defaultPreferences);
  const [saved, setSaved] = useState("");
  useEffect(() => { const stored = localStorage.getItem(key); if (stored) { try { const parsed = JSON.parse(stored); queueMicrotask(() => setValue({ ...defaultPreferences, ...parsed })); } catch {} } }, [key]);
  function persist(event: FormEvent) { event.preventDefault(); localStorage.setItem(key, JSON.stringify(value)); setSaved("Saved on this device."); }
  return <SettingsSection title="Preferences / safety" description="Tell Amana what to consider. Authoritative product evidence still decides whether a safety constraint can pass."><form className={styles.form} onSubmit={persist}><Toggle checked={value.vegetarian} label="Prefer vegetarian products" detail="Use as a shopping requirement where relevant." onChange={(checked) => setValue({ ...value, vegetarian: checked })} /><Toggle checked={value.avoidUnknownAllergens} label="Pause when allergen evidence is unknown" detail="Client preference only; backend safety rules remain fail-closed independently." onChange={(checked) => setValue({ ...value, avoidUnknownAllergens: checked })} /><Field label="Allergies or ingredients to avoid" name="allergies" value={value.allergies} onChange={(event) => setValue({ ...value, allergies: event.target.value })} placeholder="For example: peanuts, soy" /><Toggle checked={value.localFirst} label="Prefer nearby merchants" detail="A soft preference, never a guarantee of availability." onChange={(checked) => setValue({ ...value, localFirst: checked })} /><div className={styles.formFooter}><p>{saved || "Device-only in C1 · not sent to commerce yet"}</p><button type="submit">Save preferences</button></div></form><p className={styles.boundaryNote}><Icon name="shield" />These controls do not create safety guarantees or override server-side constraints.</p></SettingsSection>;
}

function VoiceSettings() {
  const { actor } = useBuyerSession(); const key = `amana:voice:${actor?.actorId}`;
  const [language, setLanguage] = useState("English + Hindi"); const [handsFree, setHandsFree] = useState(false); const [saved, setSaved] = useState("");
  useEffect(() => { const stored = localStorage.getItem(key); if (stored) { try { const parsed = JSON.parse(stored); queueMicrotask(() => { setLanguage(parsed.language ?? "English + Hindi"); setHandsFree(Boolean(parsed.handsFree)); }); } catch {} } }, [key]);
  return <SettingsSection title="Voice" description="Choose a starting preference for production voice. You can still switch languages naturally while speaking."><form className={styles.form} onSubmit={(event) => { event.preventDefault(); localStorage.setItem(key, JSON.stringify({ language, handsFree })); setSaved("Saved on this device."); }}><label className={styles.field}>Preferred language<select value={language} onChange={(event) => setLanguage(event.target.value)}><option>English + Hindi</option><option>English</option><option>Hindi</option><option>Telugu</option></select></label><Toggle checked={handsFree} label="Prefer hands-free replies" detail="Voice replies remain conversational. The microphone never starts automatically." onChange={setHandsFree} /><div className={styles.deviceGuide}><Icon name="mic" /><div><h3>Before your first voice session</h3><p>Choose a quiet space, allow microphone access only when prompted, and use headphones where echo is noticeable.</p></div></div><div className={styles.formFooter}><p>{saved || "Open voice from any Buyer conversation"}</p><button type="submit">Save voice settings</button></div></form></SettingsSection>;
}

function SettingsSection({ title, description, children }: { title: string; description: string; children: React.ReactNode }) { return <section className={styles.section}><header><h2>{title}</h2><p>{description}</p></header>{children}</section>; }
function Field(props: React.InputHTMLAttributes<HTMLInputElement> & { label: string }) { const { label, ...input } = props; return <label className={styles.field}>{label}<input {...input} /></label>; }
function Toggle({ checked, label, detail, onChange }: { checked: boolean; label: string; detail: string; onChange: (checked: boolean) => void }) { return <label className={styles.toggle}><span><strong>{label}</strong><small>{detail}</small></span><input type="checkbox" checked={checked} onChange={(event) => onChange(event.target.checked)} /><i aria-hidden="true" /></label>; }
function addressFrom(data: FormData): AddressInput { return { label: String(data.get("label")), recipientName: String(data.get("recipientName")), phone: String(data.get("phone")), addressLine1: String(data.get("addressLine1")), addressLine2: String(data.get("addressLine2")) || null, locality: String(data.get("locality")), city: String(data.get("city")), state: String(data.get("state")), postalCode: String(data.get("postalCode")) }; }
