import type { Metadata } from "next";
import "@razorpay/blade/fonts.css";
import "./globals.css";

export const metadata: Metadata = {
  title: "Amana",
  description: "Agentic commerce you can trust.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
