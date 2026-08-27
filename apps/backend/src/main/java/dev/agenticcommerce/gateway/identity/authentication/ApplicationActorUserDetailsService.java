package dev.agenticcommerce.gateway.identity.authentication;

import dev.agenticcommerce.gateway.identity.persistence.ActorPasswordCredentialRepository;
import dev.agenticcommerce.gateway.identity.persistence.ApplicationActorRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Resolves credentials and canonical role entirely from authoritative PostgreSQL state. */
@Service
public class ApplicationActorUserDetailsService implements UserDetailsService {

    private final ApplicationActorRepository actorRepository;
    private final ActorPasswordCredentialRepository credentialRepository;

    public ApplicationActorUserDetailsService(
            ApplicationActorRepository actorRepository,
            ActorPasswordCredentialRepository credentialRepository) {
        this.actorRepository = actorRepository;
        this.credentialRepository = credentialRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String identityHandle) throws UsernameNotFoundException {
        var actor = actorRepository.findByIdentityHandle(identityHandle)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
        var credential = credentialRepository.findByActorId(actor.id())
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));

        return new VerifiedActorPrincipal(
                actor.id(),
                actor.identityHandle(),
                actor.role(),
                credential.passwordHash(),
                credential.enabled());
    }
}
