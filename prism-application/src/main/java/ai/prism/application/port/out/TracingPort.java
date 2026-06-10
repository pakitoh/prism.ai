package ai.prism.application.port.out;

import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.TimeWindow;

/**
 * Outbound port for fetching traces (Tempo). Implemented by an adapter and
 * invoked as a tool during the reasoning loop.
 */
public interface TracingPort {

    /** Fetches a single trace by id and returns it as a {@link Signal}. */
    Signal getTrace(String traceId);

    /** Searches for traces of a service in a window and returns the result as a {@link Signal}. */
    Signal searchTraces(String service, TimeWindow window);
}
