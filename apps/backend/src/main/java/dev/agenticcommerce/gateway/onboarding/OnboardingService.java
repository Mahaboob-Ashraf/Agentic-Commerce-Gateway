package dev.agenticcommerce.gateway.onboarding;

import static dev.agenticcommerce.gateway.onboarding.OnboardingModels.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import dev.agenticcommerce.gateway.commerce.TransactionModels.TransactionProposal;
import dev.agenticcommerce.gateway.lifecycle.LifecycleAuditService;

@Service
public class OnboardingService {
    private final OnboardingRepository repository;
    private final MerchantCustomerLinkProvider links;
    private final CanonicalJsonService canonical;
    private final ObjectMapper mapper;
    private final LifecycleAuditService audit;
    public OnboardingService(OnboardingRepository repository, MerchantCustomerLinkProvider links,
            CanonicalJsonService canonical, ObjectMapper mapper,LifecycleAuditService audit) {
        this.repository=repository; this.links=links; this.canonical=canonical; this.mapper=mapper;this.audit=audit;
    }
    public BuyerProfile profile(UUID buyer) { return repository.profile(buyer).orElseThrow(() -> notFound("BUYER_PROFILE_NOT_FOUND")); }
    public BuyerProfile updateProfile(UUID buyer, ProfileInput input) {
        requireText(input==null?null:input.recipientName(),160,"recipientName");
        requireText(input.phone(),32,"phone"); requireText(input.email(),320,"email");
        if (!input.email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw bad("EMAIL_INVALID");
        return repository.saveProfile(buyer, normalizedProfile(input), Instant.now());
    }
    public BuyerAddress addAddress(UUID buyer, AddressInput input) {
        validate(input); return repository.addAddress(buyer, normalize(input), Instant.now());
    }
    public BuyerAddress updateAddress(UUID buyer, UUID id, AddressInput input) {
        validate(input); repository.address(buyer,id).orElseThrow(() -> notFound("ADDRESS_NOT_FOUND"));
        return repository.updateAddress(buyer,id,normalize(input),Instant.now());
    }
    public List<BuyerAddress> addresses(UUID buyer){return repository.addresses(buyer);}
    @Transactional public BuyerAddress selectAddress(UUID buyer,UUID id){
        repository.address(buyer,id).orElseThrow(() -> notFound("ADDRESS_NOT_FOUND"));
        BuyerAddress selected=repository.selectAddress(buyer,id,Instant.now());audit.record(buyer,null,null,"ADDRESS_SELECTED",id.toString(),audit.reference("addressVersion",Integer.toString(selected.version())));return selected;
    }
    @Transactional public MerchantAccountLink link(UUID buyer, LinkRequest request) {
        if(request==null||request.merchantId()==null) throw bad("MERCHANT_LINK_REQUEST_INVALID");
        requireText(request.username(),256,"username"); requireText(request.password(),1024,"password");
        char[] secret=request.password().toCharArray();
        MerchantCustomerLinkProvider.LinkResult result;
        try { result=links.exchange(request.merchantId(),request.username().strip(),secret); }
        finally { Arrays.fill(secret,'\0'); }
        if(result==null||!result.valid()||result.externalCustomerReference()==null
                ||result.delegatedCredentialReference()==null)
            throw new OnboardingException("MERCHANT_LINK_FAILED",HttpStatus.UNAUTHORIZED,
                    "Merchant account credentials were not accepted");
        int version=repository.nextLinkVersion(buyer,request.merchantId());
        var material=mapper.createObjectNode(); material.put("buyerActorId",buyer.toString());
        material.put("merchantId",request.merchantId().toString()); material.put("version",version);
        material.put("customerReference",result.externalCustomerReference());
        material.put("credentialReference",result.delegatedCredentialReference()); material.put("method",result.method());
        if(result.expiresAt()!=null)material.put("expiresAt",result.expiresAt().toString());
        MerchantAccountLink linked=repository.saveLink(buyer,request.merchantId(),result,version,canonical.hash(material),Instant.now());
        audit.record(buyer,request.merchantId(),null,"MERCHANT_ACCOUNT_LINKED",linked.id().toString(),audit.reference("linkHash",linked.linkHash()));return linked;
    }
    public List<MerchantAccountLink> links(UUID buyer){return repository.links(buyer);}
    @Transactional public MerchantAccountLink revoke(UUID buyer,UUID id){
        try{MerchantAccountLink revoked=repository.revoke(buyer,id,Instant.now());audit.record(buyer,revoked.merchantId(),null,"MERCHANT_ACCOUNT_REVOKED",id.toString(),audit.reference("status","REVOKED"));return revoked;}catch(RuntimeException e){throw notFound("MERCHANT_LINK_NOT_FOUND");}
    }
    public OnboardingStatus status(UUID buyer){
        boolean profile=repository.profile(buyer).isPresent(); var selected=repository.selectedAddress(buyer);
        List<MerchantAccountLink> active=repository.links(buyer).stream().filter(v->v.status()==LinkStatus.LINKED
                &&(v.expiresAt()==null||v.expiresAt().isAfter(Instant.now()))).toList();
        return new OnboardingStatus(profile,selected.isPresent(),active.size(),profile&&selected.isPresent()&&!active.isEmpty(),
                selected.map(BuyerAddress::id).orElse(null),active.stream().map(MerchantAccountLink::merchantId).distinct().toList());
    }
    @Transactional public FulfilmentSnapshot createSnapshot(UUID buyer,UUID merchant,String deliveryOption){
        repository.profile(buyer).orElseThrow(() -> conflict("BUYER_PROFILE_REQUIRED"));
        BuyerAddress address=repository.selectedAddress(buyer).orElseThrow(() -> conflict("DELIVERY_ADDRESS_REQUIRED"));
        MerchantAccountLink link=repository.activeLink(buyer,merchant,Instant.now())
                .orElseThrow(() -> conflict("ACTIVE_MERCHANT_LINK_REQUIRED"));
        String option=normalizeOption(deliveryOption); var m=mapper.createObjectNode();
        m.put("buyerActorId",buyer.toString());m.put("merchantId",merchant.toString());
        m.put("addressId",address.id().toString());m.put("addressVersion",address.version());
        m.put("recipientName",address.recipientName());m.put("phone",address.phone());
        m.put("addressLine1",address.addressLine1());if(address.addressLine2()==null)m.putNull("addressLine2");else m.put("addressLine2",address.addressLine2());
        m.put("locality",address.locality());m.put("city",address.city());m.put("state",address.state());
        m.put("postalCode",address.postalCode());m.put("country","IN");m.put("deliveryOption",option);
        m.put("merchantAccountLinkId",link.id().toString());m.put("merchantAccountLinkVersion",link.version());
        m.put("merchantAccountLinkHash",link.linkHash());m.put("externalCustomerReference",link.externalCustomerReference());
        FulfilmentSnapshot snapshot=repository.insertSnapshot(buyer,merchant,address,link,option,canonical.hash(m),Instant.now());
        audit.record(buyer,merchant,null,"FULFILMENT_SNAPSHOT_CREATED",snapshot.id().toString(),audit.reference("snapshotHash",snapshot.snapshotHash()));return snapshot;
    }
    public PurchaseFulfilmentAuthority requireAuthority(UUID refresh){return repository.authority(refresh)
            .orElseThrow(()->conflict("PURCHASE_FULFILMENT_AUTHORITY_REQUIRED"));}
    public void bindAuthority(UUID refresh,UUID buyer,UUID merchant,UUID cart,UUID quote,UUID serviceability,
            FulfilmentSnapshot snapshot){var m=mapper.createObjectNode();m.put("authorityRefreshId",refresh.toString());
        m.put("cartId",cart.toString());m.put("quoteRecordId",quote.toString());m.put("serviceabilityEvidenceId",serviceability.toString());
        m.put("snapshotHash",snapshot.snapshotHash());m.put("linkHash",snapshot.linkHash());
        repository.bindAuthority(refresh,buyer,merchant,cart,quote,serviceability,snapshot,canonical.hash(m),Instant.now());}
    public void bindProposal(UUID proposal,PurchaseFulfilmentAuthority authority){repository.bindProposal(proposal,authority,Instant.now());}
    public boolean validProposalBinding(TransactionProposal proposal){
        var f=proposal.canonicalMaterial().path("fulfilmentAuthority");
        if(!f.isObject())return false;
        boolean material=f.path("snapshotId").asText().matches("[0-9a-f-]{36}")
                &&f.path("snapshotHash").asText().matches("[0-9a-f]{64}")
                &&f.path("merchantAccountLinkId").asText().matches("[0-9a-f-]{36}")
                &&f.path("merchantAccountLinkHash").asText().matches("[0-9a-f]{64}")
                &&!f.path("deliveryOption").asText().isBlank();
        return material&&repository.validProposalBinding(proposal.proposalId(),proposal.buyerActorId(),
                proposal.merchantId(),proposal.serviceabilityEvidenceId(),Instant.now());
    }
    private static void validate(AddressInput i){if(i==null)throw bad("ADDRESS_INVALID");
        requireText(i.label(),64,"label");requireText(i.recipientName(),160,"recipientName");requireText(i.phone(),32,"phone");
        requireText(i.addressLine1(),256,"addressLine1");requireText(i.locality(),128,"locality");
        requireText(i.city(),128,"city");requireText(i.state(),128,"state");
        if(i.postalCode()==null||!i.postalCode().matches("^[1-9][0-9]{5}$"))throw bad("POSTAL_CODE_INVALID");
        if(i.addressLine2()!=null&&i.addressLine2().strip().length()>256)throw bad("ADDRESS_INVALID");}
    private static AddressInput normalize(AddressInput i){return new AddressInput(s(i.label()),s(i.recipientName()),s(i.phone()),s(i.addressLine1()),
            i.addressLine2()==null?null:s(i.addressLine2()),s(i.locality()),s(i.city()),s(i.state()),i.postalCode());}
    private static ProfileInput normalizedProfile(ProfileInput i){return new ProfileInput(s(i.recipientName()),s(i.phone()),s(i.email()).toLowerCase(Locale.ROOT));}
    private static String normalizeOption(String v){String x=v==null?"STANDARD":v.strip().toUpperCase(Locale.ROOT);if(!x.matches("[A-Z0-9_]{1,64}"))throw bad("DELIVERY_OPTION_INVALID");return x;}
    private static void requireText(String v,int max,String f){if(v==null||v.isBlank()||v.strip().length()>max)throw bad(f.toUpperCase(Locale.ROOT)+"_INVALID");}
    private static String s(String v){return v.strip();}
    private static OnboardingException bad(String code){return new OnboardingException(code,HttpStatus.BAD_REQUEST,"Onboarding input is invalid");}
    private static OnboardingException notFound(String code){return new OnboardingException(code,HttpStatus.NOT_FOUND,"Onboarding record was not found");}
    private static OnboardingException conflict(String code){return new OnboardingException(code,HttpStatus.CONFLICT,"Required buyer onboarding authority is unavailable");}
}
