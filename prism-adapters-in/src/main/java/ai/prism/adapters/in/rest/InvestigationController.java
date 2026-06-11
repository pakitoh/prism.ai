package ai.prism.adapters.in.rest;

import ai.prism.application.port.in.InvestigateUseCase;
import ai.prism.domain.investigation.Investigation;
import java.util.Objects;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound REST adapter: triggers an investigation and returns its outcome.
 * Synchronous for now — the request blocks until the investigation concludes
 * or fails (async delivery arrives in Phase 3).
 */
@RestController
@RequestMapping("/investigations")
public class InvestigationController {

    private final InvestigateUseCase investigateUseCase;

    public InvestigationController(InvestigateUseCase investigateUseCase) {
        this.investigateUseCase = Objects.requireNonNull(investigateUseCase, "investigateUseCase must not be null");
    }

    @PostMapping
    public InvestigationResponse investigate(@RequestBody InvestigateRequestBody body) {
        Investigation investigation = investigateUseCase.handle(body.toDomain());
        return InvestigationResponse.from(investigation);
    }
}
