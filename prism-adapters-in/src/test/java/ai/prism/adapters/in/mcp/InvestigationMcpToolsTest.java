package ai.prism.adapters.in.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.prism.application.port.in.InvestigationCommandsUseCase;
import ai.prism.application.port.in.InvestigationQueriesUseCase;
import ai.prism.domain.investigation.Confidence;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationId;
import ai.prism.domain.investigation.InvestigationRequest;
import ai.prism.domain.investigation.RequestSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InvestigationMcpToolsTest {

    @Mock
    private InvestigationCommandsUseCase commands;
    @Mock
    private InvestigationQueriesUseCase queries;

    private InvestigationMcpTools tools() {
        return new InvestigationMcpTools(commands, queries);
    }

    @Test
    void investigateSubmitsTheRequestAndReturnsTheIdPending() {
        InvestigationId id = InvestigationId.newId();
        when(commands.submit(any())).thenReturn(id);

        InvestigationMcpTools.AcceptedInvestigation accepted = tools().investigate(
                "high latency", "checkout", "2026-06-10T10:00:00Z", "2026-06-10T10:30:00Z");

        assertThat(accepted.id()).isEqualTo(id.toString());
        assertThat(accepted.status()).isEqualTo("PENDING");

        ArgumentCaptor<InvestigationRequest> request = ArgumentCaptor.forClass(InvestigationRequest.class);
        verify(commands).submit(request.capture());
        assertThat(request.getValue().query()).isEqualTo("high latency");
        assertThat(request.getValue().service()).contains("checkout");
        assertThat(request.getValue().source()).isEqualTo(RequestSource.MANUAL);
        assertThat(request.getValue().window()).hasValueSatisfying(w ->
                assertThat(w.from()).isEqualTo(Instant.parse("2026-06-10T10:00:00Z")));
    }

    @Test
    void investigateTreatsBlankOptionalsAsAbsent() {
        when(commands.submit(any())).thenReturn(InvestigationId.newId());

        tools().investigate("why errors?", "  ", "", null);

        ArgumentCaptor<InvestigationRequest> request = ArgumentCaptor.forClass(InvestigationRequest.class);
        verify(commands).submit(request.capture());
        assertThat(request.getValue().service()).isEmpty();
        assertThat(request.getValue().window()).isEmpty();
    }

    @Test
    void getInvestigationMapsAConcludedInvestigation() {
        Investigation concluded = Investigation.open(InvestigationRequest.manual("why errors?"));
        concluded.start();
        concluded.conclude(new Finding("DB pool exhausted", "ev", "raise the pool", Confidence.HIGH));
        when(queries.findById(any())).thenReturn(Optional.of(concluded));

        InvestigationMcpTools.InvestigationView view = tools().getInvestigation(concluded.id().toString());

        assertThat(view.status()).isEqualTo("CONCLUDED");
        assertThat(view.rootCause()).isEqualTo("DB pool exhausted");
        assertThat(view.confidence()).isEqualTo("HIGH");
    }

    @Test
    void getInvestigationReturnsNotFoundWhenMissing() {
        when(queries.findById(any())).thenReturn(Optional.empty());

        InvestigationMcpTools.InvestigationView view = tools().getInvestigation(InvestigationId.newId().toString());

        assertThat(view.status()).isEqualTo("NOT_FOUND");
        assertThat(view.rootCause()).isNull();
    }

    @Test
    void listRecentDefaultsToTwentyWhenLimitOmitted() {
        when(queries.recent(20)).thenReturn(List.of());

        tools().listRecentInvestigations(null);

        verify(queries).recent(20);
    }
}
