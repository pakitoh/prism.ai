package ai.prism.boot.configuration;

import ai.prism.adapters.out.investigation.RememberingInvestigationRunner;
import ai.prism.adapters.out.investigation.ObservedInvestigationRunner;
import ai.prism.adapters.out.persistence.PostgresInvestigationRepository;
import ai.prism.application.port.in.InvestigationCommandsUseCase;
import ai.prism.application.port.in.InvestigationQueriesUseCase;
import ai.prism.application.port.out.MemoryPort;
import ai.prism.application.port.out.InvestigationRepository;
import ai.prism.application.port.out.LogsPort;
import ai.prism.application.port.out.MetricsPort;
import ai.prism.application.port.out.ReasoningPort;
import ai.prism.application.port.out.TracingPort;
import ai.prism.application.service.InvestigationCommandsService;
import ai.prism.application.service.InvestigationLoop;
import ai.prism.application.service.InvestigationQueriesService;
import ai.prism.application.service.InvestigationRunner;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;

/**
 * Wires the application core: the investigation loop (decorated with memory and
 * observability) and the command/query use-case services that the inbound adapters
 * drive. Also provides the shared {@link Clock} and the background executor.
 */
@Configuration
class InvestigationConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    // One virtual thread per background investigation: cheap, and investigations are
    // IO-bound (model + telemetry HTTP calls), so they park rather than burn a platform thread.
    @Bean
    Executor investigationExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    InvestigationRunner investigationRunner(ReasoningPort reasoningPort,
                                            MetricsPort metricsPort,
                                            LogsPort logsPort,
                                            TracingPort tracingPort,
                                            MemoryPort memory,
                                            InvestigationRepository repository,
                                            Clock clock,
                                            @Value("${prism.investigation.max-steps}") int maxSteps,
                                            ObservationRegistry observationRegistry) {
        InvestigationLoop loop = new InvestigationLoop(
                reasoningPort, metricsPort, logsPort, tracingPort, memory, repository, clock, maxSteps);
        InvestigationRunner remembering = new RememberingInvestigationRunner(loop, memory);
        return new ObservedInvestigationRunner(remembering, observationRegistry);
    }

    @Bean
    InvestigationRepository investigationRepository(DataSource dataSource, JsonMapper jsonMapper) {
        return new PostgresInvestigationRepository(dataSource, jsonMapper);
    }

    @Bean
    InvestigationCommandsUseCase investigationCommands(InvestigationRunner investigationRunner,
                                                       InvestigationRepository repository,
                                                       Executor investigationExecutor) {
        return new InvestigationCommandsService(investigationRunner, repository, investigationExecutor);
    }

    @Bean
    InvestigationQueriesUseCase investigationQueries(InvestigationRepository repository) {
        return new InvestigationQueriesService(repository);
    }

}
