"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { buyerTourSteps as steps } from "@/lib/buyer/buyer-experience";
import { useBuyerSession } from "./buyer-session";
import styles from "./buyer-shell.module.css";

type Rect = { top: number; left: number; width: number; height: number };

export function ProductTour() {
  const { actor } = useBuyerSession();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const router = useRouter();
  const [active, setActive] = useState(false);
  const [index, setIndex] = useState(0);
  const [rect, setRect] = useState<Rect | null>(null);
  const dialog = useRef<HTMLDivElement>(null);

  const locate = useCallback(() => {
    if (!active) return;
    const candidates = Array.from(document.querySelectorAll<HTMLElement>(`[data-tour="${steps[index].target}"]`));
    const target = candidates.find((element) => {
      const bounds = element.getBoundingClientRect();
      return bounds.width > 0 && bounds.height > 0;
    });
    if (!target) return setRect(null);
    const bounds = target.getBoundingClientRect();
    setRect({ top: bounds.top - 7, left: bounds.left - 7, width: bounds.width + 14, height: bounds.height + 14 });
  }, [active, index]);

  useEffect(() => {
    if (!actor || pathname !== "/buyer/chat") return;
    const pendingKey = `amana:tour-pending:${actor.actorId}`;
    if (searchParams.get("tour") === "1" || localStorage.getItem(pendingKey) === "true") {
      localStorage.removeItem(pendingKey);
      queueMicrotask(() => {
        setIndex(0);
        setActive(true);
      });
      router.replace("/buyer/chat", { scroll: false });
    }
  }, [actor, pathname, router, searchParams]);

  useEffect(() => {
    const frame = requestAnimationFrame(locate);
    const timer = window.setInterval(locate, 250);
    window.addEventListener("resize", locate);
    window.addEventListener("scroll", locate, true);
    return () => {
      cancelAnimationFrame(frame);
      window.clearInterval(timer);
      window.removeEventListener("resize", locate);
      window.removeEventListener("scroll", locate, true);
    };
  }, [locate]);

  useEffect(() => {
    if (!active) return;
    window.dispatchEvent(new CustomEvent("amana:buyer-mode", { detail: { mode: index === 0 ? "VOICE" : "TEXT" } }));
    dialog.current?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") setActive(false);
      if (event.key === "Tab" && dialog.current) {
        const focusable = Array.from(dialog.current.querySelectorAll<HTMLElement>("button, [href], [tabindex]:not([tabindex='-1'])"));
        if (focusable.length === 0) return;
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [active, index]);

  if (!active) return null;
  const step = steps[index];
  const tooltipBelow = !rect || rect.top < window.innerHeight * 0.55;

  return (
    <div className={styles.tourLayer} aria-live="polite">
      <svg className={styles.tourBackdrop} aria-hidden="true" width="100%" height="100%">
        <defs><mask id="amana-tour-mask" maskUnits="userSpaceOnUse"><rect width="100%" height="100%" fill="white" />{rect && <rect x={rect.left} y={rect.top} width={rect.width} height={rect.height} rx="13" fill="black" />}</mask></defs>
        <rect width="100%" height="100%" fill="rgba(12, 22, 42, .62)" mask="url(#amana-tour-mask)" />
      </svg>
      {rect && <div className={styles.spotlight} style={rect} />}
      <div
        className={styles.tourCard}
        data-below={tooltipBelow}
        ref={dialog}
        role="dialog"
        aria-modal="true"
        aria-label={`Product tour, step ${index + 1} of ${steps.length}`}
        tabIndex={-1}
        style={rect ? { left: Math.min(Math.max(rect.left, 16), window.innerWidth - 356), top: tooltipBelow ? rect.top + rect.height + 14 : rect.top - 14 } : undefined}
      >
        <div className={styles.tourProgress}><span>{String(index + 1).padStart(2, "0")}</span><i style={{ width: `${((index + 1) / steps.length) * 100}%` }} /></div>
        <h2>{step.title}</h2>
        <p>{step.body}</p>
        <div className={styles.tourActions}>
          <button className={styles.textButton} onClick={() => setActive(false)}>Skip</button>
          <span />
          <button className={styles.secondaryButton} disabled={index === 0} onClick={() => setIndex((value) => value - 1)}>Back</button>
          <button className={styles.primaryButton} onClick={() => index === steps.length - 1 ? setActive(false) : setIndex((value) => value + 1)}>
            {index === steps.length - 1 ? "Finish" : "Next"}
          </button>
        </div>
      </div>
    </div>
  );
}
