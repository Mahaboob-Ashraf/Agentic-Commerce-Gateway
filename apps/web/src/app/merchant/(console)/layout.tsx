import { MerchantAuthBoundary } from "@/components/merchant/merchant-session";
import { MerchantShell } from "@/components/merchant/merchant-shell";

export default function MerchantConsoleLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <MerchantAuthBoundary><MerchantShell>{children}</MerchantShell></MerchantAuthBoundary>;
}
