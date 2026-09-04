import type { ComponentPropsWithoutRef, ReactNode } from "react";
import styles from "./foundation.module.css";

function joinClassNames(...classNames: Array<string | undefined>) {
  return classNames.filter(Boolean).join(" ");
}

type AmanaPageProps = ComponentPropsWithoutRef<"main">;

export function AmanaPage({ children, className, ...props }: AmanaPageProps) {
  return (
    <main className={joinClassNames(styles.page, className)} {...props}>
      <div className={joinClassNames(styles.container, styles.pageInner)}>{children}</div>
    </main>
  );
}

type AmanaSectionProps = ComponentPropsWithoutRef<"section"> & {
  description?: string;
  eyebrow?: string;
  title?: string;
};

export function AmanaSection({
  children,
  className,
  description,
  eyebrow,
  title,
  ...props
}: AmanaSectionProps) {
  return (
    <section className={joinClassNames(styles.section, className)} {...props}>
      {(eyebrow || title || description) && (
        <header className={styles.sectionHeader}>
          {eyebrow && <p className={styles.eyebrow}>{eyebrow}</p>}
          {title && <h1 className={styles.heading}>{title}</h1>}
          {description && <p className={styles.description}>{description}</p>}
        </header>
      )}
      {children}
    </section>
  );
}

type SurfaceProps = ComponentPropsWithoutRef<"div"> & {
  padding?: "none" | "compact" | "comfortable";
  tone?: "default" | "subtle" | "raised";
};

export function Surface({
  children,
  className,
  padding = "compact",
  tone = "default",
  ...props
}: SurfaceProps) {
  return (
    <div
      className={joinClassNames(styles.surface, className)}
      data-padding={padding}
      data-tone={tone}
      {...props}
    >
      {children}
    </div>
  );
}

type AmanaActionLinkProps = ComponentPropsWithoutRef<"a">;

/** Lightweight navigation action for public, progressively enhanced surfaces. */
export function AmanaActionLink({ children, className, ...props }: AmanaActionLinkProps) {
  return (
    <a className={joinClassNames(styles.actionLink, className)} {...props}>
      {children}
    </a>
  );
}

type StatusProps = ComponentPropsWithoutRef<"span"> & {
  tone?: "neutral" | "information" | "positive" | "warning" | "negative";
};

export function Status({ children, className, tone = "neutral", ...props }: StatusProps) {
  return (
    <span className={joinClassNames(styles.status, className)} data-tone={tone} {...props}>
      {children}
    </span>
  );
}

type ProductSurfaceProps = ComponentPropsWithoutRef<"article"> & {
  actions?: ReactNode;
  description?: string;
  layout?: "vertical" | "horizontal";
  media: ReactNode;
  metadata?: ReactNode;
  title: string;
};

export function ProductSurface({
  actions,
  className,
  description,
  layout = "vertical",
  media,
  metadata,
  title,
  ...props
}: ProductSurfaceProps) {
  return (
    <article className={joinClassNames(styles.product, className)} data-layout={layout} {...props}>
      <div className={styles.productMedia}>{media}</div>
      <div className={styles.productBody}>
        {metadata}
        <h2 className={styles.productTitle}>{title}</h2>
        {description && <p className={styles.productDescription}>{description}</p>}
        {actions && <footer className={styles.productFooter}>{actions}</footer>}
      </div>
    </article>
  );
}
