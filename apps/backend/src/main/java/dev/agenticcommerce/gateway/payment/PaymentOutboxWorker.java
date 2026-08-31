package dev.agenticcommerce.gateway.payment;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentOutboxWorker {
    private final PaymentRepository repository;
    private final MerchantFinalizationService finalization;
    private final PaymentProvider provider;
    private final int batchSize;
    private final long leaseSeconds;

    public PaymentOutboxWorker(
            PaymentRepository repository, MerchantFinalizationService finalization, PaymentProvider provider,
            @Value("${payment.outbox.batch-size:10}") int batchSize,
            @Value("${payment.outbox.lease-seconds:60}") long leaseSeconds) {
        this.repository = repository; this.finalization = finalization; this.provider = provider;
        this.batchSize = batchSize; this.leaseSeconds = leaseSeconds;
    }

    @Scheduled(fixedDelayString = "${payment.outbox.poll-delay-ms:1000}")
    public void dispatch() {
        // A credential-free application must start quietly; no payment work can exist legitimately
        // for a deployment that has never enabled its provider boundary.
        if (!provider.configured()) return;
        Instant now = Instant.now();
        for (var item : repository.claimOutbox(batchSize, now, now.plusSeconds(leaseSeconds))) {
            finalization.process(item);
        }
    }
}
