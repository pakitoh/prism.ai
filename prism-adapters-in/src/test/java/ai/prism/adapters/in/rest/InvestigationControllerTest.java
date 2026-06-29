package ai.prism.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ai.prism.application.port.in.InvestigationCommandsUseCase;
import ai.prism.application.port.in.InvestigationQueriesUseCase;
import ai.prism.application.port.out.DashboardLinkPort;
import ai.prism.domain.investigation.Confidence;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationId;
import ai.prism.domain.investigation.InvestigationRequest;
import ai.prism.domain.investigation.RequestSource;
import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.SignalType;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class InvestigationControllerTest {

    @Mock
    private InvestigationCommandsUseCase useCase;
    @Mock
    private InvestigationQueriesUseCase queries;
    @Mock
    private DashboardLinkPort links;

    private InvestigationController controller() {
        return new InvestigationController(useCase, queries, links);
    }

    @Test
    void acceptsAnInvestigationAndReturns202WithItsId() {
        InvestigationId id = InvestigationId.newId();
        when(useCase.submit(any())).thenReturn(id);

        ResponseEntity<InvestigationAcceptedResponse> response = controller()
                .investigate(new InvestigateRequestBody("why errors?", null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().id()).isEqualTo(id.toString());
        assertThat(response.getBody().status()).isEqualTo("PENDING");
    }

    @Test
    void buildsADomainRequestWithServiceAndWindow() {
        when(useCase.submit(any())).thenReturn(InvestigationId.newId());

        controller().investigate(new InvestigateRequestBody(
                "high latency", "checkout", "2026-06-10T10:00:00Z", "2026-06-10T10:30:00Z"));

        ArgumentCaptor<InvestigationRequest> request = ArgumentCaptor.forClass(InvestigationRequest.class);
        org.mockito.Mockito.verify(useCase).submit(request.capture());
        assertThat(request.getValue().query()).isEqualTo("high latency");
        assertThat(request.getValue().service()).contains("checkout");
        assertThat(request.getValue().source()).isEqualTo(RequestSource.MANUAL);
        assertThat(request.getValue().window()).hasValueSatisfying(w ->
                assertThat(w.from()).isEqualTo(Instant.parse("2026-06-10T10:00:00Z")));
    }

    @Test
    void getReturnsTheInvestigationWhenFound() {
        Investigation concluded = Investigation.open(InvestigationRequest.manual("why errors?"));
        concluded.start();
        concluded.conclude(Finding.of("DB pool exhausted", "errors track saturation", "raise the pool", Confidence.HIGH));
        when(queries.findById(any())).thenReturn(Optional.of(concluded));

        ResponseEntity<InvestigationResponse> response = controller().get(concluded.id().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().id()).isEqualTo(concluded.id().toString());
        assertThat(response.getBody().status()).isEqualTo("CONCLUDED");
        assertThat(response.getBody().finding().rootCause()).isEqualTo("DB pool exhausted");
    }

    @Test
    void exposesSignalLinksAndTheNominatedPrimaryLink() {
        Investigation concluded = Investigation.open(InvestigationRequest.manual("why errors?"));
        concluded.start();
        Signal metric = Signal.of(SignalType.METRIC, "rate(http_errors_total[5m])", "0.42", Instant.now());
        concluded.recordSignal(metric);
        // Nominate the (only) signal, index 0, as the headline.
        concluded.conclude(new Finding("DB pool exhausted", "errors track saturation",
                "raise the pool", Confidence.HIGH, OptionalInt.of(0)));
        when(queries.findById(any())).thenReturn(Optional.of(concluded));
        when(links.dashboardLink(any())).thenReturn(Optional.of(URI.create("http://grafana/explore?x=1")));

        ResponseEntity<InvestigationResponse> response = controller().get(concluded.id().toString());

        assertThat(response.getBody().signals()).singleElement().satisfies(s -> {
            assertThat(s.type()).isEqualTo("METRIC");
            assertThat(s.link()).isEqualTo("http://grafana/explore?x=1");
        });
        assertThat(response.getBody().primaryLink()).isEqualTo("http://grafana/explore?x=1");
    }

    @Test
    void getReturns404WhenMissing() {
        when(queries.findById(any())).thenReturn(Optional.empty());

        ResponseEntity<InvestigationResponse> response = controller().get(InvestigationId.newId().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }
}
