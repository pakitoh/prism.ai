package ai.prism.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import ai.prism.application.port.out.InvestigationRepository;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationId;
import ai.prism.domain.investigation.InvestigationRequest;
import ai.prism.domain.investigation.InvestigationStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class InvestigationCommandsServiceTest {

    private static final InvestigationRequest REQUEST = InvestigationRequest.manual("why errors?");

    private final InvestigationRepository repository = mock(InvestigationRepository.class);
    /** Runs scheduled work inline, so the test is deterministic. */
    private final Executor sameThread = Runnable::run;

    @Test
    void submitPersistsPendingFirstThenSchedulesTheRunAndReturnsTheId() {
        List<Investigation> run = new ArrayList<>();
        InvestigationRunner runner = investigation -> {
            run.add(investigation);
            return investigation;
        };
        InvestigationCommandsService service =
                new InvestigationCommandsService(runner, repository, sameThread);

        InvestigationId id = service.submit(REQUEST);

        // The same aggregate is persisted (PENDING) up front and then handed to the runner.
        assertThat(run).hasSize(1);
        assertThat(run.get(0).id()).isEqualTo(id);
        InOrder order = inOrder(repository);
        order.verify(repository).save(any(Investigation.class)); // PENDING persisted before the run
    }

    @Test
    void handleRunsSynchronouslyAndReturnsTheResult() {
        InvestigationRunner runner = investigation -> {
            investigation.start();
            investigation.fail("model timed out");
            return investigation;
        };
        InvestigationCommandsService service =
                new InvestigationCommandsService(runner, repository, sameThread);

        Investigation result = service.handle(REQUEST);

        assertThat(result.status()).isEqualTo(InvestigationStatus.FAILED);
        assertThat(result.failureReason()).contains("model timed out");
    }
}
