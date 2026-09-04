"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { buyerApi } from "@/lib/buyer/api";
import type { CommerceThread } from "@/lib/buyer/types";
import { Icon } from "./icons";
import { ProductTour } from "./product-tour";
import { useBuyerSession } from "./buyer-session";
import styles from "./buyer-shell.module.css";

const navigation = [
  { href: "/buyer/chat", label: "New Chat", icon: "add", tour: "new-chat" },
  { href: "/buyer/conversations", label: "Conversations", icon: "archive", tour: "conversations" },
  { href: "/buyer/orders", label: "Orders", icon: "orders", tour: "orders" },
  { href: "/buyer/autobuy", label: "AutoBuy", icon: "autobuy", tour: "autobuy" },
  { href: "/buyer/settings/profile", label: "Settings", icon: "settings", tour: "settings" },
] as const;

function isActive(pathname: string, href: string) {
  if (href === "/buyer/chat") return pathname === href;
  return pathname.startsWith(href);
}

export function BuyerShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const { actor, signOut } = useBuyerSession();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [threads, setThreads] = useState<CommerceThread[]>([]);
  const menuButton = useRef<HTMLButtonElement>(null);
  const drawer = useRef<HTMLElement>(null);

  useEffect(() => {
    buyerApi.threads().then(setThreads).catch(() => setThreads([]));
  }, [pathname]);

  useEffect(() => {
    buyerApi.onboardingStatus().then((nextStatus) => {
      if (!nextStatus.profileComplete || !nextStatus.addressSelected) {
        router.replace("/buyer/onboarding");
      }
    }).catch(() => {
      // The authenticated shell remains available when onboarding status is temporarily unreadable.
    });
  }, [router]);

  useEffect(() => {
    if (!drawerOpen) return;
    drawer.current?.querySelector<HTMLButtonElement>("button")?.focus();
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setDrawerOpen(false);
        menuButton.current?.focus();
      }
    };
    document.addEventListener("keydown", closeOnEscape);
    return () => document.removeEventListener("keydown", closeOnEscape);
  }, [drawerOpen]);

  const initial = actor?.identityHandle.slice(0, 1).toUpperCase() ?? "A";

  const sidebar = (
    <div className={styles.sidebarInner}>
      <div className={styles.sidebarTop}>
        <Link className={styles.brand} href="/buyer/chat" aria-label="Amana Buyer">
          <span className={styles.brandMark}>A</span>
          <span><strong>Amana</strong><small>Buyer</small></span>
        </Link>
        <button className={styles.drawerClose} onClick={() => setDrawerOpen(false)} aria-label="Close navigation">
          <Icon name="close" />
        </button>
      </div>

      <nav className={styles.primaryNav} aria-label="Buyer navigation">
        {navigation.map((item) => (
          <Link
            className={styles.navItem}
            data-active={isActive(pathname, item.href)}
            data-tour={item.tour}
            href={item.href}
            key={item.href}
            onClick={() => setDrawerOpen(false)}
          >
            <Icon name={item.icon} />
            <span>{item.label}</span>
            {item.label === "AutoBuy" && <em>Soon</em>}
          </Link>
        ))}
      </nav>

      <section className={styles.recentSection} aria-labelledby="recent-conversations-title">
        <div className={styles.recentHeading}>
          <span id="recent-conversations-title">Recent</span>
          <Link href="/buyer/conversations" aria-label="View all conversations"><Icon name="arrow" /></Link>
        </div>
        {threads.length === 0 ? (
          <p className={styles.recentEmpty}>Your conversations will appear here.</p>
        ) : (
          <ol className={styles.recentList}>
            {threads.slice(0, 5).map((thread) => (
              <li key={thread.threadId}>
                <Link href={`/buyer/chat/${thread.threadId}`} data-active={pathname.endsWith(thread.threadId)}>
                  <span>{thread.title}</span>
                  <time dateTime={thread.updatedAt}>{relativeTime(thread.updatedAt)}</time>
                </Link>
              </li>
            ))}
          </ol>
        )}
      </section>

      <div className={styles.account}>
        <span className={styles.avatar}>{initial}</span>
        <span><strong>{actor?.identityHandle}</strong><small>Buyer account</small></span>
        <button onClick={() => void signOut()} aria-label="Sign out">Sign out</button>
      </div>
    </div>
  );

  return (
    <div className={styles.shell}>
      <aside className={styles.sidebar}>{sidebar}</aside>
      <div className={styles.mobileHeader}>
        <button ref={menuButton} onClick={() => setDrawerOpen(true)} aria-expanded={drawerOpen} aria-controls="buyer-mobile-nav" aria-label="Open navigation"><Icon name="menu" /></button>
        <Link className={styles.mobileBrand} href="/buyer/chat"><span className={styles.brandMark}>A</span>Amana</Link>
        <span className={styles.mobileAvatar}>{initial}</span>
      </div>
      {drawerOpen && <button className={styles.scrim} onClick={() => setDrawerOpen(false)} aria-label="Close navigation" />}
      <aside className={styles.drawer} data-open={drawerOpen} id="buyer-mobile-nav" aria-hidden={!drawerOpen} aria-label="Buyer navigation" aria-modal={drawerOpen ? "true" : undefined} inert={!drawerOpen} ref={drawer} role={drawerOpen ? "dialog" : undefined}>{sidebar}</aside>
      <main className={styles.workspace}>{children}</main>
      <nav className={styles.bottomNav} aria-label="Mobile buyer navigation">
        {navigation.filter((item) => item.label !== "AutoBuy").map((item) => (
          <Link data-active={isActive(pathname, item.href)} data-tour={item.tour} href={item.href} key={item.href}>
            <Icon name={item.icon} /><span>{item.label === "Conversations" ? "History" : item.label}</span>
          </Link>
        ))}
      </nav>
      <ProductTour />
    </div>
  );
}

function relativeTime(value: string) {
  const elapsed = Date.now() - new Date(value).getTime();
  const minutes = Math.max(0, Math.floor(elapsed / 60_000));
  if (minutes < 1) return "Now";
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h`;
  return `${Math.floor(hours / 24)}d`;
}
