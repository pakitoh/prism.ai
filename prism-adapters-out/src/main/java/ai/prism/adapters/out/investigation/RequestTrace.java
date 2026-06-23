package ai.prism.adapters.out.investigation;

import java.util.Optional;

/**
 * Carries the originating request's trace id across the async hop to the
 * investigation worker thread, so {@link ObservedInvestigationRunner} can stamp it
 * on the (separate, root) {@code prism.investigation} span as a back-pointer —
 * letting you navigate from an investigation trace to the request that started it.
 *
 * <p>Only the id <em>value</em> is carried, deliberately not the live trace context:
 * the investigation gets its own root trace rather than becoming a child of the
 * short-lived request span. Set by the executor wrapper at the composition root.
 */
public final class RequestTrace {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private RequestTrace() {
    }

    /** Runs {@code task} with {@code traceId} visible to {@link #currentTraceId()}; always cleans up. */
    public static void runWith(String traceId, Runnable task) {
        if (traceId == null || traceId.isBlank()) {
            task.run();
            return;
        }
        TRACE_ID.set(traceId);
        try {
            task.run();
        } finally {
            TRACE_ID.remove();
        }
    }

    /** The originating request's trace id, if one was propagated to this thread. */
    public static Optional<String> currentTraceId() {
        return Optional.ofNullable(TRACE_ID.get());
    }
}
