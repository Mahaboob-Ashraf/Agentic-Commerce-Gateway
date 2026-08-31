package dev.agenticcommerce.gateway.payment;

import dev.agenticcommerce.gateway.agentization.execution.MerchantExecutionException;
import dev.agenticcommerce.gateway.agentization.service.AgentizationException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class MerchantFinalizationService {
    private final PaymentRepository repository;
    private final MerchantFinalizationGateway gateway;
    private final ObjectMapper mapper;
    private final int maximumAttempts;

    public MerchantFinalizationService(
            PaymentRepository repository, MerchantFinalizationGateway gateway, ObjectMapper mapper,
            @Value("${payment.finalization.maximum-attempts:8}") int maximumAttempts) {
        this.repository = repository; this.gateway = gateway; this.mapper = mapper;
        this.maximumAttempts = maximumAttempts;
    }

    @Transactional
    public void process(PaymentRepository.OutboxItem item) {
        var work = repository.finalization(item.executionId())
                .orElseThrow(() -> new IllegalStateException("Confirmed payment lacks merchant finalization"));
        if (work.state() == PaymentModels.FulfillmentState.FULFILLED) {
            repository.completeOutbox(item.id(), Instant.now());
            return;
        }
        Instant now = Instant.now();
        int attempt = repository.beginFinalizationAttempt(work, now);
        try {
            var header = repository.finalizationHeader(work.proposalId());
            var request = mapper.createObjectNode();
            request.put("merchantOperationId", work.merchantOperationId());
            request.put("executionId", work.executionId().toString());
            request.put("proposalId", work.proposalId().toString());
            request.put("proposalHash", header.proposalHash());
            request.put("amountMinor", header.amountMinor());
            request.put("currency", header.currency());
            request.put("providerOrderId", work.providerOrderId());
            request.put("providerPaymentId", work.providerPaymentId());
            var lines = request.putArray("lineItems");
            for (var line : repository.finalizationLines(work.proposalId())) {
                var value = lines.addObject();
                value.put("lineNumber", line.lineNumber());
                value.put("productId", line.productId().toString());
                value.put("merchantSku", line.merchantSku());
                if (line.variant() == null) value.putNull("variant"); else value.put("variant", line.variant());
                value.put("quantity", line.quantity());
                value.put("unitAmountMinor", line.unitAmountMinor());
                value.put("lineAmountMinor", line.lineAmountMinor());
            }
            var result = gateway.placeOrder(work.merchantId(), request);
            repository.completeFinalization(work, attempt, result.mappingProposalId(),
                    result.merchantOrderId(), result.responseHash(), Instant.now());
            repository.completeOutbox(item.id(), Instant.now());
        } catch (MerchantFinalizationException failure) {
            fail(item, work, attempt, failure.retryable(), failure.code());
        } catch (MerchantExecutionException failure) {
            boolean retryable = "MERCHANT_TIMEOUT".equals(failure.code())
                    || "MERCHANT_TRANSPORT_FAILURE".equals(failure.code());
            fail(item, work, attempt, retryable, failure.code());
        } catch (AgentizationException failure) {
            fail(item, work, attempt, false, failure.code());
        }
    }

    private void fail(PaymentRepository.OutboxItem item, PaymentRepository.FinalizationWork work,
            int attempt, boolean retryable, String error) {
        boolean canRetry = retryable && attempt < maximumAttempts;
        Instant now = Instant.now();
        repository.failFinalization(work, attempt, canRetry, error, now);
        long delay = Math.min(300, 1L << Math.min(attempt, 8));
        repository.failOutbox(item.id(), canRetry, error, now.plusSeconds(delay), now);
    }
}
