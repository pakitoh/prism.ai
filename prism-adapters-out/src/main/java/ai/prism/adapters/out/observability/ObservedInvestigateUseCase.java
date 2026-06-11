package ai.prism.adapters.out.observability;

import ai.prism.application.port.in.InvestigateUseCase;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationRequest;
import ai.prism.domain.investigation.InvestigationStatus;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Instruments the investigation use case: one observation per investigation
 * (yielding a {@code prism.investigation} span and timer, tagged by source and
 * outcome) plus lifecycle logs. Wraps the real {@link InvestigateUseCase}; the
 * application stays free of any observability dependency.
 */
public class ObservedInvestigateUseCase implements InvestigateUseCase {

    private static final Logger log = LoggerFactory.getLogger(ObservedInvestigateUseCase.class);

    private final InvestigateUseCase delegate;
    private final ObservationRegistry registry;

    public ObservedInvestigateUseCase(InvestigateUseCase delegate, ObservationRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public Investigation handle(InvestigationRequest request) {
        Observation observation = Observation.createNotStarted("prism.investigation", registry)
                .lowCardinalityKeyValue("source", request.source().name());
        return observation.observe(() -> {
            log.info("Investigation started: service={} query=\"{}\"",
                    request.service().orElse("-"), request.query());
            Investigation investigation = delegate.handle(request);
            observation.lowCardinalityKeyValue("outcome", investigation.status().name());
            observation.highCardinalityKeyValue("investigation.id", investigation.id().toString());
            observation.highCardinalityKeyValue("signals.gathered",
                    String.valueOf(investigation.signals().size()));
            logOutcome(investigation);
            return investigation;
        });
    }

    private static void logOutcome(Investigation investigation) {
        if (investigation.status() == InvestigationStatus.CONCLUDED) {
            log.info("Investigation {} concluded ({} signals): {}",
                    investigation.id(),
                    investigation.signals().size(),
                    investigation.finding().map(Finding::rootCause).orElse("?"));
        } else {
            log.warn("Investigation {} failed: {}",
                    investigation.id(),
                    investigation.failureReason().orElse("?"));
        }
    }
}
