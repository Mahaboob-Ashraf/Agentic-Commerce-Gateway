"use client";

import Link from "next/link";
import { createContext, useCallback, useContext, useEffect, useState, type FormEvent, type ReactNode } from "react";
import { buyerApi, BuyerApiError } from "@/lib/buyer/api";
import type { ActorSession } from "@/lib/buyer/types";
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
  const { actor, loading, signIn } = useBuyerSession();
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setSubmitting(true);
    setError("");
    try {
      await signIn(String(form.get("identity") ?? ""), String(form.get("password") ?? ""));
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Sign in failed");
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) {
    return (
      <main className={styles.authPage} aria-busy="true">
        <div className={styles.loadingMark}><span>A</span></div>
        <p>Preparing your space…</p>
      </main>
    );
  }

  if (!actor) {
    return (
      <main className={styles.authPage}>
        <section className={styles.authCard} aria-labelledby="buyer-sign-in-title">
          <Link className={styles.authBrand} href="/" aria-label="Amana home">
            <span className={styles.brandMark}>A</span><span>Amana</span>
          </Link>
          <p className={styles.kicker}>Buyer access</p>
          <h1 id="buyer-sign-in-title">Welcome back.</h1>
          <p className={styles.authLead}>Sign in with your existing Amana buyer account.</p>
          <form className={styles.authForm} onSubmit={submit}>
            <label>Identity handle<input name="identity" autoComplete="username" required /></label>
            <label>Password<input name="password" type="password" autoComplete="current-password" required /></label>
            {error && <p className={styles.formError} role="alert">{error}</p>}
            <button className={styles.primaryButton} disabled={submitting} type="submit">
              {submitting ? "Signing in…" : "Enter Amana"}
            </button>
          </form>
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
        </section>
      </main>
    );
  }

  return children;
}
