package ai.prism.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.prism.adapters.in.rest.AlertmanagerWebhook.Alert;
import ai.prism.application.port.in.InvestigationCommandsUseCase;
import ai.prism.domain.investigation.InvestigationId;
import ai.prism.domain.investigation.InvestigationRequest;
import ai.prism.domain.investigation.RequestSource;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class AlertWebhookControllerTest {

    @Mock
    private InvestigationCommandsUseCase commands;

    private AlertWebhookController controller() {
        return new AlertWebhookController(commands, Clock.systemUTC());
    }

    private static Alert alert(String status, String alertName) {
        return new Alert(status,
                Map.of("alertname", alertName, "service", "checkout"),
                Map.of("summary", alertName + " summary"),
                "2026-06-16T11:30:00Z", "2026-06-16T11:45:00Z", "fp-" + alertName, "g");
    }

    @Test
    void submitsOneInvestigationPerFiringAlertAndSkipsResolved() {
        InvestigationId one = InvestigationId.newId();
        InvestigationId two = InvestigationId.newId();
        when(commands.submit(any())).thenReturn(one, two);

        AlertmanagerWebhook webhook = new AlertmanagerWebhook("firing",
                List.of(alert("firing", "HighErrorRate"),
                        alert("resolved", "OldAlert"),
                        alert("firing", "Latency")));

        ResponseEntity<AlertsAcceptedResponse> response = controller().receive(webhook);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().investigationIds())
                .containsExactly(one.toString(), two.toString());

        ArgumentCaptor<InvestigationRequest> captor = ArgumentCaptor.forClass(InvestigationRequest.class);
        verify(commands, org.mockito.Mockito.times(2)).submit(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(request ->
                assertThat(request.source()).isEqualTo(RequestSource.ALERT));
        assertThat(captor.getAllValues().get(0).query()).isEqualTo("HighErrorRate: HighErrorRate summary");
        assertThat(captor.getAllValues().get(1).query()).isEqualTo("Latency: Latency summary");
    }

    @Test
    void acceptsAResolvedOnlyDeliveryWithoutSpawningAnything() {
        ResponseEntity<AlertsAcceptedResponse> response = controller().receive(
                new AlertmanagerWebhook("resolved", List.of(alert("resolved", "OldAlert"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().investigationIds()).isEmpty();
        verify(commands, never()).submit(any());
    }

    @Test
    void toleratesAnEmptyAlertsList() {
        ResponseEntity<AlertsAcceptedResponse> response = controller().receive(
                new AlertmanagerWebhook("firing", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().investigationIds()).isEmpty();
        verify(commands, never()).submit(any());
    }
}
