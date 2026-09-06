package dev.agenticcommerce.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenticcommerce.gateway.identity.model.ApplicationActor;
import dev.agenticcommerce.gateway.identity.model.Merchant;
import dev.agenticcommerce.gateway.identity.model.PlatformRole;
import dev.agenticcommerce.gateway.identity.persistence.ActorPasswordCredentialRepository;
import dev.agenticcommerce.gateway.identity.persistence.ApplicationActorRepository;
import dev.agenticcommerce.gateway.identity.persistence.MerchantAdminMembershipRepository;
import dev.agenticcommerce.gateway.identity.persistence.MerchantRepository;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MerchantAccessIntegrationTest {

    private static final String TEST_PASSWORD = "Task-015D2-Test-Password!";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("pgvector/pgvector:0.8.1-pg17");

    @Autowired JdbcClient jdbcClient;
    @Autowired ObjectMapper objectMapper;
    @Autowired MerchantRepository merchants;
    @Autowired ApplicationActorRepository actors;
    @Autowired ActorPasswordCredentialRepository credentials;
    @Autowired MerchantAdminMembershipRepository memberships;
    @Autowired PasswordEncoder passwordEncoder;

    @LocalServerPort int port;

    @BeforeEach
    void clearData() {
        jdbcClient.sql("""
                        TRUNCATE TABLE spring_session_attributes, spring_session,
                          actor_password_credential, merchant_admin_membership,
                          application_actor, merchant CASCADE
                        """).update();
    }

    @Test
    void listMineReturnsOnlyAuthenticatedActorsMerchantMemberships() throws Exception {
        Merchant first = merchants.create("first-owned", "First Owned");
        Merchant second = merchants.create("second-owned", "Second Owned");
        Merchant foreign = merchants.create("foreign", "Foreign Merchant");
        ApplicationActor admin = authenticatedActor("member-admin", PlatformRole.MERCHANT_ADMIN);
        ApplicationActor otherAdmin = authenticatedActor("other-admin", PlatformRole.MERCHANT_ADMIN);
        memberships.create(first.id(), admin.id());
        memberships.create(second.id(), admin.id());
        memberships.create(foreign.id(), otherAdmin.id());

        HttpResponse<String> response = authenticatedGet(admin, "/api/merchants");
        JsonNode body = objectMapper.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.toString()).contains(first.id().toString(), second.id().toString());
        assertThat(body.toString()).doesNotContain(foreign.id().toString(), "other-admin");
    }

    @Test
    void merchantAdminWithoutMembershipReceivesAnEmptySetupBoundary() throws Exception {
        ApplicationActor admin = authenticatedActor("empty-admin", PlatformRole.MERCHANT_ADMIN);

        HttpResponse<String> response = authenticatedGet(admin, "/api/merchants");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(response.body()).size()).isZero();
    }

    @Test
    void buyerCannotUseMerchantDiscovery() throws Exception {
        ApplicationActor buyer = authenticatedActor("merchant-list-buyer", PlatformRole.BUYER);

        HttpResponse<String> response = authenticatedGet(buyer, "/api/merchants");

        assertThat(response.statusCode()).isEqualTo(403);
    }

    private ApplicationActor authenticatedActor(String handle, PlatformRole role) {
        ApplicationActor actor = actors.create(handle, role);
        credentials.createArgon2Credential(actor.id(), passwordEncoder.encode(TEST_PASSWORD), true);
        return actor;
    }

    private HttpResponse<String> authenticatedGet(ApplicationActor actor, String path) throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        HttpResponse<String> csrfResponse = client.send(
                HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String csrf = objectMapper.readTree(csrfResponse.body()).path("token").asText();
        String loginBody = objectMapper.writeValueAsString(new LoginRequest(actor.identityHandle(), TEST_PASSWORD));
        HttpResponse<String> login = client.send(
                HttpRequest.newBuilder(uri("/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf)
                        .POST(HttpRequest.BodyPublishers.ofString(loginBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        return client.send(
                HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private record LoginRequest(String identityHandle, String password) {}
}
