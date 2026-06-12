package ai.prism.adapters.out.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import ai.prism.application.port.in.InvestigateUseCase;
import ai.prism.application.port.out.InvestigationKnowledgeBase;
import ai.prism.domain.investigation.Confidence;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationRequest;
import ai.prism.domain.investigation.Signal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RememberingInvestigateUseCaseTest {

    private static final InvestigationRequest REQUEST = InvestigationRequest.manual("why errors?");

    private final List<Investigation> remembered = new ArrayList<>();
    private final InvestigationKnowledgeBase knowledgeBase = new InvestigationKnowledgeBase() {
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
        concluded.conclude(new Finding("rc", "ev", "act", Confidence.HIGH));

        new RememberingInvestigateUseCase(request -> concluded, knowledgeBase).handle(REQUEST);

        assertThat(remembered).containsExactly(concluded);
    }

    @Test
    void doesNotRememberFailedInvestigations() {
        Investigation failed = Investigation.open(REQUEST);
        failed.start();
        failed.fail("model timed out");

        new RememberingInvestigateUseCase(request -> failed, knowledgeBase).handle(REQUEST);

        assertThat(remembered).isEmpty();
    }

    @Test
    void doesNotFailTheInvestigationWhenRememberingThrows() {
        Investigation concluded = Investigation.open(REQUEST);
        concluded.start();
        concluded.conclude(new Finding("rc", "ev", "act", Confidence.HIGH));
        InvestigationKnowledgeBase throwing = new InvestigationKnowledgeBase() {
            @Override
            public void remember(Investigation investigation) {
                throw new RuntimeException("embedding rate limit");
            }

            @Override
            public Signal findSimilar(String query) {
                throw new UnsupportedOperationException("not used in this test");
            }
        };

        Investigation result = new RememberingInvestigateUseCase(request -> concluded, throwing).handle(REQUEST);

        assertThat(result).isSameAs(concluded);
    }
}
