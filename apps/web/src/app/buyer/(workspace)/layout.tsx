import { BuyerAuthBoundary } from "@/components/buyer/buyer-session";
import { BuyerShell } from "@/components/buyer/buyer-shell";

export default function BuyerWorkspaceLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <BuyerAuthBoundary><BuyerShell>{children}</BuyerShell></BuyerAuthBoundary>;
}
