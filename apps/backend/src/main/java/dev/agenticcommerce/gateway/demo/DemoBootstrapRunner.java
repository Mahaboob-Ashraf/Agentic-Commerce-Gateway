package dev.agenticcommerce.gateway.demo;

import java.nio.file.Path;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="demo.bootstrap.enabled",havingValue="true")
public class DemoBootstrapRunner implements ApplicationRunner {
    private static final Logger log=LoggerFactory.getLogger(DemoBootstrapRunner.class);
    private final DemoBootstrapService service;private final String publicBase;private final String buyerIdentity;private final String buyerPassword;
    private final String merchantAdminPassword;private final Path fixtureRoot;
    public DemoBootstrapRunner(DemoBootstrapService service,@Value("${demo.bootstrap.public-base-url:}") String publicBase,
            @Value("${demo.bootstrap.buyer-identity:}") String buyerIdentity,@Value("${demo.bootstrap.buyer-password:}") String buyerPassword,
            @Value("${demo.bootstrap.merchant-admin-password:}") String merchantAdminPassword,
            @Value("${demo.bootstrap.fixture-root:../../evaluation/demo-data}") String fixtureRoot){this.service=service;this.publicBase=publicBase;
        this.buyerIdentity=buyerIdentity;this.buyerPassword=buyerPassword;this.merchantAdminPassword=merchantAdminPassword;this.fixtureRoot=Path.of(fixtureRoot);}
    @Override public void run(ApplicationArguments args){log.info("P0 demo bootstrap starting deploymentPrecondition={} merchantPublicBaseUrl={}",
                DemoBootstrapService.DEPLOYMENT_PRECONDITION,publicBase);
        var s=service.bootstrap(publicBase,buyerIdentity,buyerPassword,merchantAdminPassword,fixtureRoot);
        if(!s.blockers().isEmpty()){
            log.error("P0 demo bootstrap failed closed deploymentPrecondition={} merchantPublicBaseUrl={} blockers={}",
                    s.deploymentPrecondition(),s.merchantPublicBaseUrl(),s.blockers());
            throw new IllegalStateException("P0 demo bootstrap did not reach required remote READY state");
        }
        log.info("P0 demo bootstrap complete reused={} deploymentPrecondition={} merchantPublicBaseUrl={} buyer=[created:{}] merchants=[total:{},created:{},reused:{}] products=[Amazing:{},FreshBasket:{}] primaryFacts={} embeddings=[ready:{},fallback:{}] capabilities=[mapped:{},ready:{}] manifests={} buyerLinks={} blockers={}",
                s.reused(),s.deploymentPrecondition(),s.merchantPublicBaseUrl(),s.buyerCreated(),s.merchants(),s.merchantsCreated(),s.merchantsReused(),s.amazingProducts(),
                s.freshBasketProducts(),s.primaryFacts(),s.embeddingsReady(),s.lexicalFallbacks(),s.capabilitiesMapped(),
                s.capabilitiesReady(),s.manifests(),s.buyerLinks(),s.blockers());}
}
