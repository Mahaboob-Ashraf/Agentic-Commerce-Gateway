package dev.agenticcommerce.gateway.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenticcommerce.gateway.agentization.execution.MerchantTransportResponse;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuthoritativeAvailabilityFreshnessTest {

    private static final Duration MAXIMUM_AGE = Duration.ofMinutes(5);
    private static final Instant LOCAL_RECEIVED_AT = Instant.parse("2026-09-03T17:05:10Z");
    private static final Instant RESPONSE_DATE = Instant.parse("2026-09-03T17:07:12Z");

    @Test
    void usesAuthenticatedHttpResponseClockForLiveMerchantObservation() {
        var response = response(LOCAL_RECEIVED_AT, RESPONSE_DATE);

        assertThat(AuthoritativeAvailabilityService.isFresh(
                        Instant.parse("2026-09-03T17:07:10Z"),
                        Instant.parse("2026-09-03T17:09:10Z"),
                        response,
                        LOCAL_RECEIVED_AT,
                        MAXIMUM_AGE))
                .isTrue();
    }

    @Test
    void stillFailsClosedForStaleUndatedExpiredAndImplausiblyFutureEvidence() {
        var response = response(LOCAL_RECEIVED_AT, RESPONSE_DATE);

        assertThat(AuthoritativeAvailabilityService.isFresh(
                        RESPONSE_DATE.minus(MAXIMUM_AGE).minusSeconds(1),
                        RESPONSE_DATE.plusSeconds(60), response, LOCAL_RECEIVED_AT, MAXIMUM_AGE))
                .isFalse();
        assertThat(AuthoritativeAvailabilityService.isFresh(
                        null, RESPONSE_DATE.plusSeconds(60), response, LOCAL_RECEIVED_AT, MAXIMUM_AGE))
                .isFalse();
        assertThat(AuthoritativeAvailabilityService.isFresh(
                        RESPONSE_DATE, RESPONSE_DATE, response, LOCAL_RECEIVED_AT, MAXIMUM_AGE))
                .isFalse();
        assertThat(AuthoritativeAvailabilityService.isFresh(
                        RESPONSE_DATE.plusSeconds(31), RESPONSE_DATE.plusSeconds(60),
                        response, LOCAL_RECEIVED_AT, MAXIMUM_AGE))
                .isFalse();
    }

    @Test
    void fallsBackToLocalReceiptClockWhenResponseDateIsUnavailable() {
        var response = response(LOCAL_RECEIVED_AT, null);

        assertThat(AuthoritativeAvailabilityService.isFresh(
                        LOCAL_RECEIVED_AT.minusSeconds(1),
                        LOCAL_RECEIVED_AT.plusSeconds(60),
                        response,
                        LOCAL_RECEIVED_AT.minusSeconds(30),
                        MAXIMUM_AGE))
                .isTrue();
    }

    private static MerchantTransportResponse response(Instant receivedAt, Instant responseDate) {
        return new MerchantTransportResponse(200, "application/json", new byte[0], receivedAt, responseDate);
    }
}
