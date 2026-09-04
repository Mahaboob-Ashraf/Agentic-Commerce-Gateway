"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { buyerApi, BuyerApiError } from "@/lib/buyer/api";
import { deriveJourneySnapshot, SingleFlightGate } from "@/lib/buyer/commerce-state";
import type {
  AuthorizationDecision,
  CheckoutInitialization,
  CommerceRequestResult,
  CommerceThread,
  EvidenceOutcome,
  FulfillmentView,
  PaymentStateView,
  ThreadMessage,
} from "@/lib/buyer/types";
import { Icon } from "./icons";
import styles from "./workspace.module.css";

const BuyerVoice = lazy(() => import("./buyer-voice").then((module) => ({ default: module.BuyerVoice })));

const suggestions = [
  "Find Auralink Buds Bluetooth Earphones from Amazing",
  "Find wireless earphones under ₹3,500",
  "I need high-protein vegetarian snacks with no peanuts",
];

type RazorpayResponse = {
  razorpay_payment_id: string;
  razorpay_order_id: string;
  razorpay_signature: string;
};

type RazorpayInstance = { open: () => void };

declare global {
  interface Window {
    Razorpay?: new (options: {
      key: string;
      order_id: string;
      amount: number;
      currency: string;
      name: string;
      description: string;
      handler: (response: RazorpayResponse) => void;
      modal: { ondismiss: () => void };
      theme: { color: string };
    }) => RazorpayInstance;
  }
}

export function CommerceChat({ initialThreadId }: { initialThreadId?: string }) {
  const router = useRouter();
  const [threadId, setThreadId] = useState(initialThreadId ?? null);
  const [thread, setThread] = useState<CommerceThread | null>(null);
  const [messages, setMessages] = useState<ThreadMessage[]>([]);
  const [optimisticMessage, setOptimisticMessage] = useState("");
  const [result, setResult] = useState<CommerceRequestResult | null>(null);
  const [authorization, setAuthorization] = useState<AuthorizationDecision | null>(null);
  const [checkout, setCheckout] = useState<CheckoutInitialization | null>(null);
  const [payment, setPayment] = useState<PaymentStateView | null>(null);
  const [fulfillment, setFulfillment] = useState<FulfillmentView | null>(null);
  const [callbackAccepted, setCallbackAccepted] = useState(false);
  const [value, setValue] = useState("");
  const [loading, setLoading] = useState(Boolean(initialThreadId));
  const [sending, setSending] = useState(false);
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [voiceOpen, setVoiceOpen] = useState(false);
  const actionGate = useRef(new SingleFlightGate());
  const pendingSubmission = useRef<{ requestId: string; text: string; threadId: string | null } | null>(null);
  const checkoutButton = useRef<HTMLButtonElement>(null);
  const voiceButton = useRef<HTMLButtonElement>(null);
  const endMarker = useRef<HTMLDivElement>(null);
  const userNearBottom = useRef(true);

  const snapshot = useMemo(
    () => deriveJourneySnapshot(result, authorization, Boolean(checkout), callbackAccepted, payment, fulfillment),
    [authorization, callbackAccepted, checkout, fulfillment, payment, result],
  );

  const hydratePayment = useCallback(async (nextResult: CommerceRequestResult) => {
    setAuthorization(null);
    setCheckout(null);
    setPayment(null);
    setFulfillment(null);
    setCallbackAccepted(false);
    const proposalId = nextResult.transactionProposalId;
    if (!proposalId) return;
    const ownedThreadId = nextResult.threadId;
    const nextAuthorization = await optional(() => buyerApi.authorization(ownedThreadId, proposalId));
    setAuthorization(nextAuthorization);
    const nextPayment = await optional(() => buyerApi.payment(ownedThreadId, proposalId));
    setPayment(nextPayment);
    if (nextPayment?.providerOrderId) {
      setCheckout(await optional(() => buyerApi.checkout(ownedThreadId, proposalId)));
    }
    if (nextPayment?.paymentState === "PAYMENT_CONFIRMED") {
      setFulfillment(await optional(() => buyerApi.fulfillment(ownedThreadId, proposalId)));
    }
  }, []);

  const reloadThread = useCallback(async (id: string) => {
    const [nextThread, nextMessages] = await Promise.all([buyerApi.thread(id), buyerApi.messages(id)]);
    setThread(nextThread);
    setMessages(nextMessages);
    const latest = await optional(() => buyerApi.latestCommerceRequest(id));
    setResult(latest);
    if (latest) await hydratePayment(latest);
  }, [hydratePayment]);

  useEffect(() => {
    if (!initialThreadId) return;
    // Re-entry deliberately hydrates persisted authority after the route is mounted.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    reloadThread(initialThreadId).catch((caught: Error) => setError(caught.message)).finally(() => setLoading(false));
  }, [initialThreadId, reloadThread]);

  useEffect(() => {
    const onScroll = () => {
      const distance = document.documentElement.scrollHeight - window.scrollY - window.innerHeight;
      userNearBottom.current = distance < 180;
    };
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  useEffect(() => {
    if (userNearBottom.current) endMarker.current?.scrollIntoView({ behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth", block: "end" });
  }, [busyAction, messages, optimisticMessage, result, snapshot.phase]);

  const runCommerceRequest = useCallback(async (text: string, restoreToComposer = false) => {
    const normalized = text.trim();
    if (!normalized || !actionGate.current.enter("send")) {
      throw new Error("A commerce request is already in progress.");
    }
    setSending(true);
    setError("");
    setNotice("");
    if (restoreToComposer) {
      setOptimisticMessage(normalized);
      setValue("");
    }
    try {
      const pending = pendingSubmission.current?.text === normalized && pendingSubmission.current.threadId === threadId
        ? pendingSubmission.current
        : { requestId: crypto.randomUUID(), text: normalized, threadId };
      pendingSubmission.current = pending;
      const next = await buyerApi.createCommerceRequest(pending.requestId, normalized, threadId ?? undefined);
      pendingSubmission.current = null;
      setResult(next);
      setThreadId(next.threadId);
      if (!initialThreadId) router.replace(`/buyer/chat/${next.threadId}`);
      try {
        await reloadThread(next.threadId);
      } catch (reloadError) {
        setError(reloadError instanceof Error ? reloadError.message : "The saved conversation could not be refreshed.");
      }
      return next;
    } catch (caught) {
      if (restoreToComposer) setValue(normalized);
      setError(caught instanceof Error ? caught.message : "Amana could not complete this request.");
      setNotice("Your request is restored. Send it again to retry with the same durable request identity.");
      throw caught;
    } finally {
      setOptimisticMessage("");
      setSending(false);
      actionGate.current.leave("send");
    }
  }, [initialThreadId, reloadThread, router, threadId]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const text = value.trim();
    if (!text) return;
    try {
      await runCommerceRequest(text, true);
    } catch {
      // runCommerceRequest restores the text and presents the bounded failure.
    }
  }

  async function authorizeAndOpen() {
    if (!result?.transactionProposalId || !actionGate.current.enter("authorize")) return;
    const proposalId = result.transactionProposalId;
    setBusyAction("authorize");
    setError("");
    setNotice("Binding your authorization to this exact proposal…");
    try {
      let currentAuthorization = authorization;
      if (!currentAuthorization) {
        currentAuthorization = await buyerApi.authorize(result.threadId, proposalId);
        setAuthorization(currentAuthorization);
      }
      setNotice("Preparing Razorpay Checkout…");
      const gate = await buyerApi.reserveExecution(result.threadId, proposalId);
      if (gate.decision !== "ALLOW" || !gate.execution) throw new Error("The authorized execution was not allowed.");
      const nextPayment = await buyerApi.createPaymentOrder(result.threadId, proposalId);
      setPayment(nextPayment);
      const nextCheckout = await buyerApi.checkout(result.threadId, proposalId);
      setCheckout(nextCheckout);
      await openCheckout(nextCheckout);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Checkout could not be prepared.");
    } finally {
      setBusyAction(null);
      actionGate.current.leave("authorize");
    }
  }

  async function openPreparedCheckout() {
    if (!checkout || !actionGate.current.enter("checkout")) return;
    setBusyAction("checkout");
    setError("");
    try {
      await openCheckout(checkout);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Razorpay Checkout could not open.");
    } finally {
      setBusyAction(null);
      actionGate.current.leave("checkout");
    }
  }

  async function openCheckout(initialization: CheckoutInitialization) {
    await loadRazorpay();
    if (!window.Razorpay || !result?.transactionProposalId) throw new Error("Razorpay Checkout is unavailable.");
    setNotice("Razorpay Checkout is open. Your payment is not confirmed until provider verification completes.");
    const instance = new window.Razorpay({
      key: initialization.publicKeyId,
      order_id: initialization.providerOrderId,
      amount: initialization.amountMinor,
      currency: initialization.currency,
      name: initialization.merchantDisplayName,
      description: "Amana authorized purchase",
      handler: (response) => void handleCallback(response),
      modal: {
        ondismiss: () => {
          setNotice("Checkout closed. No payment failure has been recorded; you can resume this prepared checkout.");
          window.setTimeout(() => checkoutButton.current?.focus(), 0);
        },
      },
      theme: { color: "#2b5ff5" },
    });
    instance.open();
  }

  async function handleCallback(response: RazorpayResponse) {
    if (!result?.transactionProposalId || !actionGate.current.enter("callback")) return;
    setBusyAction("callback");
    setNotice("Payment submitted. Verifying with Razorpay…");
    setCallbackAccepted(true);
    try {
      const submission = {
        razorpayPaymentId: response.razorpay_payment_id,
        razorpayOrderId: response.razorpay_order_id,
        razorpaySignature: response.razorpay_signature,
      };
      let callback;
      try {
        callback = await buyerApi.submitPaymentCallback(result.threadId, result.transactionProposalId, submission);
      } catch (firstFailure) {
        if (firstFailure instanceof BuyerApiError && firstFailure.status < 500) throw firstFailure;
        callback = await buyerApi.submitPaymentCallback(result.threadId, result.transactionProposalId, submission);
      }
      if (!callback.accepted) throw new Error("Razorpay callback evidence was not accepted.");
      setPayment(await buyerApi.payment(result.threadId, result.transactionProposalId));
      await reconcile();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Payment evidence could not be submitted.");
      setNotice("Payment status is still being verified. Do not pay again yet.");
    } finally {
      setBusyAction(null);
      actionGate.current.leave("callback");
      window.setTimeout(() => checkoutButton.current?.focus(), 0);
    }
  }

  async function reconcile() {
    if (!result?.transactionProposalId || !actionGate.current.enter("reconcile")) return;
    setBusyAction("reconcile");
    setNotice("Verifying with Razorpay…");
    try {
      let current: PaymentStateView | null = payment;
      for (let attempt = 0; attempt < 4; attempt += 1) {
        await waitUntilVisible();
        const reconciliation = await buyerApi.reconcilePayment(result.threadId, result.transactionProposalId);
        current = reconciliation.state;
        setPayment(current);
        if (current.paymentState === "PAYMENT_CONFIRMED" || current.paymentState === "PAYMENT_FAILED" || reconciliation.reconciliationStatus === "MANUAL_REVIEW") break;
        if (attempt < 3) await delay(2000);
      }
      if (current?.paymentState === "PAYMENT_CONFIRMED") {
        setNotice("Payment verified. Creating your order…");
        await pollFulfillment(result.threadId, result.transactionProposalId);
      } else {
        setNotice("Payment status is still being verified. Do not pay again yet.");
      }
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Payment verification is temporarily unavailable.");
      setNotice("Payment status is still being verified. Do not pay again yet.");
    } finally {
      setBusyAction(null);
      actionGate.current.leave("reconcile");
    }
  }

  async function pollFulfillment(ownedThreadId: string, proposalId: string) {
    for (let attempt = 0; attempt < 15; attempt += 1) {
      await waitUntilVisible();
      const next = await buyerApi.fulfillment(ownedThreadId, proposalId);
      setFulfillment(next);
      if (["FULFILLED", "TERMINAL_FAILURE", "COMPENSATION_REQUIRED"].includes(next.fulfillmentState)) return;
      if (attempt < 14) await delay(1000);
    }
  }

  const showWelcome = !loading && messages.length === 0 && !optimisticMessage && !result;

  return (
    <div className={styles.commerceChat} aria-busy={loading || sending || Boolean(busyAction)}>
      {thread && (
        <header className={styles.commerceHeader}>
          <div><p className={styles.eyebrow}>Buyer conversation</p><h1>{thread.title}</h1></div>
          <Link href="/buyer/conversations">All conversations</Link>
        </header>
      )}

      <section className={styles.chatStream} aria-label="Conversation">
        {voiceOpen && <Suspense fallback={<div className={styles.voiceLoading}>Preparing secure voice…</div>}><BuyerVoice onClose={() => { setVoiceOpen(false); window.setTimeout(() => voiceButton.current?.focus(), 0); }} onCommerceRequest={runCommerceRequest} /></Suspense>}
        {showWelcome && <Welcome value={value} setValue={setValue} />}
        {loading && <AmanaBubble><Activity text="Reconstructing the latest authoritative state…" /></AmanaBubble>}
        {messages.map((message) => <UserBubble key={message.messageId} text={message.normalizedText} time={message.createdAt} />)}
        {optimisticMessage && <UserBubble text={optimisticMessage} pending />}
        {sending && <AmanaBubble><Activity text="Amana is checking this request through Safe Buyer…" /></AmanaBubble>}
        {result && !sending && <CommerceResponse result={result} snapshot={snapshot} authorization={authorization} checkout={checkout} payment={payment} fulfillment={fulfillment} busy={Boolean(busyAction)} onAuthorize={() => void authorizeAndOpen()} onOpenCheckout={() => void openPreparedCheckout()} onReconcile={() => void reconcile()} checkoutButton={checkoutButton} />}
        {notice && <div className={styles.liveNotice} aria-live="polite">{notice}</div>}
        {error && <div className={styles.chatError} role="alert"><span>{error}</span>{threadId && <button type="button" onClick={() => void reloadThread(threadId)}>Reload state</button>}</div>}
        <div ref={endMarker} />
      </section>

      <form className={styles.commerceComposer} onSubmit={submit}>
        <textarea aria-label="Message Amana" onChange={(event) => setValue(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); event.currentTarget.form?.requestSubmit(); } }} placeholder="Tell Amana what you’re looking for…" value={value} disabled={sending} />
        <div>
          <button ref={voiceButton} className={styles.voiceButton} type="button" aria-expanded={voiceOpen} aria-label={voiceOpen ? "Voice mode is open" : "Open voice mode"} onClick={() => setVoiceOpen(true)} disabled={voiceOpen}><Icon name="mic" /></button>
          <span>{sending ? "Working from live merchant and catalogue state" : "Enter to send · Shift+Enter for a new line"}</span>
          <button className={styles.sendButton} disabled={!value.trim() || sending} type="submit" aria-label="Send request"><Icon name="arrow" /></button>
        </div>
      </form>
    </div>
  );
}

function Welcome({ value, setValue }: { value: string; setValue: (value: string) => void }) {
  return <section className={styles.emptyState} aria-labelledby="new-chat-title">
    <div className={styles.orb} aria-hidden="true" />
    <p className={styles.eyebrow}>A thoughtful place to begin</p>
    <h1 id="new-chat-title">What can Amana find for you?</h1>
    <p className={styles.emptyLead}>Describe the product, budget, or safety requirements that matter. Amana will ground the answer before anything moves forward.</p>
    <div className={styles.promptList} aria-label="Example prompts">{suggestions.map((suggestion) => <button key={suggestion} aria-pressed={value === suggestion} onClick={() => setValue(suggestion)} type="button">{suggestion}</button>)}</div>
  </section>;
}

function UserBubble({ text, time, pending = false }: { text: string; time?: string; pending?: boolean }) {
  return <article className={styles.userBubble}><p>{text}</p><span>{pending ? "Sending…" : time ? formatDate(time) : ""}</span></article>;
}

function AmanaBubble({ children }: { children: React.ReactNode }) {
  return <article className={styles.amanaBubble}><span className={styles.amanaMark} aria-hidden="true">A</span><div>{children}</div></article>;
}

function CommerceResponse({ result, snapshot, authorization, checkout, payment, fulfillment, busy, onAuthorize, onOpenCheckout, onReconcile, checkoutButton }: {
  result: CommerceRequestResult;
  snapshot: ReturnType<typeof deriveJourneySnapshot>;
  authorization: AuthorizationDecision | null;
  checkout: CheckoutInitialization | null;
  payment: PaymentStateView | null;
  fulfillment: FulfillmentView | null;
  busy: boolean;
  onAuthorize: () => void;
  onOpenCheckout: () => void;
  onReconcile: () => void;
  checkoutButton: React.RefObject<HTMLButtonElement | null>;
}) {
  const blocked = result.constraintOverall === "FAIL" || result.constraintOverall === "UNKNOWN" || result.riskOutcome === "BLOCK";
  const noMatch = result.clarificationRequired || (!result.failureCode && result.products.length === 0);
  return <AmanaBubble>
    <div className={styles.responseIntro}>
      <p>{result.clarificationRequired ? (result.clarificationQuestion ?? "I couldn’t verify an exact product match.") : result.failureCode ? "I couldn’t safely complete this request. Nothing was authorized." : result.transactionProposalId ? "I found a grounded option and prepared the exact purchase for your review." : "Here is the latest verified result."}</p>
      {result.progress.length > 0 && <ol className={styles.progressList}>{result.progress.map((step) => <li key={step.code}><span aria-hidden="true">✓</span>{step.label}</li>)}</ol>}
    </div>

    {noMatch && <section className={styles.noMatch}><h2>I couldn’t verify an exact product match.</h2><p>I haven’t substituted a similar item. Add a category, exact SKU/GTIN, or a specific brand and model to narrow the request.</p></section>}

    {result.products.length > 0 && <section className={styles.results} aria-labelledby={`results-${result.requestId}`}>
      <div className={styles.sectionHeading}><div><p>Hybrid Retrieval · RAG + Lexical</p><h2 id={`results-${result.requestId}`}>Grounded product</h2></div><span>{result.merchantDisplayName ?? "Connected merchant"}</span></div>
      <p className={styles.retrievalNote}>Matches meaning as well as exact product terms across connected merchants.</p>
      {result.products.map((product) => <article className={styles.productRow} key={product.productId}>
        <ProductImage name={product.productName} image={authoritativeImage(product.facts)} />
        <div className={styles.productCopy}><h3>{product.productName}</h3><p>{[product.brand, product.variant, product.colour, product.sizeStorage].filter(Boolean).join(" · ")}</p><dl><div><dt>SKU</dt><dd>{product.merchantSku}</dd></div><div><dt>Quantity</dt><dd>{product.quantity}</dd></div>{product.facts.filter((fact) => fact.type !== "IMAGE").slice(0, 3).map((fact) => <div key={fact.factId}><dt>{friendly(fact.type)}</dt><dd>{factValue(fact.value)}</dd></div>)}</dl></div>
        <strong>{money(product.unitAmountMinor, result.authoritativeCurrency)}</strong>
      </article>)}
    </section>}

    {(result.products.length > 0 || result.constraints.length > 0) && <details className={styles.safetyPanel}>
      <summary><span>Why this is safe</span><small>Deterministic Guardrails</small></summary>
      <div className={styles.evidenceRows}>
        <Evidence label="Exact product grounded" outcome={result.products.length > 0 ? "PASS" : "UNKNOWN"} />
        <Evidence label="Current price checked" outcome={result.authoritativeFinalAmountMinor != null ? "PASS" : "UNKNOWN"} />
        <Evidence label="Availability checked" outcome={result.availabilityOutcome ?? "UNKNOWN"} detail={result.availabilityReasonCode} />
        <Evidence label="Delivery checked" outcome={result.serviceabilityOutcome ?? "UNKNOWN"} detail={result.serviceabilityReasonCode} />
        {result.constraints.map((constraint) => <Evidence key={constraint.key} label={friendly(constraint.key)} outcome={constraint.result} detail={constraint.safetyCritical ? "Safety-critical" : undefined} />)}
      </div>
      {blocked && <p className={styles.blockedCopy}>This purchase cannot proceed while a required fact is failed or unresolved.</p>}
    </details>}

    {result.cartId && result.products.length > 0 && <section className={styles.cartPanel}>
      <div className={styles.sectionHeading}><div><p>Candidate cart</p><h2>{result.merchantDisplayName}</h2></div><span>Current quote</span></div>
      <ul>{result.products.map((product) => <li key={product.productId}><span>{product.quantity} × {product.productName}</span><strong>{money(product.lineAmountMinor, result.authoritativeCurrency)}</strong></li>)}</ul>
      <dl className={styles.totals}><div><dt>Subtotal</dt><dd>{money(result.subtotalMinor, result.authoritativeCurrency)}</dd></div><div><dt>Delivery</dt><dd>{money(result.deliveryMinor, result.authoritativeCurrency)}</dd></div>{Boolean(result.taxMinor) && <div><dt>Tax</dt><dd>{money(result.taxMinor, result.authoritativeCurrency)}</dd></div>}{Boolean(result.feesMinor) && <div><dt>Fees</dt><dd>{money(result.feesMinor, result.authoritativeCurrency)}</dd></div>}<div className={styles.totalLine}><dt>Current total</dt><dd>{money(result.authoritativeFinalAmountMinor, result.authoritativeCurrency)}</dd></div></dl>
    </section>}

    {result.transactionProposalId && <section className={styles.proposal} data-blocked={!result.paymentReady}>
      <p className={styles.proposalKicker}>Exact action for authorization</p>
      <h2>Purchase from {result.merchantDisplayName}</h2>
      <p className={styles.proposalAmount}>{money(result.authoritativeFinalAmountMinor, result.authoritativeCurrency)}</p>
      <ul>{result.products.map((product) => <li key={product.productId}>{product.quantity} × {product.productName} <span>{product.merchantSku}</span></li>)}</ul>
      <div className={styles.proposalMeta}><span>Proposal {shortId(result.transactionProposalId)}</span><span>{result.proposalExpiresAt ? `Expires ${formatDate(result.proposalExpiresAt)}` : "Expiry unavailable"}</span></div>
      {!authorization && <button className={styles.authorizeButton} disabled={!snapshot.canAuthorize || busy} onClick={onAuthorize} type="button" aria-label={`Authorize purchase for ${money(result.authoritativeFinalAmountMinor, result.authoritativeCurrency)}`}>{busy ? "Preparing…" : "Authorize purchase"}</button>}
      {authorization?.decision === "AUTHORIZED" && !checkout && <button className={styles.authorizeButton} disabled={busy} onClick={onAuthorize} type="button">Prepare secure checkout</button>}
      {checkout && snapshot.canOpenCheckout && <button ref={checkoutButton} className={styles.authorizeButton} disabled={busy} onClick={onOpenCheckout} type="button">Open Razorpay Checkout</button>}
      {authorization?.decision === "DENIED" && <p className={styles.blockedCopy}>You declined this proposal. It will not be executed.</p>}
      {!result.paymentReady && !authorization && <p className={styles.blockedCopy}>Authorization is unavailable because the current proposal did not pass every required authority check.</p>}
    </section>}

    {(checkout || payment || fulfillment) && <PaymentPanel snapshot={snapshot} payment={payment} fulfillment={fulfillment} busy={busy} onReconcile={onReconcile} />}
  </AmanaBubble>;
}

function PaymentPanel({ snapshot, payment, fulfillment, busy, onReconcile }: { snapshot: ReturnType<typeof deriveJourneySnapshot>; payment: PaymentStateView | null; fulfillment: FulfillmentView | null; busy: boolean; onReconcile: () => void }) {
  const paymentCopy: Partial<Record<typeof snapshot.phase, [string, string]>> = {
    CHECKOUT_READY: ["Checkout prepared", "Razorpay Checkout is ready for this one authorized execution."],
    PAYMENT_SUBMITTED: ["Payment submitted", "Verifying with Razorpay. A browser callback is evidence, not confirmation."],
    PAYMENT_UNCERTAIN: ["Payment status is still being verified", "Do not pay again. Amana will only confirm after authoritative provider reconciliation."],
    PAYMENT_FAILED: ["Payment not confirmed", "The provider state did not confirm payment. No order is shown as fulfilled."],
    PAYMENT_VERIFIED: ["Payment verified", fulfillment?.fulfillmentState === "COMPENSATION_REQUIRED" ? "Merchant finalization needs compensation handling. No refund claim is being invented here." : "Merchant finalization needs attention; your payment remains verified."],
    FINALIZING: ["Payment verified", "Creating your order through the merchant’s approved capability…"],
    FULFILLED: ["Order fulfilled", fulfillment?.merchantOrderId ? `Merchant order ${fulfillment.merchantOrderId}` : "The merchant has confirmed fulfillment."],
  };
  const copy = paymentCopy[snapshot.phase];
  if (!copy) return null;
  return <section className={styles.paymentPanel} data-phase={snapshot.phase} aria-live="polite"><span className={styles.paymentIcon} aria-hidden="true">{snapshot.phase === "FULFILLED" ? "✓" : "•"}</span><div><h2>{copy[0]}</h2><p>{copy[1]}</p>{payment && <small>Provider order {shortId(payment.providerOrderId ?? "pending")} · {money(payment.amountMinor, payment.currency)}</small>}</div>{snapshot.canReconcile && <button disabled={busy} onClick={onReconcile} type="button">{busy ? "Verifying…" : "Verify payment status"}</button>}</section>;
}

function Evidence({ label, outcome, detail }: { label: string; outcome: EvidenceOutcome; detail?: string | null }) {
  return <div className={styles.evidenceRow} data-outcome={outcome}><span aria-hidden="true">{outcome === "PASS" ? "✓" : outcome === "FAIL" ? "×" : "?"}</span><div><strong>{label}</strong>{detail && <small>{friendly(detail)}</small>}</div><em>{outcome}</em></div>;
}

function ProductImage({ name, image }: { name: string; image: string | null }) {
  return <div className={styles.productGlyph}><span aria-hidden="true">{name.slice(0, 1)}</span>{image && (
    // Authoritative catalogue image hosts are dynamic; a fixed Next Image allowlist would reject valid merchants.
    // eslint-disable-next-line @next/next/no-img-element
    <img src={image} alt={name} loading="lazy" referrerPolicy="no-referrer" onError={(event) => event.currentTarget.remove()} />
  )}</div>;
}

function Activity({ text }: { text: string }) { return <div className={styles.activity} aria-live="polite"><span aria-hidden="true" /><span aria-hidden="true" /><span aria-hidden="true" /><p>{text}</p></div>; }

async function optional<T>(read: () => Promise<T>): Promise<T | null> {
  try { return await read(); } catch (error) {
    if (error instanceof BuyerApiError && [404, 409].includes(error.status)) return null;
    throw error;
  }
}

let razorpayLoader: Promise<void> | null = null;
function loadRazorpay() {
  if (window.Razorpay) return Promise.resolve();
  if (razorpayLoader) return razorpayLoader;
  razorpayLoader = new Promise<void>((resolve, reject) => {
    const script = document.createElement("script");
    script.src = "https://checkout.razorpay.com/v1/checkout.js";
    script.async = true;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error("Razorpay Checkout could not be loaded."));
    document.head.appendChild(script);
  });
  return razorpayLoader;
}

function waitUntilVisible() {
  if (document.visibilityState === "visible") return Promise.resolve();
  return new Promise<void>((resolve) => {
    const visible = () => { if (document.visibilityState === "visible") { document.removeEventListener("visibilitychange", visible); resolve(); } };
    document.addEventListener("visibilitychange", visible);
  });
}

function delay(milliseconds: number) { return new Promise((resolve) => window.setTimeout(resolve, milliseconds)); }
function shortId(value: string) { return value.length > 12 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value; }
function friendly(value: string) { return value.toLowerCase().replaceAll("_", " ").replace(/^./, (letter) => letter.toUpperCase()); }
function factValue(value: unknown) { if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") return String(value); return "Verified merchant fact"; }
function authoritativeImage(facts: CommerceRequestResult["products"][number]["facts"]) { const value = facts.find((fact) => fact.type === "IMAGE")?.value; return typeof value === "string" && (value.startsWith("https://") || value.startsWith("/")) ? value : null; }
function money(value: number | null, currency: string | null) { if (value == null || !currency) return "Not verified"; return new Intl.NumberFormat("en-IN", { style: "currency", currency }).format(value / 100); }
function formatDate(value: string) { return new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short", hour: "numeric", minute: "2-digit" }).format(new Date(value)); }
