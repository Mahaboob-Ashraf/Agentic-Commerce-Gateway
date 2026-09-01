package dev.agenticcommerce.gateway.onboarding;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OnboardingModels {
    private OnboardingModels() {}

    public record BuyerProfile(UUID buyerActorId, String recipientName, String phone, String email,
            int version, Instant createdAt, Instant updatedAt) {}

    public record ProfileInput(String recipientName, String phone, String email) {}
    public record AddressInput(String label, String recipientName, String phone, String addressLine1,
            String addressLine2, String locality, String city, String state, String postalCode) {}

    public record BuyerAddress(UUID id, UUID buyerActorId, String label, String recipientName,
            String phone, String addressLine1, String addressLine2, String locality, String city,
            String state, String postalCode, String country, boolean active, boolean selected,
            int version, Instant createdAt, Instant updatedAt) {}

    public enum LinkStatus { LINKED, EXPIRED, REVOKED, FAILED }
    public record MerchantAccountLink(UUID id, UUID buyerActorId, UUID merchantId,
            String externalCustomerReference, String delegatedCredentialReference,
            String linkMethod, int version, String linkHash, LinkStatus status,
            Instant linkedAt, Instant expiresAt, Instant revokedAt) {}
    public record LinkRequest(UUID merchantId, String username, String password) {}

    public record FulfilmentSnapshot(UUID id, UUID buyerActorId, UUID merchantId,
            UUID addressId, int addressVersion, UUID linkId, int linkVersion, String linkHash,
            String externalCustomerReference, String recipientName, String phone,
            String addressLine1, String addressLine2, String locality, String city, String state,
            String postalCode, String country, String deliveryOption, String snapshotHash,
            Instant createdAt) {}

    public record PurchaseFulfilmentAuthority(UUID authorityRefreshId, UUID buyerActorId,
            UUID merchantId, UUID cartId, UUID quoteRecordId, UUID serviceabilityEvidenceId,
            FulfilmentSnapshot snapshot, String bindingHash, Instant createdAt) {}

    public record OnboardingStatus(boolean profileComplete, boolean addressSelected,
            int activeMerchantLinks, boolean ready, UUID selectedAddressId, List<UUID> linkedMerchants) {}
}
