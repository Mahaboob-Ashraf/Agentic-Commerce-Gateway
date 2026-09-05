"use client";

import { LockIcon, PasswordInput, TextInput } from "@razorpay/blade/components";
import Image from "next/image";
import Link from "next/link";
import {
  createContext,
  useContext,
  useEffect,
  useState,
  type FormEvent,
  type ReactNode,
} from "react";
import { AmanaButton } from "@/components/amana/blade";
import { merchantApi, MerchantApiError } from "@/lib/merchant/api";
import type { MerchantAccess, MerchantActor } from "@/lib/merchant/types";
import styles from "./merchant-session.module.css";

type SessionContextValue = {
  actor: MerchantActor | null;
  loading: boolean;
  merchantLoading: boolean;
  merchantError: string;
  merchants: MerchantAccess[];
  selectedMerchant: MerchantAccess | null;
  selectMerchant: (merchantId: string) => void;
  signIn: (identity: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
};

const SessionContext = createContext<SessionContextValue | null>(null);

export function MerchantSessionProvider({ children }: { children: ReactNode }) {
  const [actor, setActor] = useState<MerchantActor | null>(null);
  const [loading, setLoading] = useState(true);
  const [merchants, setMerchants] = useState<MerchantAccess[]>([]);
  const [selectedMerchant, setSelectedMerchant] = useState<MerchantAccess | null>(null);
  const [merchantLoading, setMerchantLoading] = useState(true);
  const [merchantError, setMerchantError] = useState("");

  useEffect(() => {
    merchantApi.me().then(setActor).catch((error) => {
      if (!(error instanceof MerchantApiError) || error.status !== 401) console.error(error);
      setActor(null);
      setMerchantLoading(false);
    }).finally(() => {
      setLoading(false);
    });
  }, []);

  useEffect(() => {
    if (!actor || actor.role !== "MERCHANT_ADMIN") return;
    let active = true;
    merchantApi.merchants().then((available) => {
      if (!active) return;
      const storedId = window.localStorage.getItem(`amana.merchant.selected.${actor.actorId}`);
      const selected = available.find((merchant) => merchant.merchantId === storedId) ?? available[0] ?? null;
      setMerchants(available);
      setSelectedMerchant(selected);
      setMerchantError("");
    }).catch((error) => {
      if (!active) return;
      setMerchants([]);
      setSelectedMerchant(null);
      setMerchantError(error instanceof Error ? error.message : "Merchant workspaces are unavailable");
    }).finally(() => {
      if (active) setMerchantLoading(false);
    });
    return () => { active = false; };
  }, [actor]);

  function selectMerchant(merchantId: string) {
    const selected = merchants.find((merchant) => merchant.merchantId === merchantId);
    if (!selected || !actor) return;
    window.localStorage.setItem(`amana.merchant.selected.${actor.actorId}`, selected.merchantId);
    setSelectedMerchant(selected);
  }

  async function signIn(identity: string, password: string) {
    setMerchantLoading(true);
    setActor(await merchantApi.login(identity, password));
  }

  async function signOut() {
    await merchantApi.logout();
    setActor(null);
    setMerchants([]);
    setSelectedMerchant(null);
    setMerchantLoading(false);
    setMerchantError("");
  }

  return (
    <SessionContext.Provider value={{
      actor,
      loading,
      merchantLoading,
      merchantError,
      merchants,
      selectedMerchant,
      selectMerchant,
      signIn,
      signOut,
    }}>
      {children}
    </SessionContext.Provider>
  );
}

export function useMerchantSession() {
  const value = useContext(SessionContext);
  if (!value) throw new Error("useMerchantSession must be used inside MerchantSessionProvider");
  return value;
}

export function MerchantAuthBoundary({ children }: { children: ReactNode }) {
  const { actor, loading, signIn, signOut } = useMerchantSession();
  const demoIdentity = process.env.NEXT_PUBLIC_DEMO_MERCHANT_IDENTITY?.trim() ?? "";
  const demoPassword = process.env.NEXT_PUBLIC_DEMO_MERCHANT_PASSWORD ?? "";
  const hasDemoAccess = Boolean(demoIdentity && demoPassword);
  const [identity, setIdentity] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      await signIn(identity, password);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Sign in failed");
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) {
    return (
      <main className={styles.authPage} aria-busy="true">
        <Image alt="Amana" className={styles.loadingMark} height={36} priority src="/amana/amana-mark.png" width={36} />
        <p>Verifying merchant access…</p>
      </main>
    );
  }

  if (!actor) {
    return (
      <main className={styles.authPage}>
        <section className={styles.authPanel} aria-labelledby="merchant-sign-in-title">
          <div className={styles.authNarrative}>
            <Link className={styles.brand} href="/" aria-label="Amana home">
              <Image alt="Amana" className={styles.brandMark} height={36} priority src="/amana/amana-mark.png" width={36} />
              <span>Amana</span>
            </Link>
            <p className={styles.kicker}>Merchant operations</p>
            <h1 id="merchant-sign-in-title">Prepare your store for trusted agentic commerce.</h1>
            <p>
              Give Amana your approved commerce sources. It inspects, maps, tests and prepares your
              store—without stepping outside the authority you define.
            </p>
            <div className={styles.trustLine}>
              <LockIcon size="small" />
              <span>Approved sources only. Readiness stays deterministic.</span>
            </div>
          </div>

          <form className={styles.authForm} onSubmit={submit}>
            <div>
              <p className={styles.formKicker}>Secure access</p>
              <h2>Sign in to Merchant Console</h2>
              <p>Use a provisioned Merchant Admin account.</p>
            </div>
            <TextInput
              autoCompleteSuggestionType="username"
              isRequired
              label="Identity handle"
              onChange={({ value }) => setIdentity(value ?? "")}
              placeholder="merchant@example.com"
              value={identity}
            />
            <PasswordInput
              isRequired
              label="Password"
              onChange={({ value }) => setPassword(value ?? "")}
              value={password}
            />
            {hasDemoAccess && (
              <section className={styles.demoAccess} aria-label="Demo access">
                <div>
                  <p>Demo access</p>
                  <strong title={demoIdentity}>{demoIdentity}</strong>
                  <span>Fills the real Merchant Admin sign-in form. Authentication is still required.</span>
                </div>
                <AmanaButton
                  onClick={() => {
                    setIdentity(demoIdentity);
                    setPassword(demoPassword);
                    setError("");
                  }}
                  type="button"
                  variant="secondary"
                >Use demo account</AmanaButton>
              </section>
            )}
            {error && <p className={styles.formError} role="alert">{error}</p>}
            <AmanaButton isFullWidth isLoading={submitting} type="submit">
              Sign in securely
            </AmanaButton>
            <p className={styles.formNote}>
              Session identity and Merchant Admin authority are verified by the Amana backend.
            </p>
          </form>
        </section>
      </main>
    );
  }

  if (actor.role !== "MERCHANT_ADMIN") {
    return (
      <main className={styles.authPage}>
        <section className={styles.roleNotice} aria-labelledby="merchant-role-title">
          <Image alt="Amana" className={styles.brandMark} height={36} priority src="/amana/amana-mark.png" width={36} />
          <p className={styles.kicker}>Merchant access</p>
          <h1 id="merchant-role-title">This account cannot administer a merchant.</h1>
          <p>
            Signed in as <strong>{actor.identityHandle}</strong>. Ask a platform administrator to
            provision Merchant Admin membership before continuing.
          </p>
          <AmanaButton onClick={() => void signOut()} variant="secondary">Sign out</AmanaButton>
        </section>
      </main>
    );
  }

  return children;
}
