package dev.agenticcommerce.gateway.onboarding;

import static dev.agenticcommerce.gateway.onboarding.OnboardingModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OnboardingRepository {
    private final JdbcClient jdbc;
    public OnboardingRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<BuyerProfile> profile(UUID buyer) {
        return jdbc.sql("SELECT * FROM buyer_profile WHERE buyer_actor_id=:buyer")
                .param("buyer", buyer).query((rs, row) -> profile(rs, row)).optional();
    }
    public BuyerProfile saveProfile(UUID buyer, ProfileInput input, Instant now) {
        return jdbc.sql("""
                INSERT INTO buyer_profile(buyer_actor_id,recipient_name,phone,email,created_at,updated_at)
                VALUES(:buyer,:name,:phone,:email,:now,:now)
                ON CONFLICT (buyer_actor_id) DO UPDATE SET recipient_name=EXCLUDED.recipient_name,
                  phone=EXCLUDED.phone,email=EXCLUDED.email,version=buyer_profile.version+1,updated_at=EXCLUDED.updated_at
                RETURNING *
                """).param("buyer", buyer).param("name", input.recipientName()).param("phone", input.phone())
                .param("email", input.email()).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .query((rs, row) -> profile(rs, row)).single();
    }
    public BuyerAddress addAddress(UUID buyer, AddressInput input, Instant now) {
        return jdbc.sql("""
                INSERT INTO buyer_address(buyer_actor_id,label,recipient_name,phone,address_line_1,address_line_2,
                  locality,city,state,postal_code,country,created_at,updated_at)
                VALUES(:buyer,:label,:name,:phone,:line1,:line2,:locality,:city,:state,:postal,'IN',:now,:now)
                RETURNING *
                """).param("buyer", buyer).param("label", input.label()).param("name", input.recipientName())
                .param("phone", input.phone()).param("line1", input.addressLine1()).param("line2", input.addressLine2())
                .param("locality", input.locality()).param("city", input.city()).param("state", input.state())
                .param("postal", input.postalCode()).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(this::address).single();
    }
    public Optional<BuyerAddress> address(UUID buyer, UUID id) {
        return jdbc.sql("SELECT * FROM buyer_address WHERE buyer_actor_id=:buyer AND address_id=:id")
                .param("buyer", buyer).param("id", id).query(this::address).optional();
    }
    public List<BuyerAddress> addresses(UUID buyer) {
        return jdbc.sql("SELECT * FROM buyer_address WHERE buyer_actor_id=:buyer ORDER BY created_at,address_id")
                .param("buyer", buyer).query(this::address).list();
    }
    public BuyerAddress updateAddress(UUID buyer, UUID id, AddressInput input, Instant now) {
        return jdbc.sql("""
                UPDATE buyer_address SET label=:label,recipient_name=:name,phone=:phone,address_line_1=:line1,
                  address_line_2=:line2,locality=:locality,city=:city,state=:state,postal_code=:postal,
                  version=version+1,updated_at=:now
                WHERE buyer_actor_id=:buyer AND address_id=:id AND active RETURNING *
                """).param("buyer", buyer).param("id", id).param("label", input.label())
                .param("name", input.recipientName()).param("phone", input.phone()).param("line1", input.addressLine1())
                .param("line2", input.addressLine2()).param("locality", input.locality()).param("city", input.city())
                .param("state", input.state()).param("postal", input.postalCode())
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).query(this::address).optional().orElseThrow();
    }
    public BuyerAddress selectAddress(UUID buyer, UUID id, Instant now) {
        jdbc.sql("UPDATE buyer_address SET selected=FALSE,updated_at=:now WHERE buyer_actor_id=:buyer AND selected")
                .param("buyer", buyer).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).update();
        return jdbc.sql("""
                UPDATE buyer_address SET selected=TRUE,updated_at=:now
                WHERE buyer_actor_id=:buyer AND address_id=:id AND active RETURNING *
                """).param("buyer", buyer).param("id", id).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(this::address).optional().orElseThrow();
    }
    public Optional<BuyerAddress> selectedAddress(UUID buyer) {
        return jdbc.sql("SELECT * FROM buyer_address WHERE buyer_actor_id=:buyer AND selected AND active")
                .param("buyer", buyer).query(this::address).optional();
    }
    public int nextLinkVersion(UUID buyer, UUID merchant) {
        return jdbc.sql("SELECT COALESCE(MAX(link_version),0)+1 FROM merchant_account_link WHERE buyer_actor_id=:buyer AND merchant_id=:merchant")
                .param("buyer", buyer).param("merchant", merchant).query(Integer.class).single();
    }
    public MerchantAccountLink saveLink(UUID buyer, UUID merchant, MerchantCustomerLinkProvider.LinkResult result,
            int version, String hash, Instant now) {
        jdbc.sql("""
                UPDATE merchant_account_link SET status='REVOKED',revoked_at=:now
                WHERE buyer_actor_id=:buyer AND merchant_id=:merchant AND status='LINKED'
                """).param("buyer", buyer).param("merchant", merchant)
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).update();
        return jdbc.sql("""
                INSERT INTO merchant_account_link(buyer_actor_id,merchant_id,external_customer_reference,
                  delegated_credential_reference,link_method,link_version,link_hash,status,linked_at,expires_at)
                VALUES(:buyer,:merchant,:customer,:credential,:method,:version,:hash,'LINKED',:now,:expires)
                RETURNING *
                """).param("buyer", buyer).param("merchant", merchant)
                .param("customer", result.externalCustomerReference()).param("credential", result.delegatedCredentialReference())
                .param("method", result.method()).param("version", version).param("hash", hash)
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("expires", utc(result.expiresAt()), Types.TIMESTAMP_WITH_TIMEZONE).query(this::link).single();
    }
    public Optional<MerchantAccountLink> activeLink(UUID buyer, UUID merchant, Instant now) {
        return jdbc.sql("""
                SELECT * FROM merchant_account_link WHERE buyer_actor_id=:buyer AND merchant_id=:merchant
                  AND status='LINKED' AND (expires_at IS NULL OR expires_at>:now)
                """).param("buyer", buyer).param("merchant", merchant)
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).query(this::link).optional();
    }
    public List<MerchantAccountLink> links(UUID buyer) {
        return jdbc.sql("SELECT * FROM merchant_account_link WHERE buyer_actor_id=:buyer ORDER BY linked_at DESC")
                .param("buyer", buyer).query(this::link).list();
    }
    public MerchantAccountLink revoke(UUID buyer, UUID id, Instant now) {
        return jdbc.sql("""
                UPDATE merchant_account_link SET status='REVOKED',revoked_at=:now
                WHERE buyer_actor_id=:buyer AND merchant_account_link_id=:id AND status='LINKED' RETURNING *
                """).param("buyer", buyer).param("id", id).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(this::link).optional().orElseThrow();
    }
    public FulfilmentSnapshot insertSnapshot(UUID buyer, UUID merchant, BuyerAddress address,
            MerchantAccountLink link, String option, String hash, Instant now) {
        return jdbc.sql("""
                INSERT INTO fulfilment_snapshot(buyer_actor_id,merchant_id,source_address_id,source_address_version,
                  merchant_account_link_id,merchant_account_link_version,merchant_account_link_hash,
                  external_customer_reference,recipient_name,phone,address_line_1,address_line_2,locality,city,
                  state,postal_code,country,delivery_option,snapshot_hash,created_at)
                VALUES(:buyer,:merchant,:address,:addressVersion,:link,:linkVersion,:linkHash,:customer,:name,
                  :phone,:line1,:line2,:locality,:city,:state,:postal,'IN',:option,:hash,:now) RETURNING *
                """).param("buyer", buyer).param("merchant", merchant).param("address", address.id())
                .param("addressVersion", address.version()).param("link", link.id()).param("linkVersion", link.version())
                .param("linkHash", link.linkHash()).param("customer", link.externalCustomerReference())
                .param("name", address.recipientName()).param("phone", address.phone()).param("line1", address.addressLine1())
                .param("line2", address.addressLine2()).param("locality", address.locality()).param("city", address.city())
                .param("state", address.state()).param("postal", address.postalCode()).param("option", option)
                .param("hash", hash).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .query((rs, row) -> snapshot(rs, row)).single();
    }
    public Optional<FulfilmentSnapshot> snapshot(UUID id) {
        return jdbc.sql("SELECT * FROM fulfilment_snapshot WHERE fulfilment_snapshot_id=:id")
                .param("id", id).query((rs, row) -> snapshot(rs, row)).optional();
    }
    public void bindAuthority(UUID refresh, UUID buyer, UUID merchant, UUID cart, UUID quote, UUID serviceability,
            FulfilmentSnapshot snapshot, String hash, Instant now) {
        jdbc.sql("""
                INSERT INTO purchase_fulfilment_authority(authority_refresh_id,buyer_actor_id,merchant_id,cart_id,
                  quote_record_id,serviceability_evidence_id,fulfilment_snapshot_id,fulfilment_snapshot_hash,
                  merchant_account_link_id,merchant_account_link_version,merchant_account_link_hash,
                  delivery_option,binding_hash,created_at)
                VALUES(:refresh,:buyer,:merchant,:cart,:quote,:serviceability,:snapshot,:snapshotHash,:link,
                  :linkVersion,:linkHash,:option,:hash,:now)
                """).param("refresh", refresh).param("buyer", buyer).param("merchant", merchant).param("cart", cart)
                .param("quote", quote).param("serviceability", serviceability).param("snapshot", snapshot.id())
                .param("snapshotHash", snapshot.snapshotHash()).param("link", snapshot.linkId())
                .param("linkVersion", snapshot.linkVersion()).param("linkHash", snapshot.linkHash())
                .param("option", snapshot.deliveryOption()).param("hash", hash)
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).update();
    }
    public Optional<PurchaseFulfilmentAuthority> authority(UUID refresh) {
        return jdbc.sql("""
                SELECT authority.*,snapshot.* FROM purchase_fulfilment_authority authority
                JOIN fulfilment_snapshot snapshot ON snapshot.fulfilment_snapshot_id=authority.fulfilment_snapshot_id
                WHERE authority.authority_refresh_id=:refresh
                """).param("refresh", refresh).query((rs,row) -> new PurchaseFulfilmentAuthority(
                        rs.getObject("authority_refresh_id", UUID.class), rs.getObject("buyer_actor_id", UUID.class),
                        rs.getObject("merchant_id", UUID.class), rs.getObject("cart_id", UUID.class),
                        rs.getObject("quote_record_id", UUID.class), rs.getObject("serviceability_evidence_id", UUID.class),
                        snapshot(rs,row), rs.getString("binding_hash").strip(), instant(rs,"created_at"))).optional();
    }
    public void bindProposal(UUID proposal, PurchaseFulfilmentAuthority authority, Instant now) {
        FulfilmentSnapshot s=authority.snapshot();
        jdbc.sql("""
                INSERT INTO transaction_proposal_fulfilment(proposal_id,buyer_actor_id,merchant_id,
                  authority_refresh_id,fulfilment_snapshot_id,fulfilment_snapshot_hash,merchant_account_link_id,
                  merchant_account_link_version,merchant_account_link_hash,delivery_option,binding_hash,created_at)
                VALUES(:proposal,:buyer,:merchant,:refresh,:snapshot,:snapshotHash,:link,:linkVersion,:linkHash,
                  :option,:hash,:now)
                """).param("proposal", proposal).param("buyer", authority.buyerActorId()).param("merchant", authority.merchantId())
                .param("refresh", authority.authorityRefreshId()).param("snapshot", s.id()).param("snapshotHash", s.snapshotHash())
                .param("link", s.linkId()).param("linkVersion", s.linkVersion()).param("linkHash", s.linkHash())
                .param("option", s.deliveryOption()).param("hash", authority.bindingHash())
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).update();
    }
    public boolean validProposalBinding(UUID proposal,UUID buyer,UUID merchant,UUID serviceability,Instant now){
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM transaction_proposal_fulfilment binding
                  JOIN fulfilment_snapshot snapshot ON snapshot.fulfilment_snapshot_id=binding.fulfilment_snapshot_id
                  JOIN merchant_account_link link ON link.merchant_account_link_id=binding.merchant_account_link_id
                  JOIN authoritative_serviceability_evidence service ON service.serviceability_evidence_id=:serviceability
                  WHERE binding.proposal_id=:proposal AND binding.buyer_actor_id=:buyer AND binding.merchant_id=:merchant
                    AND snapshot.buyer_actor_id=:buyer AND snapshot.merchant_id=:merchant
                    AND link.buyer_actor_id=:buyer AND link.merchant_id=:merchant AND link.status='LINKED'
                    AND (link.expires_at IS NULL OR link.expires_at>:now)
                    AND link.link_version=binding.merchant_account_link_version
                    AND link.link_hash=binding.merchant_account_link_hash
                    AND service.fulfilment_snapshot_id=binding.fulfilment_snapshot_id
                    AND service.fulfilment_snapshot_hash=binding.fulfilment_snapshot_hash
                    AND service.delivery_option=binding.delivery_option)
                """).param("serviceability",serviceability).param("proposal",proposal).param("buyer",buyer)
                .param("merchant",merchant).param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).query(Boolean.class).single();
    }

    private BuyerProfile profile(ResultSet rs,int row) throws SQLException { return new BuyerProfile(
            rs.getObject("buyer_actor_id",UUID.class),rs.getString("recipient_name"),rs.getString("phone"),
            rs.getString("email"),rs.getInt("version"),instant(rs,"created_at"),instant(rs,"updated_at")); }
    private BuyerAddress address(ResultSet rs,int row) throws SQLException { return new BuyerAddress(
            rs.getObject("address_id",UUID.class),rs.getObject("buyer_actor_id",UUID.class),rs.getString("label"),
            rs.getString("recipient_name"),rs.getString("phone"),rs.getString("address_line_1"),
            rs.getString("address_line_2"),rs.getString("locality"),rs.getString("city"),rs.getString("state"),
            rs.getString("postal_code"),rs.getString("country"),rs.getBoolean("active"),rs.getBoolean("selected"),
            rs.getInt("version"),instant(rs,"created_at"),instant(rs,"updated_at")); }
    private MerchantAccountLink link(ResultSet rs,int row) throws SQLException { return new MerchantAccountLink(
            rs.getObject("merchant_account_link_id",UUID.class),rs.getObject("buyer_actor_id",UUID.class),
            rs.getObject("merchant_id",UUID.class),rs.getString("external_customer_reference"),
            rs.getString("delegated_credential_reference"),rs.getString("link_method"),rs.getInt("link_version"),
            rs.getString("link_hash").strip(),LinkStatus.valueOf(rs.getString("status")),instant(rs,"linked_at"),
            instant(rs,"expires_at"),instant(rs,"revoked_at")); }
    private FulfilmentSnapshot snapshot(ResultSet rs,int row) throws SQLException { return new FulfilmentSnapshot(
            rs.getObject("fulfilment_snapshot_id",UUID.class),rs.getObject("buyer_actor_id",UUID.class),
            rs.getObject("merchant_id",UUID.class),rs.getObject("source_address_id",UUID.class),
            rs.getInt("source_address_version"),rs.getObject("merchant_account_link_id",UUID.class),
            rs.getInt("merchant_account_link_version"),rs.getString("merchant_account_link_hash").strip(),
            rs.getString("external_customer_reference"),rs.getString("recipient_name"),rs.getString("phone"),
            rs.getString("address_line_1"),rs.getString("address_line_2"),rs.getString("locality"),rs.getString("city"),
            rs.getString("state"),rs.getString("postal_code"),rs.getString("country"),rs.getString("delivery_option"),
            rs.getString("snapshot_hash").strip(),instant(rs,"created_at")); }
    private static OffsetDateTime utc(Instant value){return value==null?null:value.atOffset(ZoneOffset.UTC);}
    private static Instant instant(ResultSet rs,String column)throws SQLException{OffsetDateTime v=rs.getObject(column,OffsetDateTime.class);return v==null?null:v.toInstant();}
}
