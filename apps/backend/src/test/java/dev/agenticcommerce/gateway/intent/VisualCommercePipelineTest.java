package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.intent.BuyerModels.*;
import static dev.agenticcommerce.gateway.intent.CommerceRequestModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.catalogue.CatalogueRepository;
import dev.agenticcommerce.gateway.commerce.AuthoritativeRefreshService;
import dev.agenticcommerce.gateway.commerce.TransactionAuthorityRepository;
import dev.agenticcommerce.gateway.commerce.TransactionProposalService;
import dev.agenticcommerce.gateway.risk.ReversibilityService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class VisualCommercePipelineTest {
    @Test void visualHypothesisIsPersistedThenEntersTheExistingOrchestrationWithoutProposalAuthority(){
        CommerceRequestRepository requests=mock(CommerceRequestRepository.class);BuyerThreadService threads=mock(BuyerThreadService.class);
        BuyerOrchestrationService orchestration=mock(BuyerOrchestrationService.class);BuyerRepository buyers=mock(BuyerRepository.class);
        CatalogueRepository catalogues=mock(CatalogueRepository.class);TransactionAuthorityRepository authority=mock(TransactionAuthorityRepository.class);
        VisionObservationRepository observations=mock(VisionObservationRepository.class);UUID buyer=UUID.randomUUID(),request=UUID.randomUUID(),threadId=UUID.randomUUID(),messageId=UUID.randomUUID();
        Instant now=Instant.parse("2026-09-05T00:00:00Z");CommerceThread understanding=thread(threadId,buyer,BuyerState.UNDERSTANDING,now),waiting=thread(threadId,buyer,BuyerState.WAITING_FOR_USER,now);
        ThreadMessage message=new ThreadMessage(messageId,threadId,buyer,1,"IMAGE_TEXT","Find something like this under ₹4,000","a".repeat(64),now);
        var observation=new VisualCommerceModels.VisionObservation("Footwear","low-top sneaker",null,null,List.of("white"),List.of("canvas"),List.of("lifestyle"),List.of(),new BigDecimal("0.9"),List.of("brand unknown"));
        VisionObservationProvider provider=(image,text)->new VisionObservationProvider.Observed(observation,"TEST","gemini-3.5-flash-lite");
        var created=new CommerceRequestRecord(UUID.randomUUID(),request,buyer,null,null,message.normalizedText(),"b".repeat(64),RequestStatus.RUNNING,null,null,now,now,null);
        when(requests.create(eq(buyer),eq(request),isNull(),eq(message.normalizedText()),anyString())).thenReturn(Optional.of(created));when(threads.createVisual(buyer,message.normalizedText())).thenReturn(understanding);
        when(buyers.latestMessage(buyer,threadId)).thenReturn(Optional.of(message));when(threads.require(buyer,threadId)).thenReturn(understanding,waiting,waiting,waiting);
        when(orchestration.advanceWithinRequest(buyer,threadId)).thenReturn(new AdvanceResult(waiting,null));when(buyers.latestIntent(buyer,threadId)).thenReturn(Optional.empty());
        when(buyers.currentCart(buyer,threadId)).thenReturn(Optional.empty());when(buyers.currentQuote(buyer,threadId)).thenReturn(Optional.empty());when(buyers.currentCertificate(buyer,threadId)).thenReturn(Optional.empty());
        when(authority.latestAuthorityRefresh(buyer,threadId)).thenReturn(Optional.empty());when(authority.currentProposal(buyer,threadId)).thenReturn(Optional.empty());
        ObjectMapper mapper=JsonMapper.builder().build();String observationHash=new CanonicalJsonService(mapper).hash(mapper.valueToTree(observation));
        var stored=new VisualCommerceModels.StoredVisionObservation(UUID.randomUUID(),request,threadId,messageId,"image/png","shoe.png",32,640,480,"c".repeat(64),observation,observationHash,"TEST","gemini-3.5-flash-lite");
        when(observations.create(eq(buyer),eq(request),eq(threadId),eq(messageId),any(),any(),eq(observationHash))).thenReturn(stored);when(observations.findByRequest(buyer,request)).thenReturn(Optional.of(stored));
        var service=new CommerceRequestService(requests,threads,orchestration,buyers,catalogues,mock(dev.agenticcommerce.gateway.identity.persistence.MerchantRepository.class),mock(AuthoritativeRefreshService.class),mock(TransactionProposalService.class),
                mock(ReversibilityService.class),authority,new CanonicalJsonService(mapper),mapper,new BuyerImageValidator(),provider,observations);

        CommerceRequestResult result=service.executeVisual(buyer,request,null,message.normalizedText(),new MockMultipartFile("image","shoe.png","image/png",png()));

        verify(orchestration).advanceWithinRequest(buyer,threadId);verify(observations).create(eq(buyer),eq(request),eq(threadId),eq(messageId),any(),any(),eq(observationHash));
        assertThat(result.visualObservation().productType()).isEqualTo("low-top sneaker");assertThat(result.visualMatchType()).isEqualTo("VISUALLY_SIMILAR_GROUNDED_RESULT");
        assertThat(result.products()).isEmpty();assertThat(result.transactionProposalId()).isNull();assertThat(result.paymentReady()).isFalse();
    }
    private static CommerceThread thread(UUID id,UUID buyer,BuyerState state,Instant now){return new CommerceThread(id,buyer,"Visual request",state,null,null,null,null,null,null,null,null,null,0,32,0,now.plusSeconds(1800),0,now,now);}
    private static byte[] png(){byte[] bytes=new byte[32];byte[] signature={(byte)0x89,0x50,0x4e,0x47,13,10,26,10};System.arraycopy(signature,0,bytes,0,8);bytes[12]='I';bytes[13]='H';bytes[14]='D';bytes[15]='R';java.nio.ByteBuffer.wrap(bytes,16,8).putInt(640).putInt(480);return bytes;}
}
