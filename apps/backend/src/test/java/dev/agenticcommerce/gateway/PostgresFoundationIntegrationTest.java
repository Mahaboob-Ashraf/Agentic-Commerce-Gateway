package dev.agenticcommerce.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenticcommerce.gateway.identity.model.ActorPasswordCredential;
import dev.agenticcommerce.gateway.identity.model.ApplicationActor;
import dev.agenticcommerce.gateway.identity.model.Merchant;
import dev.agenticcommerce.gateway.identity.model.MerchantAdminMembership;
import dev.agenticcommerce.gateway.identity.model.PlatformRole;
import dev.agenticcommerce.gateway.identity.persistence.ApplicationActorRepository;
import dev.agenticcommerce.gateway.identity.persistence.ActorPasswordCredentialRepository;
import dev.agenticcommerce.gateway.identity.persistence.MerchantAdminMembershipRepository;
import dev.agenticcommerce.gateway.identity.persistence.MerchantRepository;
import dev.agenticcommerce.gateway.identity.service.MerchantAdministrationAccessService;
import dev.agenticcommerce.gateway.identity.service.PlatformAdministrationAccessService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostgresFoundationIntegrationTest {

    private static final String TEST_PASSWORD = "Correct-Horse-Battery-Staple-42!";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("pgvector/pgvector:0.8.1-pg17");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    MerchantRepository merchantRepository;

    @Autowired
    ApplicationActorRepository actorRepository;

    @Autowired
    ActorPasswordCredentialRepository credentialRepository;

    @Autowired
    MerchantAdminMembershipRepository membershipRepository;

    @Autowired
    MerchantAdministrationAccessService merchantAccessService;

    @Autowired
    PlatformAdministrationAccessService platformAccessService;

    @Autowired
    PasswordEncoder passwordEncoder;

    @LocalServerPort
    int port;

    @BeforeEach
    void clearIdentityData() {
        jdbcClient.sql("""
                        TRUNCATE TABLE spring_session_attributes, spring_session,
                          actor_password_credential, merchant_admin_membership,
                          application_actor, merchant
                        """)
                .update();
    }

    @Test
    void startsContextRunsFlywayAndEnablesRequiredExtensions() {
        Set<String> extensions = jdbcTemplate.query(
                "SELECT extname FROM pg_extension WHERE extname IN ('vector', 'pg_trgm')",
                (ResultSet resultSet, int rowNumber) -> resultSet.getString("extname"))
                .stream()
                .collect(Collectors.toSet());

        assertThat(extensions).containsExactlyInAnyOrder("vector", "pg_trgm");
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success",
                Integer.class);
        assertThat(migrationCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void appliesTenantAndIdentityMigration() {
        Integer applied = jdbcClient.sql("""
                        SELECT COUNT(*)::integer
                        FROM flyway_schema_history
                        WHERE script = 'V002__tenant_and_identity_foundation.sql'
                          AND success
                        """)
                .query(Integer.class)
                .single();

        assertThat(applied).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(PlatformRole.class)
    void persistsAndReadsEveryCanonicalRole(PlatformRole role) {
        String handle = role.name().toLowerCase(Locale.ROOT) + "@test.local";

        ApplicationActor created = actorRepository.create(handle, role);

        assertThat(created.id()).isNotNull();
        assertThat(created.createdAt()).isNotNull();
        assertThat(actorRepository.findById(created.id()))
                .contains(created);
        assertThat(actorRepository.findByIdentityHandle(handle))
                .contains(created);
    }

    @Test
    void createsValidMerchantAdminRelationship() {
        Merchant merchant = merchantRepository.create("merchant-one", "Merchant One");
        ApplicationActor admin = actorRepository.create(
                "admin-one@test.local", PlatformRole.MERCHANT_ADMIN);

        MerchantAdminMembership membership = membershipRepository.create(merchant.id(), admin.id());

        assertThat(membership.merchantId()).isEqualTo(merchant.id());
        assertThat(membership.actorId()).isEqualTo(admin.id());
        assertThat(membershipRepository.findByMerchantAndActor(merchant.id(), admin.id()))
                .contains(membership);
        assertThat(merchantAccessService.canAdminister(admin.id(), merchant.id())).isTrue();
    }

    @Test
    void rejectsDuplicateMerchantAdminRelationship() {
        Merchant merchant = merchantRepository.create("merchant-duplicate", "Merchant Duplicate");
        ApplicationActor admin = actorRepository.create(
                "duplicate-admin@test.local", PlatformRole.MERCHANT_ADMIN);
        membershipRepository.create(merchant.id(), admin.id());

        assertThatThrownBy(() -> membershipRepository.create(merchant.id(), admin.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsRelationshipsWithInvalidForeignKeys() {
        Merchant merchant = merchantRepository.create("merchant-fk", "Merchant FK");
        ApplicationActor admin = actorRepository.create("fk-admin@test.local", PlatformRole.MERCHANT_ADMIN);

        assertThatThrownBy(() -> membershipRepository.create(merchant.id(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> membershipRepository.create(UUID.randomUUID(), admin.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsNonMerchantAdminMembership() {
        Merchant merchant = merchantRepository.create("merchant-role", "Merchant Role");
        ApplicationActor buyer = actorRepository.create("buyer@test.local", PlatformRole.BUYER);

        assertThatThrownBy(() -> membershipRepository.create(merchant.id(), buyer.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsNonCanonicalRoleValue() {
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO application_actor (identity_handle, platform_role)
                        VALUES ('invalid-role@test.local', 'ADMIN')
                        """)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void merchantScopedLookupCannotResolveAnotherMerchantsRelationship() {
        Merchant merchantA = merchantRepository.create("merchant-a", "Merchant A");
        Merchant merchantB = merchantRepository.create("merchant-b", "Merchant B");
        ApplicationActor admin = actorRepository.create(
                "tenant-admin@test.local", PlatformRole.MERCHANT_ADMIN);
        membershipRepository.create(merchantA.id(), admin.id());

        assertThat(membershipRepository.findByMerchantAndActor(merchantA.id(), admin.id()))
                .isPresent();
        assertThat(membershipRepository.findByMerchantAndActor(merchantB.id(), admin.id()))
                .isEmpty();
        assertThat(merchantAccessService.canAdminister(admin.id(), merchantA.id())).isTrue();
        assertThat(merchantAccessService.canAdminister(admin.id(), merchantB.id())).isFalse();
    }

    @Test
    void platformAndSystemRolesDoNotBypassMerchantMembership() {
        Merchant merchant = merchantRepository.create("merchant-no-bypass", "Merchant No Bypass");
        ApplicationActor platformAdmin = actorRepository.create(
                "platform-admin@test.local", PlatformRole.PLATFORM_ADMIN);
        ApplicationActor system = actorRepository.create("system@test.local", PlatformRole.SYSTEM);

        assertThat(platformAccessService.isPlatformAdministrator(platformAdmin.id())).isTrue();
        assertThat(platformAccessService.isPlatformAdministrator(system.id())).isFalse();
        assertThat(merchantAccessService.canAdminister(platformAdmin.id(), merchant.id())).isFalse();
        assertThat(merchantAccessService.canAdminister(system.id(), merchant.id())).isFalse();
    }

    @Test
    void exposesUnauthenticatedHealthEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/actuator/health"))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void appliesAuthenticationAndSessionMigration() {
        Integer applied = jdbcClient.sql("""
                        SELECT COUNT(*)::integer
                        FROM flyway_schema_history
                        WHERE script = 'V003__authentication_and_jdbc_sessions.sql'
                          AND success
                        """)
                .query(Integer.class)
                .single();

        assertThat(applied).isOne();
    }

    @Test
    void storesOnlyAnArgon2PasswordHashAndVerifiesIt() {
        ApplicationActor buyer = actorRepository.create("password-buyer", PlatformRole.BUYER);
        String encodedPassword = passwordEncoder.encode(TEST_PASSWORD);

        ActorPasswordCredential credential = credentialRepository.createArgon2Credential(
                buyer.id(), encodedPassword, true);

        assertThat(credential.passwordHash())
                .startsWith("$argon2")
                .doesNotContain(TEST_PASSWORD);
        assertThat(passwordEncoder.matches(TEST_PASSWORD, credential.passwordHash())).isTrue();
        assertThat(passwordEncoder.matches("wrong-password", credential.passwordHash())).isFalse();
        assertThat(jdbcClient.sql("""
                        SELECT password_hash
                        FROM actor_password_credential
                        WHERE actor_id = :actorId
                        """)
                .param("actorId", buyer.id())
                .query(String.class)
                .single()).isEqualTo(encodedPassword);
    }

    @Test
    void loginRotatesTheSessionAndRestoresAVerifiedPrincipalFromPostgres() throws Exception {
        ApplicationActor buyer = createAuthenticatedActor(
                "session-buyer", PlatformRole.BUYER, TEST_PASSWORD);
        CookieManager cookies = newCookieManager();
        HttpClient client = sessionClient(cookies);
        CsrfSession anonymousCsrf = fetchCsrf(client, cookies);

        HttpResponse<String> login = login(
                client, anonymousCsrf.token(), buyer.identityHandle(), TEST_PASSWORD);

        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body())
                .contains(buyer.id().toString())
                .contains("\"identityHandle\":\"session-buyer\"")
                .contains("\"role\":\"BUYER\"")
                .doesNotContain(TEST_PASSWORD)
                .doesNotContain("passwordHash");
        String authenticatedSessionId = cookieValue(cookies, "ACG_SESSION");
        assertThat(authenticatedSessionId).isNotEqualTo(anonymousCsrf.sessionId());

        HttpResponse<String> me = get(client, "/api/auth/me");
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body())
                .contains(buyer.id().toString())
                .contains("\"identityHandle\":\"session-buyer\"")
                .contains("\"role\":\"BUYER\"");
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)::integer
                        FROM spring_session
                        WHERE principal_name = :principalName
                        """)
                .param("principalName", buyer.identityHandle())
                .query(Integer.class)
                .single()).isOne();
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)::integer
                        FROM spring_session_attributes attributes
                        JOIN spring_session session_row
                          ON session_row.primary_id = attributes.session_primary_id
                        WHERE session_row.principal_name = :principalName
                        """)
                .param("principalName", buyer.identityHandle())
                .query(Integer.class)
                .single()).isGreaterThan(0);
        var serializedSessionAttributes = jdbcClient.sql("""
                        SELECT encode(attributes.attribute_bytes, 'escape')
                        FROM spring_session_attributes attributes
                        JOIN spring_session session_row
                          ON session_row.primary_id = attributes.session_primary_id
                        WHERE session_row.principal_name = :principalName
                        """)
                .param("principalName", buyer.identityHandle())
                .query(String.class)
                .list();
        assertThat(serializedSessionAttributes).allSatisfy(serializedAttribute ->
                assertThat(serializedAttribute)
                        .doesNotContain(TEST_PASSWORD)
                        .doesNotContain("$argon2"));
    }

    @Test
    void invalidPasswordReturnsGenericUnauthorizedResponse() throws Exception {
        createAuthenticatedActor("invalid-password-buyer", PlatformRole.BUYER, TEST_PASSWORD);
        CookieManager cookies = newCookieManager();
        HttpClient client = sessionClient(cookies);
        CsrfSession csrf = fetchCsrf(client, cookies);

        HttpResponse<String> response = login(
                client, csrf.token(), "invalid-password-buyer", "wrong-password");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"error\":\"invalid_credentials\"");
    }

    @Test
    void unknownIdentityReturnsTheSameGenericUnauthorizedResponse() throws Exception {
        CookieManager cookies = newCookieManager();
        HttpClient client = sessionClient(cookies);
        CsrfSession csrf = fetchCsrf(client, cookies);

        HttpResponse<String> response = login(
                client, csrf.token(), "unknown-identity", TEST_PASSWORD);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"error\":\"invalid_credentials\"");
    }

    @Test
    void unauthenticatedProtectedEndpointReturnsJsonUnauthorized() throws Exception {
        HttpResponse<String> response = get(sessionClient(newCookieManager()), "/api/auth/me");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"error\":\"unauthorized\"");
    }

    @Test
    void loginRequiresCsrfProtection() throws Exception {
        createAuthenticatedActor("csrf-login-buyer", PlatformRole.BUYER, TEST_PASSWORD);
        CookieManager cookies = newCookieManager();
        HttpClient client = sessionClient(cookies);
        fetchCsrf(client, cookies);

        HttpResponse<String> response = login(
                client, null, "csrf-login-buyer", TEST_PASSWORD);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"error\":\"forbidden\"");
    }

    @Test
    void logoutRequiresCsrfAndKeepsTheSessionWhenMissing() throws Exception {
        createAuthenticatedActor("csrf-logout-buyer", PlatformRole.BUYER, TEST_PASSWORD);
        CookieManager cookies = newCookieManager();
        HttpClient client = sessionClient(cookies);
        CsrfSession csrf = fetchCsrf(client, cookies);
        assertThat(login(client, csrf.token(), "csrf-logout-buyer", TEST_PASSWORD).statusCode())
                .isEqualTo(200);

        HttpResponse<String> logout = post(client, "/api/auth/logout", null, null);

        assertThat(logout.statusCode()).isEqualTo(403);
        assertThat(get(client, "/api/auth/me").statusCode()).isEqualTo(200);
    }

    @Test
    void logoutWithCsrfInvalidatesTheDurableSession() throws Exception {
        createAuthenticatedActor("logout-buyer", PlatformRole.BUYER, TEST_PASSWORD);
        CookieManager cookies = newCookieManager();
        HttpClient client = sessionClient(cookies);
        CsrfSession anonymousCsrf = fetchCsrf(client, cookies);
        assertThat(login(client, anonymousCsrf.token(), "logout-buyer", TEST_PASSWORD).statusCode())
                .isEqualTo(200);
        CsrfSession authenticatedCsrf = fetchCsrf(client, cookies);

        HttpResponse<String> logout = post(
                client, "/api/auth/logout", authenticatedCsrf.token(), null);

        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(get(client, "/api/auth/me").statusCode()).isEqualTo(401);
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)::integer
                        FROM spring_session
                        WHERE principal_name = :principalName
                        """)
                .param("principalName", "logout-buyer")
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void authenticatedMerchantAdminRemainsBoundToExplicitTenantMembership() throws Exception {
        Merchant firstMerchant = merchantRepository.create("auth-merchant-one", "Auth Merchant One");
        Merchant secondMerchant = merchantRepository.create("auth-merchant-two", "Auth Merchant Two");
        ApplicationActor admin = createAuthenticatedActor(
                "authenticated-merchant-admin", PlatformRole.MERCHANT_ADMIN, TEST_PASSWORD);
        membershipRepository.create(firstMerchant.id(), admin.id());
        CookieManager cookies = newCookieManager();
        HttpClient client = sessionClient(cookies);
        CsrfSession csrf = fetchCsrf(client, cookies);

        HttpResponse<String> login = login(client, csrf.token(), admin.identityHandle(), TEST_PASSWORD);

        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(get(client, "/api/auth/me").body())
                .contains("\"role\":\"MERCHANT_ADMIN\"");
        assertThat(merchantAccessService.canAdminister(admin.id(), firstMerchant.id())).isTrue();
        assertThat(merchantAccessService.canAdminister(admin.id(), secondMerchant.id())).isFalse();
    }

    @Test
    void authenticatedPlatformAdminDoesNotGainMerchantMembershipByRole() throws Exception {
        Merchant merchant = merchantRepository.create("no-auth-bypass", "No Auth Bypass Merchant");
        ApplicationActor platformAdmin = createAuthenticatedActor(
                "authenticated-platform-admin", PlatformRole.PLATFORM_ADMIN, TEST_PASSWORD);
        CookieManager cookies = newCookieManager();
        HttpClient client = sessionClient(cookies);
        CsrfSession csrf = fetchCsrf(client, cookies);

        HttpResponse<String> login = login(
                client, csrf.token(), platformAdmin.identityHandle(), TEST_PASSWORD);

        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(get(client, "/api/auth/me").body())
                .contains("\"role\":\"PLATFORM_ADMIN\"");
        assertThat(platformAccessService.isPlatformAdministrator(platformAdmin.id())).isTrue();
        assertThat(merchantAccessService.canAdminister(platformAdmin.id(), merchant.id())).isFalse();
    }

    private ApplicationActor createAuthenticatedActor(
            String identityHandle, PlatformRole role, String rawPassword) {
        ApplicationActor actor = actorRepository.create(identityHandle, role);
        credentialRepository.createArgon2Credential(
                actor.id(), passwordEncoder.encode(rawPassword), true);
        return actor;
    }

    private HttpClient sessionClient(CookieManager cookieManager) {
        return HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .build();
    }

    private CookieManager newCookieManager() {
        return new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    }

    private CsrfSession fetchCsrf(HttpClient client, CookieManager cookies) throws Exception {
        HttpResponse<String> response = get(client, "/api/auth/csrf");
        assertThat(response.statusCode()).isEqualTo(200);
        return new CsrfSession(
                jsonString(response.body(), "token"),
                cookieValue(cookies, "ACG_SESSION"));
    }

    private HttpResponse<String> login(
            HttpClient client, String csrfToken, String identityHandle, String password)
            throws Exception {
        String body = "{\"identityHandle\":\"" + jsonEscape(identityHandle)
                + "\",\"password\":\"" + jsonEscape(password) + "\"}";
        return post(client, "/api/auth/login", csrfToken, body);
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(
            HttpClient client, String path, String csrfToken, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri(path));
        if (csrfToken != null) {
            request.header("X-CSRF-TOKEN", csrfToken);
        }
        if (body == null) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI baseUri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String cookieValue(CookieManager cookies, String name) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals(name))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing cookie " + name));
    }

    private static String jsonString(String body, String fieldName) {
        Pattern pattern = Pattern.compile(
                "\\\"" + Pattern.quote(fieldName) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            throw new AssertionError("Missing JSON string field " + fieldName);
        }
        return matcher.group(1);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record CsrfSession(String token, String sessionId) {
    }
}
