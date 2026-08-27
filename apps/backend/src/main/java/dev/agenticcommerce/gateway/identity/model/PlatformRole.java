package dev.agenticcommerce.gateway.identity.model;

/** Canonical application roles. These values are persisted verbatim in PostgreSQL. */
public enum PlatformRole {
    BUYER,
    MERCHANT_ADMIN,
    PLATFORM_ADMIN,
    SYSTEM
}
