package ai.prism.application.service;

import ai.prism.application.port.in.InvestigationCommandsUseCase;
import ai.prism.application.port.out.InvestigationRepository;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationId;
import ai.prism.domain.investigation.InvestigationRequest;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Entry point for investigations: opens the aggregate and either runs it
 * synchronously or schedules it on a background worker, delegating the actual
 * work to the (decorated) {@link InvestigationRunner}.
 *
 * <p>{@link #submit} persists the {@code PENDING} aggregate before scheduling so a
 * client polling {@code GET /investigations/{id}} immediately finds the record, then
 * returns the id without blocking on the loop. {@link #handle} runs to completion on
 * the calling thread for callers that want the result inline.
 */
public class InvestigationCommandsService implements InvestigationCommandsUseCase {

    private final InvestigationRunner runner;
    private final InvestigationRepository repository;
    private final Executor executor;

    public InvestigationCommandsService(InvestigationRunner runner,
                                           InvestigationRepository repository,
                                           Executor executor) {
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    @Override
    public InvestigationId submit(InvestigationRequest request) {
        Investigation investigation = Investigation.open(request);
        // Persist the PENDING aggregate up front so polling finds it before the loop runs.
        repository.save(investigation);
        executor.execute(() -> runner.run(investigation));
        return investigation.id();
    }

    @Override
    public Investigation handle(InvestigationRequest request) {
        return runner.run(Investigation.open(request));
    }
}
