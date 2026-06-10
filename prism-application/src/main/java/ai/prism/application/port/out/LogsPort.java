package ai.prism.application.port.out;

import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.TimeWindow;

/**
 * Outbound port for searching logs (Loki). Implemented by an adapter and
 * invoked as a tool during the reasoning loop.
 */
public interface LogsPort {

    /**
     * Runs a log query and returns the result as a {@link Signal}.
     *
     * @param logQl  a LogQL expression
     * @param window the time range to query over
     */
    Signal search(String logQl, TimeWindow window);
}
