package dev.agenticcommerce.gateway.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** JSON credentials accepted only by the server-side login endpoint. */
public record LoginRequest(
        @NotBlank @Size(max = 320) String identityHandle,
        @NotBlank @Size(max = 1024) String password) {

    @Override
    public String toString() {
        return "LoginRequest[identityHandle=" + identityHandle + ", password=<redacted>]";
    }
}
