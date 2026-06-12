package ai.prism.application.port.out;

import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.TimeWindow;

/**
 * Outbound port for querying metrics (Prometheus). Implemented by an adapter
 * and invoked as a tool during the reasoning loop.
 */
public interface MetricsPort {

    /**
     * Runs a range query and returns the result as a {@link Signal}.
     *
     * @param promQl a PromQL expression
     * @param window the time range to query over
     */
    Signal queryRange(String promQl, TimeWindow window);
}
