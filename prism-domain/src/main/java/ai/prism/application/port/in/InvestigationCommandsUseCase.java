package ai.prism.application.port.in;

import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationId;
import ai.prism.domain.investigation.InvestigationRequest;

/**
 * Inbound port: the entry point for running an investigation. Inbound adapters
 * (REST, MCP server, Kafka consumer) depend on this interface, not on the
 * concrete service.
 */
public interface InvestigationCommandsUseCase {

    /**
     * Starts an investigation asynchronously: persists it as {@code PENDING},
     * schedules the work on a background worker and returns its id immediately.
     * Callers poll {@link InvestigationQueriesUseCase#findById} for status and result.
     */
    InvestigationId submit(InvestigationRequest request);

    /**
     * Runs an investigation to completion on the calling thread and returns the
     * resulting aggregate, either {@code CONCLUDED} with a finding or {@code FAILED}
     * with a reason.
     */
    Investigation handle(InvestigationRequest request);
}
