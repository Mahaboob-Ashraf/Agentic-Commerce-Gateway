package dev.agenticcommerce.gateway.identity.authentication;

import dev.agenticcommerce.gateway.identity.model.PlatformRole;
import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Server-verified Spring Security principal for one persisted application actor.
 * Password material is erased after authentication and is not serialized into JDBC sessions.
 */
public final class VerifiedActorPrincipal implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID actorId;
    private final String identityHandle;
    private final PlatformRole role;
    private final boolean enabled;
    private final List<GrantedAuthority> authorities;
    private transient String passwordHash;

    public VerifiedActorPrincipal(
            UUID actorId,
            String identityHandle,
            PlatformRole role,
            String passwordHash,
            boolean enabled) {
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.identityHandle = Objects.requireNonNull(identityHandle, "identityHandle");
        this.role = Objects.requireNonNull(role, "role");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.enabled = enabled;
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    public UUID actorId() {
        return actorId;
    }

    public String identityHandle() {
        return identityHandle;
    }

    public PlatformRole role() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return identityHandle;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}
