"use client";

import { useEffect, useState, useSyncExternalStore } from "react";

const reducedMotionQuery = "(prefers-reduced-motion: reduce)";

function subscribeToReducedMotion(onChange: () => void) {
  const query = window.matchMedia(reducedMotionQuery);
  query.addEventListener("change", onChange);
  return () => query.removeEventListener("change", onChange);
}

function getReducedMotionSnapshot() {
  return window.matchMedia(reducedMotionQuery).matches;
}

function subscribeToVisibility(onChange: () => void) {
  document.addEventListener("visibilitychange", onChange);
  return () => document.removeEventListener("visibilitychange", onChange);
}

function getVisibilitySnapshot() {
  return document.visibilityState === "visible";
}

/** Defaults to reduced motion during SSR, then adopts the user's preference. */
export function usePrefersReducedMotion() {
  return useSyncExternalStore(subscribeToReducedMotion, getReducedMotionSnapshot, () => true);
}

/** Allows motion-heavy children to pause when the browser tab is hidden. */
export function usePageVisibility() {
  return useSyncExternalStore(subscribeToVisibility, getVisibilitySnapshot, () => false);
}

type ViewportMotionOptions = {
  rootMargin?: string;
  threshold?: number;
};

/**
 * Activates optional motion only while an element is near the viewport, the tab
 * is visible, and reduced motion is not requested. Updates occur on observer
 * events, never on animation frames.
 */
export function useViewportMotion<T extends HTMLElement>({
  rootMargin = "160px",
  threshold = 0.05,
}: ViewportMotionOptions = {}) {
  const [element, setElement] = useState<T | null>(null);
  const [isNearViewport, setIsNearViewport] = useState(false);
  const prefersReducedMotion = usePrefersReducedMotion();
  const isPageVisible = usePageVisibility();

  useEffect(() => {
    if (!element) {
      return;
    }

    const observer = new IntersectionObserver(
      ([entry]) => setIsNearViewport(entry.isIntersecting),
      { rootMargin, threshold },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, [element, rootMargin, threshold]);

  return {
    isActive: isNearViewport && isPageVisible && !prefersReducedMotion,
    ref: setElement,
  } as const;
}
