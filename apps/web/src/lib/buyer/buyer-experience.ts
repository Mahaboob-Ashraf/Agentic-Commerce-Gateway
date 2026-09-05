import type { OnboardingStatus } from "./types";

export type BuyerInputMode = "VOICE" | "TEXT";
export type BuyerInputModeEvent = "VOICE_SELECTED" | "TEXT_SELECTED";

export const COMPOSER_MIN_HEIGHT_PX = 48;
export const COMPOSER_MAX_HEIGHT_PX = 192;
export const BUYER_ONBOARDING_SEEN_PREFIX = "amana.buyer.onboardingSeen.";

export const buyerTourSteps = [
  { target: "voice-controls", title: "Start with your voice", body: "A new conversation opens here. You choose when the microphone starts, and can interrupt or leave voice at any time." },
  { target: "composer", title: "Type or show Amana", body: "Add a bounded product image and optional constraints. Visual hints are verified against connected merchant catalogues." },
  { target: "grounding", title: "Grounded commerce", body: "Amana searches connected catalogues, then reloads and checks merchant facts before presenting an option." },
  { target: "proposal-boundary", title: "Review before payment", body: "A purchase proposal is visually distinct. Only your explicit on-screen authorization can open Razorpay Checkout." },
  { target: "orders", title: "Orders and lifecycle", body: "Order tracking and lifecycle actions have a dedicated destination; richer lifecycle controls are still upcoming." },
  { target: "settings", title: "Settings and AutoBuy", body: "Manage your identity, addresses, merchant links, and voice preferences here. Durable AutoBuy controls are upcoming." },
] as const;

export function initialBuyerInputMode(initialThreadId?: string): BuyerInputMode {
  return initialThreadId ? "TEXT" : "VOICE";
}

export function reduceBuyerInputMode(_current: BuyerInputMode, event: BuyerInputModeEvent): BuyerInputMode {
  return event === "VOICE_SELECTED" ? "VOICE" : "TEXT";
}

export function shouldRenderVoiceOrb(mode: BuyerInputMode): boolean {
  return mode === "VOICE";
}

export function boundedComposerHeight(scrollHeight: number): number {
  if (!Number.isFinite(scrollHeight)) return COMPOSER_MIN_HEIGHT_PX;
  return Math.min(COMPOSER_MAX_HEIGHT_PX, Math.max(COMPOSER_MIN_HEIGHT_PX, scrollHeight));
}

export function canEnterBuyer(status: OnboardingStatus | null): boolean {
  return Boolean(status?.profileComplete && status.addressSelected);
}

export function buyerOnboardingSeenKey(actorId: string): string {
  return `${BUYER_ONBOARDING_SEEN_PREFIX}${actorId}`;
}

export function shouldShowBuyerOnboarding(status: OnboardingStatus | null, seenOnDevice: boolean): boolean {
  return !canEnterBuyer(status) || !seenOnDevice;
}

export function nextNewChatGeneration(current: number): number {
  return current + 1;
}

export function buyerWorkspaceKey(generation: number): string {
  return `buyer-workspace-${generation}`;
}

export function commerceThreadHint(threadId: string | null): string | undefined {
  return threadId ?? undefined;
}

export function configuredDemoBuyer(identity: string | undefined, password: string | undefined) {
  const normalizedIdentity = identity?.trim();
  if (!normalizedIdentity || !password) return null;
  return { identity: normalizedIdentity, password };
}
