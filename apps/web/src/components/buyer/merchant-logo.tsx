export function MerchantLogo({ name, logoUrl, className }: { name: string; logoUrl: string | null | undefined; className?: string }) {
  const image = safeMerchantLogoUrl(logoUrl);
  return <span className={className} data-has-logo={Boolean(image)} aria-hidden="true">
    <span>{name.trim().slice(0, 1).toUpperCase() || "M"}</span>
    {image && (
      // Merchant presentation URLs are application-owned metadata and may use approved HTTPS hosts.
      // eslint-disable-next-line @next/next/no-img-element
      <img src={image} alt="" loading="eager" decoding="async" referrerPolicy="no-referrer" onError={(event) => { event.currentTarget.remove(); event.currentTarget.parentElement?.setAttribute("data-has-logo", "false"); }} />
    )}
  </span>;
}

export function safeMerchantLogoUrl(value: string | null | undefined): string | null {
  if (!value) return null;
  return (value.startsWith("/") && !value.startsWith("//")) || value.startsWith("https://") ? value : null;
}
