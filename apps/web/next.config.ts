import type { NextConfig } from "next";

const backendOrigin = (process.env.AMANA_BACKEND_ORIGIN ?? "http://localhost:8080").replace(
  /\/$/,
  "",
);

const nextConfig: NextConfig = {
  reactStrictMode: true,
  async rewrites() {
    return [
      { source: "/api/auth/:path*", destination: `${backendOrigin}/api/auth/:path*` },
      // Keep Buyer mutations on the same transparent proxy path as authentication. This preserves
      // the browser's session cookie, CSRF header, and multipart boundary/body without buffering
      // and reconstructing uploads in a Route Handler.
      { source: "/api/buyer/:path*", destination: `${backendOrigin}/api/buyer/:path*` },
    ];
  },
};

export default nextConfig;
