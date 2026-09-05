"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { IconButton, ImageIcon, MicIcon, SendIcon, Tooltip } from "@razorpay/blade/components";
import { buyerApi, BuyerApiError } from "@/lib/buyer/api";
import { boundedComposerHeight, commerceThreadHint, initialBuyerInputMode, reduceBuyerInputMode, shouldRenderVoiceOrb, type BuyerInputMode } from "@/lib/buyer/buyer-experience";
import { deriveJourneySnapshot, isSubmissionBusy, isUserMessageSending, SingleFlightGate, type CommerceSubmissionPhase } from "@/lib/buyer/commerce-state";
import { BUYER_IMAGE_ACCEPT, validateBuyerImage } from "@/lib/buyer/image-upload";
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
import { MerchantLogo } from "./merchant-logo";
import styles from "./workspace.module.css";

const BuyerVoice = lazy(() => import("./buyer-voice").then((module) => ({ default: module.BuyerVoice })));

type BuyerImageAttachment = { file: File; previewUrl: string };

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
      image?: string;
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
  const [callbackProposalId, setCallbackProposalId] = useState<string | null>(null);
  const [value, setValue] = useState("");
  const [selectedImage, setSelectedImage] = useState<BuyerImageAttachment | null>(null);
  const [sentImagePreview, setSentImagePreview] = useState<{ threadId: string; previewUrl: string } | null>(null);
  const [loading, setLoading] = useState(Boolean(initialThreadId));
  const [submissionPhase, setSubmissionPhase] = useState<CommerceSubmissionPhase>("IDLE");
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [inputMode, setInputMode] = useState<BuyerInputMode>(() => initialBuyerInputMode(initialThreadId));
  const actionGate = useRef(new SingleFlightGate());
  const activeCommerceCompletion = useRef<Promise<void> | null>(null);
  const pendingSubmission = useRef<{ requestId: string; text: string; threadId: string | null; imageKey: string | null } | null>(null);
  const threadIdRef = useRef(threadId);
  const inputModeRef = useRef(inputMode);
  const checkoutButton = useRef<HTMLButtonElement>(null);
  const voiceButton = useRef<HTMLSpanElement>(null);
  const composer = useRef<HTMLTextAreaElement>(null);
  const imageInput = useRef<HTMLInputElement>(null);
  const endMarker = useRef<HTMLDivElement>(null);
  const userNearBottom = useRef(true);

  const snapshot = useMemo(
    () => deriveJourneySnapshot(result, authorization, Boolean(checkout), callbackProposalId === result?.transactionProposalId, payment, fulfillment),
    [authorization, callbackProposalId, checkout, fulfillment, payment, result],
  );
  const sending = isSubmissionBusy(submissionPhase);

  useEffect(() => { threadIdRef.current = threadId; }, [threadId]);
  useEffect(() => { inputModeRef.current = inputMode; }, [inputMode]);

  const hydratePayment = useCallback(async (nextResult: CommerceRequestResult) => {
    setAuthorization(null);
    setCheckout(null);
    setPayment(null);
    setFulfillment(null);
    setCallbackProposalId(null);
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
    if (latest?.requestStatus === "RUNNING") {
      setSubmissionPhase("PROCESSING");
      try {
        const recovered = await recoverCommerceRequest(latest.requestId);
        setResult(recovered);
        await hydratePayment(recovered);
      } finally {
        setSubmissionPhase("IDLE");
      }
    } else if (latest) await hydratePayment(latest);
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

  useEffect(() => {
    const field = composer.current;
    if (!field) return;
    field.style.height = "48px";
    const height = boundedComposerHeight(value ? field.scrollHeight : 48);
    field.style.height = `${height}px`;
    field.style.overflowY = field.scrollHeight > height ? "auto" : "hidden";
  }, [value]);

  useEffect(() => {
    const changeMode = (event: Event) => {
      const mode = (event as CustomEvent<{ mode?: BuyerInputMode }>).detail?.mode;
      if (mode === "VOICE" || mode === "TEXT") {
        setInputMode((current) => reduceBuyerInputMode(current, mode === "VOICE" ? "VOICE_SELECTED" : "TEXT_SELECTED"));
      }
    };
    window.addEventListener("amana:buyer-mode", changeMode);
    return () => window.removeEventListener("amana:buyer-mode", changeMode);
  }, []);

  const runCommerceRequest = useCallback(async (text: string, restoreToComposer = false, attachment?: BuyerImageAttachment) => {
    const normalized = text.trim() || (attachment ? "Find products like this" : "");
    if (!normalized) throw new Error("A commerce request needs a product, image, or shopping goal.");

    while (!actionGate.current.enter("send")) {
      // Typed duplicate submissions remain bounded. A Live correction, however, is a
      // distinct user turn: wait for the active durable request, then compile the
      // corrected payload against the now-current thread instead of dropping it.
      if (restoreToComposer || !activeCommerceCompletion.current) {
        throw new Error("A commerce request is already in progress.");
      }
      await activeCommerceCompletion.current;
    }
    let releaseCommerceCompletion: () => void = () => undefined;
    const ownedCompletion = new Promise<void>((resolve) => { releaseCommerceCompletion = resolve; });
    activeCommerceCompletion.current = ownedCompletion;
    setSubmissionPhase("SUBMITTING");
    setError("");
    setNotice("");
    if (restoreToComposer) {
      setOptimisticMessage(normalized);
      setValue("");
    }
    try {
      const currentThreadId = threadIdRef.current;
      const imageKey = attachment ? `${attachment.file.name}:${attachment.file.size}:${attachment.file.lastModified}:${attachment.file.type}` : null;
      const pending = pendingSubmission.current?.text === normalized && pendingSubmission.current.threadId === currentThreadId && pendingSubmission.current.imageKey === imageKey
        ? pendingSubmission.current
        : { requestId: crypto.randomUUID(), text: normalized, threadId: currentThreadId, imageKey };
      pendingSubmission.current = pending;
      let next: CommerceRequestResult;
      let requestSettled = false;
      try {
        const activeRequest = attachment
          ? buyerApi.createVisualCommerceRequest(pending.requestId, attachment.file, text.trim(), commerceThreadHint(currentThreadId))
          : buyerApi.createCommerceRequest(pending.requestId, normalized, commerceThreadHint(currentThreadId));
        void observeCommerceAcceptance(
          pending.requestId,
          () => setSubmissionPhase("PROCESSING"),
          () => requestSettled,
        );
        next = await activeRequest;
      } catch (caught) {
        if (!(caught instanceof BuyerApiError) || caught.code !== "COMMERCE_REQUEST_RUNNING") throw caught;
        setSubmissionPhase("PROCESSING");
        setNotice("This request is already running. Reattached to its durable progress.");
        next = await recoverCommerceRequest(pending.requestId);
      } finally {
        requestSettled = true;
      }
      pendingSubmission.current = null;
      setResult(next);
      setThreadId(next.threadId);
      threadIdRef.current = next.threadId;
      if (attachment) setSentImagePreview({ threadId: next.threadId, previewUrl: attachment.previewUrl });
      if (!initialThreadId) {
        const nextPath = `/buyer/chat/${next.threadId}`;
        // Keep the fresh-chat route stable while Live owns the conversation. Native History API
        // updates are observed by Next and can replace the page tree, unmounting the voice runtime.
        if (inputModeRef.current !== "VOICE") router.replace(nextPath);
      }
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
      setSubmissionPhase("IDLE");
      actionGate.current.leave("send");
      if (activeCommerceCompletion.current === ownedCompletion) activeCommerceCompletion.current = null;
      releaseCommerceCompletion();
    }
  }, [initialThreadId, reloadThread, router]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const text = value.trim();
    if (!text && !selectedImage) return;
    try {
      const completed = await runCommerceRequest(text, true, selectedImage ?? undefined);
      if (completed.failureCode) {
        setValue(text);
        return;
      }
      setSelectedImage(null);
    } catch {
      // runCommerceRequest restores the text and presents the bounded failure.
    }
  }

  async function chooseImage(file: File | undefined) {
    if (!file) return;
    try {
      await validateBuyerImage(file);
      if (selectedImage) URL.revokeObjectURL(selectedImage.previewUrl);
      setSelectedImage({ file, previewUrl: URL.createObjectURL(file) });
      setError("");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "The selected image is not supported.");
      if (imageInput.current) imageInput.current.value = "";
    }
  }

  function removeSelectedImage() {
    if (selectedImage) URL.revokeObjectURL(selectedImage.previewUrl);
    setSelectedImage(null);
    if (imageInput.current) imageInput.current.value = "";
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
      image: result.merchantLogoUrl ?? undefined,
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
    setCallbackProposalId(result.transactionProposalId);
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

  const lastVisualMessageId = messages.findLast((message) => message.inputSource === "IMAGE_TEXT")?.messageId;
  const conversation = <>
    {loading && <AmanaBubble><Activity text="Restoring the latest verified state…" /></AmanaBubble>}
    {messages.map((message) => <UserBubble key={message.messageId} text={message.normalizedText} time={message.createdAt} image={message.messageId === lastVisualMessageId && sentImagePreview?.threadId === threadId ? sentImagePreview.previewUrl : null} />)}
    {optimisticMessage && <UserBubble text={optimisticMessage} pending={isUserMessageSending(submissionPhase)} image={selectedImage?.previewUrl ?? null} />}
    {sending && <AmanaBubble><Activity text={selectedImage ? "Understanding the image, then checking merchant evidence…" : "Checking live catalogue and merchant facts…"} /></AmanaBubble>}
    {result && !sending && <CommerceResponse result={result} snapshot={snapshot} authorization={authorization} checkout={checkout} payment={payment} fulfillment={fulfillment} busy={Boolean(busyAction)} onAuthorize={() => void authorizeAndOpen()} onOpenCheckout={() => void openPreparedCheckout()} onReconcile={() => void reconcile()} checkoutButton={checkoutButton} />}
    {notice && <div className={styles.liveNotice} aria-live="polite">{notice}</div>}
    {error && <div className={styles.chatError} role="alert"><span>{error}</span>{threadId && <button type="button" onClick={() => void reloadThread(threadId)}>Reload state</button>}</div>}
    <div ref={endMarker} />
  </>;

  return (
    <div className={styles.commerceChat} data-mode={inputMode.toLowerCase()} aria-busy={loading || sending || Boolean(busyAction)}>
      {thread && (
        <header className={styles.commerceHeader}>
          <div><p className={styles.eyebrow}>Buyer conversation</p><h1>{thread.title}</h1></div>
          <Link href="/buyer/conversations">All conversations</Link>
        </header>
      )}

      {shouldRenderVoiceOrb(inputMode) ? (
        <Suspense fallback={<div className={styles.voiceLoading}>Preparing voice…</div>}>
          <BuyerVoice
            onClose={() => {
              setInputMode((current) => reduceBuyerInputMode(current, "TEXT_SELECTED"));
              const activeThreadId=threadIdRef.current;
              if (!initialThreadId&&activeThreadId) router.replace(`/buyer/chat/${activeThreadId}`);
              window.setTimeout(() => voiceButton.current?.querySelector("button")?.focus(), 0);
            }}
            onCommerceRequest={runCommerceRequest}
          >
            {conversation}
          </BuyerVoice>
        </Suspense>
      ) : <>
        <section className={styles.chatStream} aria-label="Conversation">
          {showWelcome && <Welcome value={value} setValue={setValue} />}
          {conversation}
        </section>

        <form className={styles.commerceComposer} data-tour="composer" onSubmit={submit}>
          {selectedImage && <div className={styles.imagePreview}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={selectedImage.previewUrl} alt="Selected product reference" />
            <div><strong>{selectedImage.file.name}</strong><span>{formatBytes(selectedImage.file.size)} · preview only</span></div>
            <button type="button" onClick={removeSelectedImage} aria-label="Remove selected image">Remove</button>
          </div>}
          <div className={styles.composerRow}>
            <input ref={imageInput} className={styles.hiddenFileInput} type="file" accept={BUYER_IMAGE_ACCEPT} onChange={(event) => void chooseImage(event.target.files?.[0])} />
            <Tooltip content="Add a product image" placement="top">
              <IconButton icon={ImageIcon} accessibilityLabel="Add a product image" size="medium" isDisabled={sending} onClick={() => imageInput.current?.click()} />
            </Tooltip>
            <textarea
              ref={composer}
              rows={1}
              aria-label="Message Amana"
              onChange={(event) => setValue(event.target.value)}
              onKeyDown={(event) => { if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); event.currentTarget.form?.requestSubmit(); } }}
              placeholder="Ask for a product, budget, or requirement"
              value={value}
              disabled={sending}
            />
            <span ref={voiceButton} data-tour="mode-switch">
              <Tooltip content="Switch to voice" placement="top">
                <IconButton icon={MicIcon} accessibilityLabel="Switch to voice mode" size="medium" onClick={() => setInputMode((current) => reduceBuyerInputMode(current, "VOICE_SELECTED"))} />
              </Tooltip>
            </span>
            <IconButton icon={SendIcon} accessibilityLabel="Send request" size="medium" isDisabled={(!value.trim() && !selectedImage) || sending} onClick={() => composer.current?.form?.requestSubmit()} />
          </div>
        </form>
      </>}
    </div>
  );
}

function Welcome({ value, setValue }: { value: string; setValue: (value: string) => void }) {
  return <section className={styles.emptyState} aria-labelledby="new-chat-title">
    <p className={styles.eyebrow}>New conversation</p>
    <h1 id="new-chat-title">Ask Amana</h1>
    <p className={styles.emptyLead}>Describe what you need. Prices, availability, and safety requirements are checked before a proposal appears.</p>
    <div className={styles.promptList} aria-label="Example prompts">{suggestions.map((suggestion) => <button key={suggestion} aria-pressed={value === suggestion} onClick={() => setValue(suggestion)} type="button">{suggestion}</button>)}</div>
    <div className={styles.trustRail}><span data-tour="grounding">Grounded in connected catalogues</span><span data-tour="proposal-boundary">You approve every purchase</span></div>
  </section>;
}

function UserBubble({ text, time, pending = false, image }: { text: string; time?: string; pending?: boolean; image?: string | null }) {
  return <article className={styles.userBubble}>{image && (
    // eslint-disable-next-line @next/next/no-img-element
    <img className={styles.userImage} src={image} alt="Product reference supplied by you" />
  )}<p>{text}</p><span>{pending ? "Sending…" : time ? formatDate(time) : ""}</span></article>;
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
  const merchantName = result.merchantDisplayName ?? "Connected merchant";
  return <AmanaBubble>
    <div className={styles.responseIntro}>
      <p>{result.clarificationRequired ? (result.clarificationQuestion ?? "I couldn’t verify a grounded product match.") : result.failureCode ? failureCopy(result.failureCode) : result.transactionProposalId ? "I found a grounded option and prepared the exact purchase for your review." : "Here is the latest verified result."}</p>
      {result.progress.length > 0 && <ol className={styles.progressList}>{result.progress.map((step) => <li key={step.code}><span aria-hidden="true">✓</span>{step.label}</li>)}</ol>}
    </div>

    {noMatch && <section className={styles.noMatch}><h2>I couldn’t verify a grounded product match.</h2><p>{result.visualObservation ? "The visual hypothesis did not produce a trustworthy eligible catalogue result. Add a colour, budget, material, or merchant constraint." : "I haven’t substituted an unverified item. Add a category, exact SKU/GTIN, or a specific brand and model to narrow the request."}</p></section>}

    {result.visualObservation && <section className={styles.visualObservation}>
      <div><p>Visual hypothesis · not catalogue truth</p><h2>{result.visualObservation.productType}</h2></div>
      <p>The image appears to show {[...result.visualObservation.colors, ...result.visualObservation.styleDescriptors].slice(0, 4).join(", ") || result.visualObservation.category}. Merchant evidence below determines the actual product.</p>
      {result.visualObservation.ambiguities.length > 0 && <small>Uncertain: {result.visualObservation.ambiguities.join(" · ")}</small>}
    </section>}

    {result.products.length > 0 && <section className={styles.results} aria-labelledby={`results-${result.requestId}`}>
      <div className={styles.sectionHeading}><div><p>Hybrid Retrieval · RAG + Lexical</p><h2 id={`results-${result.requestId}`}>Grounded product</h2></div><div className={styles.merchantIdentity}><MerchantLogo className={styles.merchantLogo} name={merchantName} logoUrl={result.merchantLogoUrl} /><span>{merchantName}</span></div></div>
      <p className={styles.retrievalNote}>Matches meaning as well as exact product terms across connected merchants.</p>
      {result.products.map((product) => <article className={styles.productRow} key={product.productId}>
        <ProductImage name={product.productName} image={authoritativeImage(product.facts)} />
        <div className={styles.productCopy}>{result.visualMatchType && <span className={styles.matchBadge}>{result.visualMatchType === "EXACT_GROUNDED_MATCH" ? "Exact grounded match" : "Visually similar grounded result"}</span>}<span className={styles.productMerchant}><MerchantLogo className={styles.merchantLogoSmall} name={merchantName} logoUrl={result.merchantLogoUrl} />{merchantName}</span><h3>{product.productName}</h3><p>{[product.brand, product.variant, product.colour, product.sizeStorage].filter(Boolean).join(" · ")}</p>{result.visualMatchReasons.map((reason) => <small className={styles.matchReason} key={reason}>{reason}</small>)}<dl><div><dt>SKU</dt><dd>{product.merchantSku}</dd></div><div><dt>Quantity</dt><dd>{product.quantity}</dd></div>{product.facts.filter((fact) => fact.type !== "IMAGE").slice(0, 3).map((fact) => <div key={fact.factId}><dt>{friendly(fact.type)}</dt><dd>{factValue(fact.value)}</dd></div>)}</dl></div>
        <div className={styles.productPrice}><span>Verified price</span><strong>{money(product.unitAmountMinor, result.authoritativeCurrency)}</strong></div>
      </article>)}
    </section>}

    {(result.products.length > 0 || result.constraints.length > 0) && <details className={styles.safetyPanel}>
      <summary><span>Why this is safe</span><small>Deterministic Guardrails</small></summary>
      <div className={styles.evidenceRows}>
        <Evidence label="Merchant product grounded" outcome={result.products.length > 0 ? "PASS" : "UNKNOWN"} />
        <Evidence label="Current price checked" outcome={result.authoritativeFinalAmountMinor != null ? "PASS" : "UNKNOWN"} />
        <Evidence label="Availability checked" outcome={result.availabilityOutcome ?? "UNKNOWN"} detail={result.availabilityReasonCode} />
        <Evidence label="Delivery checked" outcome={result.serviceabilityOutcome ?? "UNKNOWN"} detail={result.serviceabilityReasonCode} />
        {result.constraints.map((constraint) => <Evidence key={constraint.key} label={friendly(constraint.key)} outcome={constraint.result} detail={constraint.safetyCritical ? "Safety-critical" : undefined} />)}
      </div>
      {blocked && <p className={styles.blockedCopy}>This purchase cannot proceed while a required fact is failed or unresolved.</p>}
    </details>}

    {result.cartId && result.products.length > 0 && <section className={styles.cartPanel}>
      <div className={styles.sectionHeading}><div><p>Candidate cart</p><div className={styles.merchantIdentityLarge}><MerchantLogo className={styles.merchantLogo} name={merchantName} logoUrl={result.merchantLogoUrl} /><h2>{merchantName}</h2></div></div><span>Current quote</span></div>
      <ul>{result.products.map((product) => <li key={product.productId}><span>{product.quantity} × {product.productName}</span><strong>{money(product.lineAmountMinor, result.authoritativeCurrency)}</strong></li>)}</ul>
      <dl className={styles.totals}><div><dt>Subtotal</dt><dd>{money(result.subtotalMinor, result.authoritativeCurrency)}</dd></div><div><dt>Delivery</dt><dd>{money(result.deliveryMinor, result.authoritativeCurrency)}</dd></div>{Boolean(result.taxMinor) && <div><dt>Tax</dt><dd>{money(result.taxMinor, result.authoritativeCurrency)}</dd></div>}{Boolean(result.feesMinor) && <div><dt>Fees</dt><dd>{money(result.feesMinor, result.authoritativeCurrency)}</dd></div>}<div className={styles.totalLine}><dt>Current total</dt><dd>{money(result.authoritativeFinalAmountMinor, result.authoritativeCurrency)}</dd></div></dl>
    </section>}

    {result.transactionProposalId && <section className={styles.proposal} data-blocked={!result.paymentReady}>
      <p className={styles.proposalKicker}>Exact action for authorization</p>
      <div className={styles.merchantIdentityLarge}><MerchantLogo className={styles.merchantLogo} name={merchantName} logoUrl={result.merchantLogoUrl} /><h2>Purchase from {merchantName}</h2></div>
      <p className={styles.proposalAmount}>{money(result.authoritativeFinalAmountMinor, result.authoritativeCurrency)}</p>
      <ul>{result.products.map((product) => <li key={product.productId}>{product.quantity} × {product.productName} <span>{product.merchantSku}</span></li>)}</ul>
      <div className={styles.proposalMeta}><span>Proposal {shortId(result.transactionProposalId)}</span><span>{result.proposalExpiresAt ? `Expires ${formatDate(result.proposalExpiresAt)}` : "Expiry unavailable"}</span></div>
      {!authorization && <button className={styles.authorizeButton} disabled={!snapshot.canAuthorize || busy} onClick={onAuthorize} type="button" aria-label={`Authorize purchase for ${money(result.authoritativeFinalAmountMinor, result.authoritativeCurrency)}`}>{busy ? "Preparing…" : "Authorize purchase"}</button>}
      {authorization?.decision === "AUTHORIZED" && !checkout && <button className={styles.authorizeButton} disabled={busy} onClick={onAuthorize} type="button">Prepare secure checkout</button>}
      {checkout && snapshot.canOpenCheckout && <button ref={checkoutButton} className={styles.authorizeButton} disabled={busy} onClick={onOpenCheckout} type="button">Open Razorpay Checkout</button>}
      {authorization?.decision === "DENIED" && <p className={styles.blockedCopy}>You declined this proposal. It will not be executed.</p>}
      {!result.paymentReady && !authorization && <p className={styles.blockedCopy}>Authorization is unavailable because the current proposal did not pass every required authority check.</p>}
    </section>}

    {(checkout || payment || fulfillment) && <PaymentPanel snapshot={snapshot} payment={payment} fulfillment={fulfillment} busy={busy} merchantName={merchantName} merchantLogoUrl={result.merchantLogoUrl} onReconcile={onReconcile} />}
  </AmanaBubble>;
}

function PaymentPanel({ snapshot, payment, fulfillment, busy, merchantName, merchantLogoUrl, onReconcile }: { snapshot: ReturnType<typeof deriveJourneySnapshot>; payment: PaymentStateView | null; fulfillment: FulfillmentView | null; busy: boolean; merchantName: string; merchantLogoUrl: string | null; onReconcile: () => void }) {
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
  return <section className={styles.paymentPanel} data-phase={snapshot.phase} aria-live="polite"><span className={styles.paymentIcon} aria-hidden="true">{snapshot.phase === "FULFILLED" ? "✓" : "•"}</span><div><div className={styles.paymentMerchant}><MerchantLogo className={styles.merchantLogoSmall} name={merchantName} logoUrl={merchantLogoUrl} /><span>{merchantName}</span></div><h2>{copy[0]}</h2><p>{copy[1]}</p>{payment && <small>Provider order {shortId(payment.providerOrderId ?? "pending")} · {money(payment.amountMinor, payment.currency)}</small>}</div>{snapshot.canReconcile && <button disabled={busy} onClick={onReconcile} type="button">{busy ? "Verifying…" : "Verify payment status"}</button>}</section>;
}

function Evidence({ label, outcome, detail }: { label: string; outcome: EvidenceOutcome; detail?: string | null }) {
  return <div className={styles.evidenceRow} data-outcome={outcome}><span aria-hidden="true">{outcome === "PASS" ? "✓" : outcome === "FAIL" ? "×" : "?"}</span><div><strong>{label}</strong>{detail && <small>{friendly(detail)}</small>}</div><em>{outcome}</em></div>;
}

function ProductImage({ name, image }: { name: string; image: string | null }) {
  return <div className={styles.productGlyph} data-has-media={Boolean(image)}><span aria-hidden="true">{name.slice(0, 1)}</span>{image && (
    // Authoritative catalogue image hosts are dynamic; a fixed Next Image allowlist would reject valid merchants.
    // eslint-disable-next-line @next/next/no-img-element
    <img src={image} alt={name} loading="eager" decoding="async" referrerPolicy="no-referrer" onError={(event) => { event.currentTarget.remove(); event.currentTarget.parentElement?.setAttribute("data-has-media", "false"); }} />
  )}</div>;
}

function Activity({ text }: { text: string }) { return <div className={styles.activity} aria-live="polite"><span aria-hidden="true" /><span aria-hidden="true" /><span aria-hidden="true" /><p>{text}</p></div>; }

async function optional<T>(read: () => Promise<T>): Promise<T | null> {
  try { return await read(); } catch (error) {
    if (error instanceof BuyerApiError && [404, 409].includes(error.status)) return null;
    throw error;
  }
}

async function recoverCommerceRequest(requestId: string): Promise<CommerceRequestResult> {
  for (let attempt = 0; attempt < 90; attempt += 1) {
    await waitUntilVisible();
    try {
      const result = await buyerApi.commerceRequest(requestId);
      if (result.requestStatus !== "RUNNING") return result;
    } catch (error) {
      if (!(error instanceof BuyerApiError) || !["COMMERCE_REQUEST_RUNNING", "COMMERCE_REQUEST_ACCEPTING"].includes(error.code ?? "")) throw error;
    }
    await delay(1000);
  }
  throw new BuyerApiError(
    504,
    "The durable commerce request is still running. Reload this conversation to continue tracking it.",
    "COMMERCE_REQUEST_RECOVERY_TIMEOUT",
  );
}

async function observeCommerceAcceptance(
  requestId: string,
  onAccepted: () => void,
  isSettled: () => boolean,
): Promise<void> {
  for (let attempt = 0; attempt < 100 && !isSettled(); attempt += 1) {
    try {
      await buyerApi.commerceRequest(requestId);
      if (!isSettled()) onAccepted();
      return;
    } catch (error) {
      if (error instanceof BuyerApiError && error.code === "COMMERCE_REQUEST_ACCEPTING") {
        if (!isSettled()) onAccepted();
        return;
      }
      if (!(error instanceof BuyerApiError) || error.status !== 404) return;
    }
    await delay(100);
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
function failureCopy(code: string) {
  if (code === "AI_PROVIDER_RATE_LIMITED") return "Amana’s reasoning service is temporarily rate-limited. Retry shortly. Nothing was authorized.";
  if (code === "AI_PROVIDER_UNAVAILABLE") return "Amana’s reasoning service is temporarily unavailable. Retry shortly. Nothing was authorized.";
  return "I couldn’t safely complete this request. Nothing was authorized.";
}
function formatBytes(bytes: number) { return bytes >= 1024 * 1024 ? `${(bytes / (1024 * 1024)).toFixed(1)} MB` : `${Math.ceil(bytes / 1024)} KB`; }
function shortId(value: string) { return value.length > 12 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value; }
function friendly(value: string) { return value.toLowerCase().replaceAll("_", " ").replace(/^./, (letter) => letter.toUpperCase()); }
function factValue(value: unknown) { if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") return String(value); return "Verified merchant fact"; }
function authoritativeImage(facts: CommerceRequestResult["products"][number]["facts"]) { const value = facts.find((fact) => fact.type === "IMAGE")?.value; return typeof value === "string" && (value.startsWith("https://") || value.startsWith("/")) ? value : null; }
function money(value: number | null, currency: string | null) { if (value == null || !currency) return "Not verified"; return new Intl.NumberFormat("en-IN", { style: "currency", currency }).format(value / 100); }
function formatDate(value: string) { return new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short", hour: "numeric", minute: "2-digit" }).format(new Date(value)); }
