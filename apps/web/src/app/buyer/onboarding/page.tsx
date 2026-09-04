import { BuyerAuthBoundary } from "@/components/buyer/buyer-session";
import { Onboarding } from "@/components/buyer/onboarding";

export default function OnboardingPage() {
  return <BuyerAuthBoundary><Onboarding /></BuyerAuthBoundary>;
}
