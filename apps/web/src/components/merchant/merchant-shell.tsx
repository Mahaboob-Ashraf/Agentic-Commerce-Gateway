"use client";

import {
  ActivityIcon,
  ClipboardIcon,
  CloseIcon,
  CodeSnippetIcon,
  DashboardIcon,
  FileTextIcon,
  IconButton,
  ListIcon,
  LogOutIcon,
  MenuIcon,
  PackageIcon,
  SettingsIcon,
  ShieldIcon,
  StorefrontIcon,
  Tooltip,
  type IconComponent,
} from "@razorpay/blade/components";
import Link from "next/link";
import Image from "next/image";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { useMerchantSession } from "./merchant-session";
import { MerchantTourProvider } from "./merchant-tour";
import { loadMerchantSetup } from "@/lib/merchant/setup-store";
import type { MerchantSetup } from "@/lib/merchant/types";
import styles from "./merchant-shell.module.css";

type NavItem = { href: string; label: string; icon: IconComponent };

const primaryNav: NavItem[] = [
  { href: "/merchant/overview", label: "Overview", icon: DashboardIcon },
  { href: "/merchant/agentization", label: "Agentization", icon: ActivityIcon },
  { href: "/merchant/catalogue", label: "Catalogue", icon: PackageIcon },
  { href: "/merchant/policies", label: "Policies", icon: ClipboardIcon },
  { href: "/merchant/capabilities", label: "Capabilities", icon: ShieldIcon },
  { href: "/merchant/evidence", label: "Verification / Evidence", icon: ListIcon },
  { href: "/merchant/manifest", label: "Manifest", icon: CodeSnippetIcon },
];

const secondaryNav: NavItem[] = [
  { href: "/merchant/onboarding", label: "Source setup", icon: FileTextIcon },
  { href: "/merchant/settings", label: "Settings", icon: SettingsIcon },
];

function Navigation({ onNavigate }: { onNavigate?: () => void }) {
  const pathname = usePathname();

  function links(items: NavItem[]) {
    return items.map(({ href, label, icon: Icon }) => {
      const active = pathname === href || pathname.startsWith(`${href}/`);
      return (
        <Link
          aria-current={active ? "page" : undefined}
          className={styles.navLink}
          data-active={active || undefined}
          data-tour={href === "/merchant/agentization" ? "agentization-nav" : href === "/merchant/manifest" ? "manifest-nav" : undefined}
          href={href}
          key={href}
          onClick={onNavigate}
        >
          <Icon size="small" />
          <span>{label}</span>
        </Link>
      );
    });
  }

  return (
    <nav className={styles.navigation} aria-label="Merchant navigation">
      <div className={styles.navSection}>
        <p className={styles.navLabel}>Operate</p>
        {links(primaryNav)}
      </div>
      <div className={`${styles.navSection} ${styles.navSectionBottom}`}>
        <p className={styles.navLabel}>Configure</p>
        {links(secondaryNav)}
      </div>
    </nav>
  );
}

export function MerchantShell({ children }: { children: ReactNode }) {
  const {
    actor,
    merchantLoading,
    merchants,
    selectedMerchant,
    selectMerchant,
    signOut,
  } = useMerchantSession();
  const [mobileOpen, setMobileOpen] = useState(false);
  const drawerCloseRef = useRef<HTMLButtonElement>(null);
  const drawerTriggerRef = useRef<HTMLButtonElement | null>(null);
  const [setup] = useState<MerchantSetup | null>(() =>
    actor ? loadMerchantSetup(actor.actorId) : null,
  );

  const storeName = selectedMerchant?.displayName ?? (setup?.store.name.trim() || "Merchant workspace");
  const contextStatus = merchantLoading
    ? "Loading authorized access"
    : selectedMerchant
      ? `Authorized · ${selectedMerchant.merchantKey}`
      : "No merchant membership";
  const hasAmazingLogo = Boolean(
    selectedMerchant
    && (selectedMerchant.merchantKey.toLowerCase().includes("amazing")
      || selectedMerchant.displayName.trim().toLowerCase() === "amazing"),
  );

  useEffect(() => {
    if (!mobileOpen) return;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    window.requestAnimationFrame(() => {
      if (!document.querySelector('[data-testid="merchant-product-tour"]')) drawerCloseRef.current?.focus();
    });
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || document.querySelector('[data-testid="merchant-product-tour"]')) return;
      setMobileOpen(false);
      drawerTriggerRef.current?.focus();
    };
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.body.style.overflow = previousOverflow;
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [mobileOpen]);

  const merchantLogo = (className: string) => hasAmazingLogo ? (
    <span className={className}>
      <Image alt="Amazing" height={32} priority src="/amana/merchant/amazing.png" width={92} />
    </span>
  ) : (
    <span className={`${styles.storeIcon} ${className}`}><StorefrontIcon size="small" /></span>
  );

  const merchantContext = (
    <div className={styles.storeContext} data-tour="merchant-selector">
      {merchantLogo(styles.storeLogo)}
      <span>
        <strong>{storeName}</strong>
        <small>{contextStatus}</small>
      </span>
      {merchants.length > 1 && (
        <select
          aria-label="Choose merchant workspace"
          className={styles.merchantSelect}
          onChange={(event) => selectMerchant(event.target.value)}
          value={selectedMerchant?.merchantId ?? ""}
        >
          {merchants.map((merchant) => (
            <option key={merchant.merchantId} value={merchant.merchantId}>{merchant.displayName}</option>
          ))}
        </select>
      )}
    </div>
  );

  return (
    <MerchantTourProvider
      actorId={actor?.actorId ?? "unavailable"}
      onMobileNavigationChange={setMobileOpen}
      ready={Boolean(actor) && !merchantLoading}
    >
    <div className={styles.shell} data-merchant-shell-root>
      <a className={styles.skipLink} href="#merchant-main">Skip to main content</a>
      <aside className={styles.sidebar}>
        <Link className={styles.brand} href="/merchant/overview" aria-label="Amana Merchant home">
          <Image alt="Amana" className={styles.brandMark} height={36} priority src="/amana/amana-mark.png" width={36} />
          <span className={styles.brandName}>Amana</span>
          <span className={styles.brandProduct}>Merchant</span>
        </Link>
        {merchantContext}
        <Navigation />
        <div className={styles.identity}>
          <span className={styles.avatar}>{actor?.identityHandle.slice(0, 1).toUpperCase()}</span>
          <span className={styles.identityText}>
            <strong>{actor?.identityHandle}</strong>
            <small>Merchant Admin</small>
          </span>
          <button className={styles.signOut} onClick={() => void signOut()} type="button">Sign out</button>
        </div>
      </aside>

      <div className={styles.workspace}>
        <header className={styles.mobileHeader}>
          <Tooltip content="Open merchant navigation" placement="bottom">
            <IconButton
              accessibilityLabel="Open merchant navigation"
              icon={MenuIcon}
              onClick={() => setMobileOpen(true)}
              ref={drawerTriggerRef}
              size="large"
            />
          </Tooltip>
          <Link className={styles.mobileBrand} href="/merchant/overview">
            <Image alt="Amana" className={styles.brandMark} height={36} priority src="/amana/amana-mark.png" width={36} /><span>Amana Merchant</span>
          </Link>
          {hasAmazingLogo ? merchantLogo(styles.mobileMerchantLogo) : <span className={styles.mobileStatus} aria-label={contextStatus} />}
        </header>
        <main className={styles.main} id="merchant-main" tabIndex={-1}>{children}</main>
      </div>

      {mobileOpen && (
        <div className={styles.drawerLayer}>
          <button aria-label="Close merchant navigation" className={styles.drawerBackdrop} onClick={() => setMobileOpen(false)} type="button" />
          <aside aria-label="Merchant navigation" aria-modal="true" className={styles.mobileDrawer} role="dialog">
            <header className={styles.drawerHeader}>
              <span className={styles.drawerBrand}>
                <Image alt="Amana" className={styles.brandMark} height={36} priority src="/amana/amana-mark.png" width={36} />
                <strong>Amana Merchant</strong>
              </span>
              <button aria-label="Close merchant navigation" className={styles.drawerClose} onClick={() => setMobileOpen(false)} ref={drawerCloseRef} type="button">
                <CloseIcon size="small" />
              </button>
            </header>
            <div className={styles.drawerBody}>
          <div className={styles.drawerStore} data-tour="merchant-selector">
            {merchantLogo(styles.drawerStoreLogo)}
            <span><strong>{storeName}</strong><small>{contextStatus}</small></span>
          </div>
          {merchants.length > 1 && (
            <select
              aria-label="Choose merchant workspace"
              className={styles.drawerMerchantSelect}
              onChange={(event) => selectMerchant(event.target.value)}
              value={selectedMerchant?.merchantId ?? ""}
            >
              {merchants.map((merchant) => (
                <option key={merchant.merchantId} value={merchant.merchantId}>{merchant.displayName}</option>
              ))}
            </select>
          )}
          <Navigation onNavigate={() => setMobileOpen(false)} />
            </div>
            <footer className={styles.drawerFooter}>
              <div><span className={styles.avatar}>{actor?.identityHandle.slice(0, 1).toUpperCase()}</span><span><strong>{actor?.identityHandle}</strong><small>Merchant Admin</small></span></div>
              <button onClick={() => { setMobileOpen(false); void signOut(); }} type="button"><LogOutIcon size="small" />Sign out</button>
            </footer>
          </aside>
        </div>
      )}
    </div>
    </MerchantTourProvider>
  );
}
