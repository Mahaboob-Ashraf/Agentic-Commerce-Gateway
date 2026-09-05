package dev.agenticcommerce.gateway.config;

import dev.agenticcommerce.gateway.demo.DemoMerchantApiAuthenticationFilter;
import dev.agenticcommerce.gateway.identity.authentication.ApplicationActorUserDetailsService;
import dev.agenticcommerce.gateway.identity.authentication.VerifiedActorPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
public class AuthenticationSecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationSecurityConfiguration.class);

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    AuthenticationManager authenticationManager(
            ApplicationActorUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        var provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(
            HttpSessionCsrfTokenRepository csrfTokenRepository) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokenRepository)));
    }

    @Bean
    HttpSessionCsrfTokenRepository csrfTokenRepository() {
        var repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        return repository;
    }

    @Bean
    SecurityFilterChain authenticationSecurityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            HttpSessionCsrfTokenRepository csrfTokenRepository,
            DemoMerchantApiAuthenticationFilter demoMerchantApiAuthenticationFilter) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/payments/razorpay/webhook").permitAll()
                        .requestMatchers("/api/demo-merchants/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/discovery/merchants/*/ready-capabilities").permitAll()
                        .requestMatchers("/api/discovery/merchants/*/catalogue/**").permitAll()
                        .requestMatchers("/api/auth/me", "/api/auth/logout").authenticated()
                        .requestMatchers("/api/buyer/**").hasRole("BUYER")
                        .requestMatchers(HttpMethod.GET, "/api/merchants").hasRole("MERCHANT_ADMIN")
                        .requestMatchers("/api/merchants/*/agentization/**")
                        .hasRole("MERCHANT_ADMIN")
                        .requestMatchers("/api/merchants/*/catalogue/**").hasRole("MERCHANT_ADMIN")
                        .anyRequest().denyAll())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository)
                        .ignoringRequestMatchers("/api/payments/razorpay/webhook", "/api/demo-merchants/**"))
                .addFilterBefore(demoMerchantApiAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository)
                        .requireExplicitSave(true))
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized"))
                        .accessDeniedHandler(AuthenticationSecurityConfiguration::writeAccessDenied))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("ACG_SESSION")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    FilterRegistrationBean<DemoMerchantApiAuthenticationFilter> demoMerchantFilterRegistration(
            DemoMerchantApiAuthenticationFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    private static void writeJsonError(HttpServletResponse response, int status, String error)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + error + "\"}");
    }

    private static void writeAccessDenied(
            HttpServletRequest request,
            HttpServletResponse response,
            org.springframework.security.access.AccessDeniedException exception) throws IOException {
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        boolean authenticated = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        String principal = authentication != null
                        && authentication.getPrincipal() instanceof VerifiedActorPrincipal actor
                ? actor.actorId().toString()
                : "none";
        String roles = authentication == null
                ? "[]"
                : authentication.getAuthorities().stream()
                        .map(Object::toString)
                        .sorted()
                        .toList()
                        .toString();
        boolean csrfFailure = exception instanceof CsrfException;
        log.warn(
                "Security request denied path={} authenticated={} principal={} roles={} session={} csrfFailure={} exceptionClass={} reason={}",
                request.getRequestURI(), authenticated, principal, roles, sessionFingerprint(request),
                csrfFailure, exception.getClass().getName(),
                csrfFailure ? "CSRF_VALIDATION_FAILED" : "AUTHORIZATION_DENIED");
        writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "forbidden");
    }

    private static String sessionFingerprint(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) {
            return "none";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(session.getId().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
