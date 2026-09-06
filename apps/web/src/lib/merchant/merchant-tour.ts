export type MerchantTourTarget =
  | "merchant-selector"
  | "readiness-summary"
  | "agentization-nav"
  | "capability-rail"
  | "activity-timeline"
  | "evidence-authority"
  | "manifest-nav";

export type MerchantTourStep = {
  target: MerchantTourTarget;
  route: "/merchant/overview" | "/merchant/agentization";
  heading: string;
  copy: string;
  emphasis?: string;
  mobileNavigation?: boolean;
  placement: "above" | "below" | "left" | "right";
};

export const MERCHANT_TOUR_STEPS: readonly MerchantTourStep[] = [
  {
    target: "merchant-selector",
    route: "/merchant/overview",
    heading: "Your agent-ready store",
    copy: "Everything here is scoped to the merchant you administer. Amana never mixes authority or evidence between merchants.",
    mobileNavigation: true,
    placement: "right",
  },
  {
    target: "readiness-summary",
    route: "/merchant/overview",
    heading: "Readiness is earned, not assumed",
    copy: "Amana only advertises capabilities after deterministic evidence satisfies the readiness reducer.",
    emphasis: "AI does not declare READY.",
    placement: "right",
  },
  {
    target: "agentization-nav",
    route: "/merchant/overview",
    heading: "Agentize approved commerce APIs",
    copy: "Amana inspects approved sources, maps capabilities, runs contract tests and proposes bounded repairs.",
    mobileNavigation: true,
    placement: "right",
  },
  {
    target: "capability-rail",
    route: "/merchant/agentization",
    heading: "Every commerce action is evaluated independently",
    copy: "Search, availability, quote, ordering, cancellation and returns each have their own evidence and readiness state.",
    placement: "right",
  },
  {
    target: "activity-timeline",
    route: "/merchant/agentization",
    heading: "See exactly how agentization reached its conclusion",
    copy: "Inspect · Discover · Map · Test · Observe · Diagnose · Repair · Retest · Reduce.",
    placement: "right",
  },
  {
    target: "evidence-authority",
    route: "/merchant/agentization",
    heading: "AI may propose. Authority remains deterministic.",
    copy: "Failures, observed values, proposed mappings and merchant approvals remain inspectable before readiness changes.",
    placement: "left",
  },
  {
    target: "manifest-nav",
    route: "/merchant/agentization",
    heading: "Only verified capabilities become discoverable",
    copy: "The reducer-published manifest is what Safe AI Buyer consumes. Replay output or AI suggestions cannot silently alter it.",
    mobileNavigation: true,
    placement: "right",
  },
] as const;

type LocalStorageLike = Pick<Storage, "getItem" | "setItem" | "removeItem">;

export function merchantTourSeenKey(actorId: string) {
  return `amana.merchant.tourSeen.${actorId}`;
}

export function shouldStartMerchantTour(storage: LocalStorageLike, actorId: string) {
  return storage.getItem(merchantTourSeenKey(actorId)) !== "true";
}

export function markMerchantTourSeen(storage: LocalStorageLike, actorId: string) {
  storage.setItem(merchantTourSeenKey(actorId), "true");
}

export function clearMerchantTourSeen(storage: LocalStorageLike, actorId: string) {
  storage.removeItem(merchantTourSeenKey(actorId));
}

export type TourRect = { top: number; right: number; bottom: number; left: number; width: number; height: number };

export function selectVisibleTourTarget<T extends { rect: TourRect }>(targets: readonly T[]) {
  return targets.find(({ rect }) => rect.width > 0 && rect.height > 0) ?? null;
}

export type TourCardPosition = { left: number; top: number };

export function positionTourCard(
  target: TourRect | null,
  placement: MerchantTourStep["placement"],
  viewport: { width: number; height: number },
  card: { width: number; height: number },
): TourCardPosition {
  const edge = 16;
  const gap = 18;
  const clamp = (value: number, minimum: number, maximum: number) => Math.min(Math.max(value, minimum), Math.max(minimum, maximum));
  if (!target) {
    return {
      left: clamp((viewport.width - card.width) / 2, edge, viewport.width - card.width - edge),
      top: clamp((viewport.height - card.height) / 2, edge, viewport.height - card.height - edge),
    };
  }

  const horizontalTop = clamp(target.top, edge, viewport.height - card.height - edge);
  const verticalLeft = clamp(target.left + (target.width - card.width) / 2, edge, viewport.width - card.width - edge);
  const candidates: Record<MerchantTourStep["placement"], TourCardPosition> = {
    right: { left: target.right + gap, top: horizontalTop },
    left: { left: target.left - card.width - gap, top: horizontalTop },
    below: { left: verticalLeft, top: target.bottom + gap },
    above: { left: verticalLeft, top: target.top - card.height - gap },
  };
  const fits = (position: TourCardPosition) => position.left >= edge
    && position.top >= edge
    && position.left + card.width <= viewport.width - edge
    && position.top + card.height <= viewport.height - edge;
  const order: MerchantTourStep["placement"][] = [placement, "below", "above", "right", "left"];
  const resolved = order.map((direction) => candidates[direction]).find(fits) ?? candidates[placement];
  return {
    left: clamp(resolved.left, edge, viewport.width - card.width - edge),
    top: clamp(resolved.top, edge, viewport.height - card.height - edge),
  };
}
