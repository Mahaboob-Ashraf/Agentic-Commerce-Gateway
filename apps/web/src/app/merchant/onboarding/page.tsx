import { MerchantOnboarding } from "@/components/merchant/merchant-onboarding";
import { MerchantAuthBoundary } from "@/components/merchant/merchant-session";

export default function MerchantOnboardingPage() {
  return <MerchantAuthBoundary><MerchantOnboarding /></MerchantAuthBoundary>;
}
