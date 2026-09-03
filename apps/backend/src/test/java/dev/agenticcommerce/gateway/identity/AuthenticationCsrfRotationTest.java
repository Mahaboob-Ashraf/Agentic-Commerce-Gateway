package dev.agenticcommerce.gateway.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

class AuthenticationCsrfRotationTest {

    @Test
    void successfulAuthenticationInvalidatesAnonymousTokenAndRequiresReplacement() {
        var repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        var anonymousRequest = new MockHttpServletRequest();
        var anonymousResponse = new MockHttpServletResponse();
        var anonymousToken = repository.generateToken(anonymousRequest);
        repository.saveToken(anonymousToken, anonymousRequest, anonymousResponse);

        var loginRequest = new MockHttpServletRequest();
        loginRequest.setSession(anonymousRequest.getSession());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "buyer", "not-retained", List.of(new SimpleGrantedAuthority("ROLE_BUYER")));
        new CsrfAuthenticationStrategy(repository)
                .onAuthentication(authentication, loginRequest, new MockHttpServletResponse());

        assertThat(repository.loadToken(loginRequest)).isNull();

        var authenticatedRequest = new MockHttpServletRequest();
        authenticatedRequest.setSession(loginRequest.getSession());
        var replacement = repository.generateToken(authenticatedRequest);
        repository.saveToken(replacement, authenticatedRequest, new MockHttpServletResponse());

        assertThat(replacement.getToken()).isNotEqualTo(anonymousToken.getToken());
        assertThat(repository.loadToken(authenticatedRequest).getToken()).isEqualTo(replacement.getToken());
    }
}
