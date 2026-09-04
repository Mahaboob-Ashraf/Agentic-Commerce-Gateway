import type { Metadata } from "next";
import { BuyerSessionProvider } from "@/components/buyer/buyer-session";

export const metadata: Metadata = {
  title: "Amana Buyer",
  description: "A grounded, buyer-controlled agentic commerce experience.",
};

export default function BuyerLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <BuyerSessionProvider>{children}</BuyerSessionProvider>;
}
