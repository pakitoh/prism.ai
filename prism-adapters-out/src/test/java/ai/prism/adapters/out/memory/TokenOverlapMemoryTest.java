package ai.prism.adapters.out.memory;

import static org.assertj.core.api.Assertions.assertThat;

import ai.prism.domain.investigation.Confidence;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationRequest;
import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.SignalType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TokenOverlapMemoryTest {

    private static final Instant NOW = Instant.parse("2026-06-11T10:00:00Z");

    private final TokenOverlapMemory knowledgeBase =
            new TokenOverlapMemory(Clock.fixed(NOW, ZoneOffset.UTC));

    private static Investigation concluded(String query, String rootCause) {
        Investigation investigation = Investigation.open(InvestigationRequest.manual(query));
        investigation.start();
        investigation.conclude(Finding.of(rootCause, "evidence", "raise the pool", Confidence.HIGH));
        return investigation;
    }

    @Test
    void returnsAMemorySignalRankingTheMostSimilarPastInvestigation() {
        knowledgeBase.remember(concluded("checkout service latency spike", "slow database query"));
        knowledgeBase.remember(concluded("payment timeouts", "expired TLS certificate"));

        Signal signal = knowledgeBase.findSimilar("why is the checkout service slow?");

        assertThat(signal.type()).isEqualTo(SignalType.MEMORY);
        assertThat(signal.observedAt()).isEqualTo(NOW);
        assertThat(signal.content())
                .contains("checkout service latency spike")
                .doesNotContain("payment timeouts");
    }

    @Test
    void reportsWhenNothingSimilarIsRemembered() {
        knowledgeBase.remember(concluded("payment timeouts", "expired TLS certificate"));

        Signal signal = knowledgeBase.findSimilar("disk pressure on the cache nodes");

        assertThat(signal.type()).isEqualTo(SignalType.MEMORY);
        assertThat(signal.content()).isEqualTo("No similar past investigations found.");
    }

    @Test
    void ignoresInvestigationsThatDidNotConclude() {
        Investigation failed = Investigation.open(InvestigationRequest.manual("checkout latency"));
        failed.start();
        failed.fail("model timed out");

        knowledgeBase.remember(failed);

        assertThat(knowledgeBase.findSimilar("checkout latency").content())
                .isEqualTo("No similar past investigations found.");
    }

    @Test
    void reRememberingTheSameInvestigationReplacesRatherThanDuplicates() {
        Investigation investigation = concluded("checkout service latency spike", "slow database query");
        knowledgeBase.remember(investigation);
        knowledgeBase.remember(investigation); // same id again — must not create a second entry

        Signal signal = knowledgeBase.findSimilar("why is the checkout service slow?");

        long occurrences = signal.content().lines()
                .filter(line -> line.contains("checkout service latency spike"))
                .count();
        assertThat(occurrences).isEqualTo(1);
    }
}
