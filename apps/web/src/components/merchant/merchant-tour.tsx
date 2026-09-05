"use client";

import { usePathname, useRouter } from "next/navigation";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";
import {
  MERCHANT_TOUR_STEPS,
  clearMerchantTourSeen,
  markMerchantTourSeen,
  positionTourCard,
  selectVisibleTourTarget,
  shouldStartMerchantTour,
  type TourRect,
} from "@/lib/merchant/merchant-tour";
import styles from "./merchant-tour.module.css";

type MerchantTourContextValue = { replayTour: () => void };

const MerchantTourContext = createContext<MerchantTourContextValue | null>(null);

export function useMerchantTour() {
  const value = useContext(MerchantTourContext);
  if (!value) throw new Error("useMerchantTour must be used inside MerchantTourProvider");
  return value;
}

function rectFromDom(rect: DOMRect): TourRect {
  return {
    top: rect.top,
    right: rect.right,
    bottom: rect.bottom,
    left: rect.left,
    width: rect.width,
    height: rect.height,
  };
}

export function MerchantTourProvider({
  actorId,
  children,
  onMobileNavigationChange,
  ready,
}: {
  actorId: string;
  children: ReactNode;
  onMobileNavigationChange: (open: boolean) => void;
  ready: boolean;
}) {
  const pathname = usePathname();
  const router = useRouter();
  const [active, setActive] = useState(false);
  const [stepIndex, setStepIndex] = useState(0);
  const [targetRect, setTargetRect] = useState<TourRect | null>(null);
  const [targetMissing, setTargetMissing] = useState(false);
  const [cardSize, setCardSize] = useState({ width: 360, height: 270 });
  const [viewport, setViewport] = useState({ width: 1440, height: 900 });
  const initializedActor = useRef<string | null>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const previousFocus = useRef<HTMLElement | null>(null);
  const step = MERCHANT_TOUR_STEPS[stepIndex] ?? MERCHANT_TOUR_STEPS[0];

  useEffect(() => {
    if (!ready || initializedActor.current === actorId) return;
    initializedActor.current = actorId;
    const timer = window.setTimeout(() => {
      try {
        if (!shouldStartMerchantTour(window.localStorage, actorId)) return;
        setStepIndex(0);
        setActive(true);
      } catch {
        // Storage may be unavailable in hardened browser contexts; the tour remains presentation-only.
        setStepIndex(0);
        setActive(true);
      }
    }, 0);
    return () => window.clearTimeout(timer);
  }, [actorId, ready]);

  const finishTour = useCallback(() => {
    try {
      markMerchantTourSeen(window.localStorage, actorId);
    } catch {
      // Authentication and merchant state never depend on this optional presentation flag.
    }
    setActive(false);
    onMobileNavigationChange(false);
  }, [actorId, onMobileNavigationChange]);

  const replayTour = useCallback(() => {
    try {
      clearMerchantTourSeen(window.localStorage, actorId);
    } catch {
      // The replay still runs for this session when local storage is unavailable.
    }
    setStepIndex(0);
    setActive(true);
    router.push(MERCHANT_TOUR_STEPS[0].route);
  }, [actorId, router]);

  useEffect(() => {
    if (!active || pathname === step.route) return;
    router.push(step.route);
  }, [active, pathname, router, step.route]);

  useEffect(() => {
    if (!active) return;
    const media = window.matchMedia("(max-width: 48rem)");
    const syncNavigation = () => onMobileNavigationChange(media.matches && Boolean(step.mobileNavigation));
    syncNavigation();
    media.addEventListener("change", syncNavigation);
    return () => media.removeEventListener("change", syncNavigation);
  }, [active, onMobileNavigationChange, step.mobileNavigation]);

  useEffect(() => {
    if (!active || pathname !== step.route) {
      const frame = window.requestAnimationFrame(() => setTargetRect(null));
      return () => window.cancelAnimationFrame(frame);
    }
    let cancelled = false;
    const startedAt = Date.now();
    let observed: Element | null = null;
    let resizeObserver: ResizeObserver | null = null;

    const measure = () => {
      if (cancelled) return;
      setViewport((current) => current.width === window.innerWidth && current.height === window.innerHeight
        ? current
        : { width: window.innerWidth, height: window.innerHeight });
      const candidates = Array.from(document.querySelectorAll<HTMLElement>(`[data-tour="${step.target}"]`))
        .map((element) => ({ element, rect: rectFromDom(element.getBoundingClientRect()) }));
      const target = selectVisibleTourTarget(candidates);
      if (!target) {
        setTargetRect(null);
        setTargetMissing(Date.now() - startedAt > 1200);
        return;
      }
      setTargetMissing(false);
      const outsideViewport = target.rect.bottom < 16 || target.rect.top > window.innerHeight - 16;
      if (outsideViewport) target.element.scrollIntoView({ block: "center", behavior: "auto" });
      const measured = rectFromDom(target.element.getBoundingClientRect());
      setTargetRect((current) => current
        && current.top === measured.top
        && current.left === measured.left
        && current.width === measured.width
        && current.height === measured.height
        ? current
        : measured);
      if (observed !== target.element) {
        resizeObserver?.disconnect();
        observed = target.element;
        resizeObserver = new ResizeObserver(measure);
        resizeObserver.observe(target.element);
      }
    };

    measure();
    const poll = window.setInterval(measure, 120);
    window.addEventListener("resize", measure);
    window.addEventListener("scroll", measure, true);
    return () => {
      cancelled = true;
      window.clearInterval(poll);
      window.removeEventListener("resize", measure);
      window.removeEventListener("scroll", measure, true);
      resizeObserver?.disconnect();
    };
  }, [active, pathname, step.route, step.target]);

  useEffect(() => {
    if (!active || !dialogRef.current) return;
    const measureCard = () => {
      const rect = dialogRef.current?.getBoundingClientRect();
      if (rect) setCardSize((current) => current.width === rect.width && current.height === rect.height
        ? current
        : { width: rect.width, height: rect.height });
    };
    measureCard();
    const observer = new ResizeObserver(measureCard);
    observer.observe(dialogRef.current);
    return () => observer.disconnect();
  }, [active, stepIndex, targetMissing]);

  useEffect(() => {
    if (!active) return;
    previousFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const shell = document.querySelector<HTMLElement>("[data-merchant-shell-root]");
    if (shell) shell.inert = true;
    window.requestAnimationFrame(() => dialogRef.current?.focus());

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        finishTour();
        return;
      }
      if (event.key !== "Tab" || !dialogRef.current) return;
      const controls = Array.from(dialogRef.current.querySelectorAll<HTMLElement>("button:not([disabled])"));
      if (!controls.length) return;
      const first = controls[0];
      const last = controls.at(-1) ?? first;
      if (!dialogRef.current.contains(document.activeElement)) {
        event.preventDefault();
        (event.shiftKey ? last : first).focus();
      } else if (event.shiftKey && (document.activeElement === first || document.activeElement === dialogRef.current)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => {
      if (shell) shell.inert = false;
      document.removeEventListener("keydown", onKeyDown);
      previousFocus.current?.focus();
    };
  }, [active, finishTour]);

  const paddedRect = useMemo(() => {
    if (!targetRect) return null;
    const padding = 8;
    const left = Math.max(0, targetRect.left - padding);
    const top = Math.max(0, targetRect.top - padding);
    const right = Math.min(viewport.width, targetRect.right + padding);
    const bottom = Math.min(viewport.height, targetRect.bottom + padding);
    return { left, top, right, bottom, width: right - left, height: bottom - top };
  }, [targetRect, viewport]);
  const cardPosition = positionTourCard(paddedRect, step.placement, viewport, cardSize);
  const overlayStyle = (style: CSSProperties) => ({ ...style } as CSSProperties);
  const contextValue = useMemo(() => ({ replayTour }), [replayTour]);

  return (
    <MerchantTourContext.Provider value={contextValue}>
      {children}
      {active && typeof document !== "undefined" && createPortal(
        <div className={styles.tourLayer} data-testid="merchant-product-tour">
          {paddedRect ? (
            <>
              <div className={styles.mask} style={overlayStyle({ inset: `0 0 auto 0`, height: paddedRect.top })} />
              <div className={styles.mask} style={overlayStyle({ left: 0, top: paddedRect.top, width: paddedRect.left, height: paddedRect.height })} />
              <div className={styles.mask} style={overlayStyle({ left: paddedRect.right, right: 0, top: paddedRect.top, height: paddedRect.height })} />
              <div className={styles.mask} style={overlayStyle({ inset: `${paddedRect.bottom}px 0 0 0` })} />
              <div className={styles.targetBlocker} style={overlayStyle({ left: paddedRect.left, top: paddedRect.top, width: paddedRect.width, height: paddedRect.height })} />
              <div className={styles.spotlightRing} style={overlayStyle({ left: paddedRect.left, top: paddedRect.top, width: paddedRect.width, height: paddedRect.height })} />
            </>
          ) : <div className={`${styles.mask} ${styles.fullMask}`} />}
          <div
            aria-describedby="merchant-tour-copy"
            aria-labelledby="merchant-tour-heading"
            aria-modal="true"
            className={styles.tourCard}
            ref={dialogRef}
            role="dialog"
            style={{ left: cardPosition.left, top: cardPosition.top }}
            tabIndex={-1}
          >
            <div className={styles.progressRow}>
              <span aria-live="polite">Step {stepIndex + 1} of {MERCHANT_TOUR_STEPS.length}</span>
              <span className={styles.progressTrack} aria-hidden="true"><i style={{ width: `${((stepIndex + 1) / MERCHANT_TOUR_STEPS.length) * 100}%` }} /></span>
            </div>
            <h2 id="merchant-tour-heading">{step.heading}</h2>
            <p id="merchant-tour-copy">{step.copy}</p>
            {step.emphasis && <p className={styles.emphasis}>{step.emphasis}</p>}
            {targetMissing && <p className={styles.targetNote}>This area is still loading. You can continue without losing your place.</p>}
            <div className={styles.tourActions}>
              <button className={styles.skipButton} onClick={finishTour} type="button">Skip tour</button>
              <span>
                <button disabled={stepIndex === 0} onClick={() => setStepIndex((current) => Math.max(0, current - 1))} type="button">Back</button>
                <button className={styles.nextButton} onClick={() => stepIndex === MERCHANT_TOUR_STEPS.length - 1 ? finishTour() : setStepIndex((current) => current + 1)} type="button">
                  {stepIndex === MERCHANT_TOUR_STEPS.length - 1 ? "Finish" : "Next"}
                </button>
              </span>
            </div>
          </div>
        </div>,
        document.body,
      )}
    </MerchantTourContext.Provider>
  );
}
