"use client";

import Link from "next/link";
import { createContext, useCallback, useContext, useEffect, useState, type FormEvent, type ReactNode } from "react";
import { buyerApi, BuyerApiError } from "@/lib/buyer/api";
import { configuredDemoBuyer } from "@/lib/buyer/buyer-experience";
import type { ActorSession } from "@/lib/buyer/types";
import { AmanaMark } from "./amana-mark";
import styles from "./buyer-shell.module.css";

type SessionContextValue = {
  actor: ActorSession | null;
  loading: boolean;
  refresh: () => Promise<void>;
  signIn: (identity: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
};

const SessionContext = createContext<SessionContextValue | null>(null);

export function BuyerSessionProvider({ children }: { children: ReactNode }) {
  const [actor, setActor] = useState<ActorSession | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    try {
      setActor(await buyerApi.me());
    } catch (error) {
      if (!(error instanceof BuyerApiError) || error.status !== 401) console.error(error);
      setActor(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    buyerApi.me().then(setActor).catch((error) => {
      if (!(error instanceof BuyerApiError) || error.status !== 401) console.error(error);
      setActor(null);
    }).finally(() => setLoading(false));
  }, []);

  async function signIn(identity: string, password: string) {
    const nextActor = await buyerApi.login(identity, password);
    setActor(nextActor);
  }

  async function signOut() {
    await buyerApi.logout();
    setActor(null);
  }

  return (
    <SessionContext.Provider value={{ actor, loading, refresh, signIn, signOut }}>
      {children}
    </SessionContext.Provider>
  );
}

export function useBuyerSession() {
  const value = useContext(SessionContext);
  if (!value) throw new Error("useBuyerSession must be used inside BuyerSessionProvider");
  return value;
}

export function BuyerAuthBoundary({ children }: { children: ReactNode }) {
  const { actor, loading, signIn, signOut } = useBuyerSession();
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [identity, setIdentity] = useState("");
  const [password, setPassword] = useState("");
  const demoBuyer = configuredDemoBuyer(
    process.env.NEXT_PUBLIC_DEMO_BUYER_IDENTITY,
    process.env.NEXT_PUBLIC_DEMO_BUYER_PASSWORD,
  );

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

  async function leaveWrongRole() {
    setSubmitting(true);
    setError("");
    try {
      await signOut();
      setIdentity("");
      setPassword("");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Sign out failed");
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) {
    return (
      <main className={styles.authPage} aria-busy="true">
        <div className={styles.loadingMark}><AmanaMark className={styles.brandMark} priority /></div>
        <p>Preparing your space…</p>
      </main>
    );
  }

  if (!actor) {
    return (
      <main className={styles.authPage}>
        <section className={styles.authCard} aria-labelledby="buyer-sign-in-title">
          <Link className={styles.authBrand} href="/" aria-label="Amana home">
            <AmanaMark className={styles.brandMark} priority /><span>Amana</span>
          </Link>
          <p className={styles.kicker}>Buyer access</p>
          <h1 id="buyer-sign-in-title">Welcome back.</h1>
          <p className={styles.authLead}>Sign in with your existing Amana buyer account.</p>
          <form className={styles.authForm} onSubmit={submit}>
            <label>Identity handle<input name="identity" autoComplete="username" required value={identity} onChange={(event) => setIdentity(event.target.value)} /></label>
            <label>Password<input name="password" type="password" autoComplete="current-password" required value={password} onChange={(event) => setPassword(event.target.value)} /></label>
            {error && <p className={styles.formError} role="alert">{error}</p>}
            <button className={styles.primaryButton} disabled={submitting} type="submit">
              {submitting ? "Signing in…" : "Enter Amana"}
            </button>
          </form>
          {demoBuyer && <section className={styles.demoAccess} aria-label="Demo access">
            <div><strong>Demo access</strong><span>Reviewer-ready Buyer account</span></div>
            <button type="button" onClick={() => { setIdentity(demoBuyer.identity); setPassword(demoBuyer.password); setError(""); }}>Use demo account</button>
            <p>Fills the form only. Continue with the normal secure sign-in.</p>
          </section>}
          <p className={styles.authFootnote}>Session security and identity are verified by the existing Amana backend.</p>
        </section>
      </main>
    );
  }

  if (actor.role !== "BUYER") {
    return (
      <main className={styles.authPage}>
        <section className={styles.authCard}>
          <p className={styles.kicker}>Buyer access</p>
          <h1>This account is not a buyer.</h1>
          <p className={styles.authLead}>Use a BUYER account to enter this part of Amana.</p>
          {error && <p className={styles.formError} role="alert">{error}</p>}
          <button className={styles.secondaryButton} disabled={submitting} onClick={() => void leaveWrongRole()} type="button">
            {submitting ? "Signing out…" : "Sign out"}
          </button>
        </section>
      </main>
    );
  }

  return children;
}
