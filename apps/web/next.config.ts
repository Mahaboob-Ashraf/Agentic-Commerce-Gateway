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
      { source: "/api/buyer/:path*", destination: `${backendOrigin}/api/buyer/:path*` },
    ];
  },
};

export default nextConfig;
