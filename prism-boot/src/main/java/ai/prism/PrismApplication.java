package ai.prism;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. Lives at the {@code ai.prism} base package so component scanning
 * reaches the inbound adapters ({@code ai.prism.adapters.in}) and the wiring
 * configuration.
 */
@SpringBootApplication
public class PrismApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrismApplication.class, args);
    }
}
