import type { SVGProps } from "react";

type IconName =
  | "add"
  | "archive"
  | "arrow"
  | "autobuy"
  | "check"
  | "chevron"
  | "close"
  | "image"
  | "menu"
  | "mic"
  | "orders"
  | "settings"
  | "shield"
  | "spark"
  | "user";

const paths: Record<IconName, React.ReactNode> = {
  add: <path d="M12 5v14M5 12h14" />,
  archive: <path d="M4 7.5h16M6 7.5v11h12v-11M9.5 11.5h5M5 4h14v3.5H5z" />,
  arrow: <path d="m9 18 6-6-6-6M4 12h11" />,
  autobuy: <path d="M6.5 7.5A7 7 0 0 1 19 11h2l-3 3-3-3h2a5 5 0 0 0-8.8-3.2M17.5 16.5A7 7 0 0 1 5 13H3l3-3 3 3H7a5 5 0 0 0 8.8 3.2" />,
  check: <path d="m5 12.5 4.2 4.2L19 7" />,
  chevron: <path d="m9 18 6-6-6-6" />,
  close: <path d="m6 6 12 12M18 6 6 18" />,
  image: <path d="M4 5.5h16v13H4zM8 10a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3Zm-4 6 4.5-4 3 2.5 2.5-2 6 5.5" />,
  menu: <path d="M4 7h16M4 12h16M4 17h16" />,
  mic: <path d="M9 6a3 3 0 0 1 6 0v5a3 3 0 0 1-6 0V6Zm-3 5a6 6 0 0 0 12 0M12 17v3M9 20h6" />,
  orders: <path d="M6 4h12v16l-3-2-3 2-3-2-3 2V4Zm3 5h6M9 13h4" />,
  settings: <path d="M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7Zm7.4 3.5.1-1.5-2-1.1-.5-1.2.6-2.2-2.1-1.2-1.6 1.6-1.3-.2L11 4H8.6l-.7 2.2-1.3.5L4.5 6 3.3 8.1l1.6 1.6-.2 1.3L3 12.5v2.4l2.2.7.5 1.3-.7 2.2 2.1 1.2 1.6-1.6 1.3.2.7 2.1h2.4l.7-2.1 1.3-.5 2.1.7 1.2-2.1-1.6-1.6.2-1.3 2.4-.7V12Z" />,
  shield: <path d="M12 3 5 6v5c0 4.7 2.8 8.1 7 10 4.2-1.9 7-5.3 7-10V6l-7-3Zm-3 9 2 2 4-4" />,
  spark: <path d="m12 3 1.3 4.2L17.5 9l-4.2 1.8L12 15l-1.3-4.2L6.5 9l4.2-1.8L12 3Zm6 12 .6 1.9 1.9.6-1.9.6L18 20l-.6-1.9-1.9-.6 1.9-.6L18 15Z" />,
  user: <path d="M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm-7 8a7 7 0 0 1 14 0" />,
};

export function Icon({ name, ...props }: SVGProps<SVGSVGElement> & { name: IconName }) {
  return (
    <svg
      aria-hidden="true"
      fill="none"
      height="20"
      viewBox="0 0 24 24"
      width="20"
      stroke="currentColor"
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeWidth="1.7"
      {...props}
    >
      {paths[name]}
    </svg>
  );
}
