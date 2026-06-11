package ai.prism.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ai.prism.application.port.in.InvestigateUseCase;
import ai.prism.domain.investigation.Confidence;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationRequest;
import ai.prism.domain.investigation.RequestSource;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InvestigationControllerTest {

    @Mock
    private InvestigateUseCase useCase;

    @Test
    void mapsAConcludedInvestigationToTheResponse() {
        Investigation concluded = Investigation.open(InvestigationRequest.manual("why errors?"));
        concluded.start();
        concluded.conclude(new Finding("DB pool exhausted", "errors track saturation", "raise the pool", Confidence.HIGH));
        when(useCase.handle(any())).thenReturn(concluded);

        InvestigationController controller = new InvestigationController(useCase);
        InvestigationResponse response = controller.investigate(
                new InvestigateRequestBody("why errors?", null, null, null));

        assertThat(response.id()).isEqualTo(concluded.id().toString());
        assertThat(response.status()).isEqualTo("CONCLUDED");
        assertThat(response.failureReason()).isNull();
        assertThat(response.finding().rootCause()).isEqualTo("DB pool exhausted");
        assertThat(response.finding().confidence()).isEqualTo("HIGH");
    }

    @Test
    void buildsADomainRequestWithServiceAndWindow() {
        Investigation failed = Investigation.open(InvestigationRequest.manual("x"));
        failed.start();
        failed.fail("collector down");
        when(useCase.handle(any())).thenReturn(failed);

        InvestigationController controller = new InvestigationController(useCase);
        controller.investigate(new InvestigateRequestBody(
                "high latency", "checkout", "2026-06-10T10:00:00Z", "2026-06-10T10:30:00Z"));

        ArgumentCaptor<InvestigationRequest> request = ArgumentCaptor.forClass(InvestigationRequest.class);
        org.mockito.Mockito.verify(useCase).handle(request.capture());
        assertThat(request.getValue().query()).isEqualTo("high latency");
        assertThat(request.getValue().service()).contains("checkout");
        assertThat(request.getValue().source()).isEqualTo(RequestSource.MANUAL);
        assertThat(request.getValue().window()).hasValueSatisfying(w ->
                assertThat(w.from()).isEqualTo(Instant.parse("2026-06-10T10:00:00Z")));
    }

    @Test
    void mapsAFailedInvestigationToTheResponse() {
        Investigation failed = Investigation.open(InvestigationRequest.manual("x"));
        failed.start();
        failed.fail("model timed out");
        when(useCase.handle(any())).thenReturn(failed);

        InvestigationController controller = new InvestigationController(useCase);
        InvestigationResponse response = controller.investigate(new InvestigateRequestBody("x", null, null, null));

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.finding()).isNull();
        assertThat(response.failureReason()).isEqualTo("model timed out");
    }
}
