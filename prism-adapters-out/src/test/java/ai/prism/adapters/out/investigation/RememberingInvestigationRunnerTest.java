package ai.prism.adapters.out.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import ai.prism.application.port.out.MemoryPort;
import ai.prism.domain.investigation.Confidence;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationRequest;
import ai.prism.domain.investigation.Signal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RememberingInvestigationRunnerTest {

    private static final InvestigationRequest REQUEST = InvestigationRequest.manual("why errors?");

    private final List<Investigation> remembered = new ArrayList<>();
    private final MemoryPort knowledgeBase = new MemoryPort() {
        @Override
        public void remember(Investigation investigation) {
            remembered.add(investigation);
        }

        @Override
        public Signal findSimilar(String query) {
            throw new UnsupportedOperationException("not used in this test");
        }
    };

    @Test
    void remembersConcludedInvestigations() {
        Investigation concluded = Investigation.open(REQUEST);
        concluded.start();
        concluded.conclude(Finding.of("rc", "ev", "act", Confidence.HIGH));

        new RememberingInvestigationRunner(investigation -> concluded, knowledgeBase).run(Investigation.open(REQUEST));

        assertThat(remembered).containsExactly(concluded);
    }

    @Test
    void doesNotRememberFailedInvestigations() {
        Investigation failed = Investigation.open(REQUEST);
        failed.start();
        failed.fail("model timed out");

        new RememberingInvestigationRunner(investigation -> failed, knowledgeBase).run(Investigation.open(REQUEST));

        assertThat(remembered).isEmpty();
    }

    @Test
    void doesNotFailTheInvestigationWhenRememberingThrows() {
        Investigation concluded = Investigation.open(REQUEST);
        concluded.start();
        concluded.conclude(Finding.of("rc", "ev", "act", Confidence.HIGH));
        MemoryPort throwing = new MemoryPort() {
            @Override
            public void remember(Investigation investigation) {
                throw new RuntimeException("embedding rate limit");
            }

            @Override
            public Signal findSimilar(String query) {
                throw new UnsupportedOperationException("not used in this test");
            }
        };

        Investigation result = new RememberingInvestigationRunner(investigation -> concluded, throwing)
                .run(Investigation.open(REQUEST));

        assertThat(result).isSameAs(concluded);
    }
}
