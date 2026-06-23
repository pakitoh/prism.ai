package ai.prism.adapters.out.investigation;

import ai.prism.application.service.InvestigationRunner;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationStatus;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Instruments running an investigation: one observation per run (yielding a
 * {@code prism.investigation} span and timer, tagged by source and outcome) plus
 * lifecycle logs. Wraps the {@link InvestigationRunner} so the application stays free
 * of any observability dependency, and so sync and background (async) runs are
 * instrumented identically.
 */
public class ObservedInvestigationRunner implements InvestigationRunner {

    private static final Logger log = LoggerFactory.getLogger(ObservedInvestigationRunner.class);

    private final InvestigationRunner delegate;
    private final ObservationRegistry registry;

    public ObservedInvestigationRunner(InvestigationRunner delegate, ObservationRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public Investigation run(Investigation investigation) {
        Observation observation = Observation.createNotStarted("prism.investigation", registry)
                .lowCardinalityKeyValue("source", investigation.request().source().name());
        // Back-pointer to the request trace that started this (separate) investigation trace.
        RequestTrace.currentTraceId()
                .ifPresent(traceId -> observation.highCardinalityKeyValue("request.trace_id", traceId));
        return observation.observe(() -> {
            log.info("Investigation started: service={} query=\"{}\"",
                    investigation.request().service().orElse("-"), investigation.request().query());
            Investigation result = delegate.run(investigation);
            observation.lowCardinalityKeyValue("outcome", result.status().name());
            observation.highCardinalityKeyValue("investigation.id", result.id().toString());
            observation.highCardinalityKeyValue("signals.gathered",
                    String.valueOf(result.signals().size()));
            logOutcome(result);
            return result;
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
