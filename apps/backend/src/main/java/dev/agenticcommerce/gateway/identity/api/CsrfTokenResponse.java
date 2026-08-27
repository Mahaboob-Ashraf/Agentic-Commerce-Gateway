package dev.agenticcommerce.gateway.identity.api;

/** CSRF metadata needed by the future browser client. */
public record CsrfTokenResponse(String headerName, String parameterName, String token) {
}
