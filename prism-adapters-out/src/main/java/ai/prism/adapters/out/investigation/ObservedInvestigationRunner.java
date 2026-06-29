package ai.prism.adapters.out.investigation;

import ai.prism.application.service.InvestigationRunner;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationStatus;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Instruments running an investigation with a {@code prism.investigation} span — created via
 * the OpenTelemetry API (the app's single telemetry façade), not Micrometer, so it lands in the
 * same SDK as the auto-instrumentation and the Logback OTLP appender. The span is made current
 * for the whole run, so every reasoning step and tool-call log emitted on this thread carries
 * the investigation's trace id. Wraps the {@link InvestigationRunner} so the application layer
 * stays free of any observability dependency, and sync and async runs are instrumented alike.
 */
public class ObservedInvestigationRunner implements InvestigationRunner {

    private static final Logger log = LoggerFactory.getLogger(ObservedInvestigationRunner.class);

    private static final AttributeKey<String> SOURCE = AttributeKey.stringKey("source");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");

    private final InvestigationRunner delegate;
    private final Tracer tracer;
    private final LongHistogram duration;

    public ObservedInvestigationRunner(InvestigationRunner delegate, OpenTelemetry openTelemetry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        Objects.requireNonNull(openTelemetry, "openTelemetry must not be null");
        this.tracer = openTelemetry.getTracer("ai.prism.investigation");
        this.duration = openTelemetry.getMeter("ai.prism.investigation")
                .histogramBuilder("prism.investigation.duration").ofLongs().setUnit("ms").build();
    }

    @Override
    public Investigation run(Investigation investigation) {
        String source = investigation.request().source().name();
        SpanBuilder builder = tracer.spanBuilder("prism.investigation").setAttribute("source", source);
        // Link this (separate, root) investigation trace back to the request trace that started it.
        RequestSpanContext.current().ifPresent(requestContext -> {
            builder.addLink(requestContext);
            builder.setAttribute("request.trace_id", requestContext.getTraceId());
        });
        Span span = builder.startSpan();
        long start = System.nanoTime();
        String outcome = "ERROR";
        try (Scope ignored = span.makeCurrent()) {
            log.info("Investigation started: service={} query=\"{}\"",
                    investigation.request().service().orElse("-"), investigation.request().query());
            Investigation result = delegate.run(investigation);
            outcome = result.status().name();
            span.setAttribute("outcome", outcome);
            span.setAttribute("investigation.id", result.id().toString());
            span.setAttribute("signals.gathered", result.signals().size());
            logOutcome(result);
            return result;
        } catch (RuntimeException failure) {
            span.setStatus(StatusCode.ERROR, failure.getMessage() != null ? failure.getMessage() : failure.toString());
            throw failure;
        } finally {
            duration.record((System.nanoTime() - start) / 1_000_000L,
                    Attributes.of(SOURCE, source, OUTCOME, outcome));
            span.end();
        }
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
