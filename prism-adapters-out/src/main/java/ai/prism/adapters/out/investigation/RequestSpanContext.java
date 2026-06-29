package ai.prism.adapters.out.investigation;

import io.opentelemetry.api.trace.SpanContext;
import java.util.Optional;

/**
 * Carries the originating request's {@link SpanContext} across the async hop to the investigation
 * worker, so {@link ObservedInvestigationRunner} can add a span <em>link</em> from the (separate,
 * root) {@code prism.investigation} trace back to the request trace that started it — and stamp
 * {@code request.trace_id}. Only the context value is carried (not made current), so the
 * investigation gets its own trace rather than becoming a child of the short-lived request span.
 * Set by the executor wrapper at the composition root.
 */
public final class RequestSpanContext {

    private static final ThreadLocal<SpanContext> CONTEXT = new ThreadLocal<>();

    private RequestSpanContext() {
    }

    /** Runs {@code task} with {@code context} visible to {@link #current()}; always cleans up. */
    public static void runWith(SpanContext context, Runnable task) {
        if (context == null || !context.isValid()) {
            task.run();
            return;
        }
        CONTEXT.set(context);
        try {
            task.run();
        } finally {
            CONTEXT.remove();
        }
    }

    /** The originating request's span context, if one was propagated to this thread. */
    public static Optional<SpanContext> current() {
        return Optional.ofNullable(CONTEXT.get());
    }
}
