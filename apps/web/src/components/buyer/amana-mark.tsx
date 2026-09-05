import Image from "next/image";

export function AmanaMark({ className, priority = false }: { className?: string; priority?: boolean }) {
  return (
    <span className={className} aria-hidden="true">
      <Image src="/amana/amana-mark.png" alt="" width={40} height={40} priority={priority} />
    </span>
  );
}
