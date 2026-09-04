"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useBuyerSession } from "./buyer-session";
import styles from "./buyer-shell.module.css";

const steps = [
  { target: "new-chat", title: "Start with intent", body: "Open a fresh conversation whenever you want Amana to help with a new purchase." },
  { target: "conversations", title: "Pick up where you left off", body: "Your grounded commerce conversations stay organized and easy to reopen." },
  { target: "composer", title: "Text, image, or voice", body: "This workspace is prepared for all three inputs. Commerce execution arrives in the next Buyer task." },
  { target: "orders", title: "Stay close to every order", body: "Orders will bring tracking and lifecycle actions into one calm view." },
  { target: "settings", title: "Your boundaries, in one place", body: "Manage delivery details, merchant connections, preferences, and voice settings here." },
] as const;

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
    window.addEventListener("resize", locate);
    window.addEventListener("scroll", locate, true);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener("resize", locate);
      window.removeEventListener("scroll", locate, true);
    };
  }, [locate]);

  useEffect(() => {
    if (!active) return;
    dialog.current?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") setActive(false);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [active, index]);

  if (!active) return null;
  const step = steps[index];
  const tooltipBelow = !rect || rect.top < window.innerHeight * 0.55;

  return (
    <div className={styles.tourLayer} aria-live="polite">
      {rect && <div className={styles.spotlight} style={rect} />}
      <div
        className={styles.tourCard}
        data-below={tooltipBelow}
        ref={dialog}
        role="dialog"
        aria-modal="false"
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
