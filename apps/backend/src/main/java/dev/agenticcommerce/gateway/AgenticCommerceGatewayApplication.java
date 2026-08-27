package dev.agenticcommerce.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class AgenticCommerceGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgenticCommerceGatewayApplication.class, args);
    }
}
