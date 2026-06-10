package ai.prism.domain.investigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root for the investigation lifecycle.
 *
 * <p>Signals may only be recorded while the investigation is
 * {@link InvestigationStatus#IN_PROGRESS}. State transitions are enforced;
 * illegal transitions raise {@link InvestigationStateException}.
 */
public class Investigation {

    private final InvestigationId id;
    private final InvestigationRequest request;
    private final List<Signal> signals;
    private InvestigationStatus status;
    private Finding finding;
    private String failureReason;

    private Investigation(InvestigationId id, InvestigationRequest request) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.request = Objects.requireNonNull(request, "request must not be null");
        this.signals = new ArrayList<>();
        this.status = InvestigationStatus.PENDING;
    }

    /** Opens a new investigation in {@link InvestigationStatus#PENDING}. */
    public static Investigation open(InvestigationRequest request) {
        return new Investigation(InvestigationId.newId(), request);
    }

    /** Reconstitutes an investigation from persistence. */
    public static Investigation rehydrate(
            InvestigationId id,
            InvestigationRequest request,
            InvestigationStatus status,
            List<Signal> signals,
            Finding finding,
            String failureReason) {
        Investigation investigation = new Investigation(id, request);
        investigation.status = Objects.requireNonNull(status, "status must not be null");
        investigation.signals.addAll(Objects.requireNonNull(signals, "signals must not be null"));
        investigation.finding = finding;
        investigation.failureReason = failureReason;
        return investigation;
    }

    public void start() {
        requireStatus(InvestigationStatus.PENDING, "start");
        this.status = InvestigationStatus.IN_PROGRESS;
    }

    public void recordSignal(Signal signal) {
        Objects.requireNonNull(signal, "signal must not be null");
        requireStatus(InvestigationStatus.IN_PROGRESS, "record a signal on");
        this.signals.add(signal);
    }

    public void conclude(Finding finding) {
        Objects.requireNonNull(finding, "finding must not be null");
        requireStatus(InvestigationStatus.IN_PROGRESS, "conclude");
        this.finding = finding;
        this.status = InvestigationStatus.CONCLUDED;
    }

    public void fail(String reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        if (status == InvestigationStatus.CONCLUDED || status == InvestigationStatus.FAILED) {
            throw new InvestigationStateException(
                    "Cannot fail an investigation that is already " + status);
        }
        this.failureReason = reason;
        this.status = InvestigationStatus.FAILED;
    }

    private void requireStatus(InvestigationStatus required, String action) {
        if (status != required) {
            throw new InvestigationStateException(
                    "Cannot " + action + " an investigation in status " + status
                            + "; expected " + required);
        }
    }

    public InvestigationId id() {
        return id;
    }

    public InvestigationRequest request() {
        return request;
    }

    public InvestigationStatus status() {
        return status;
    }

    /** An immutable snapshot of the signals gathered so far, in recording order. */
    public List<Signal> signals() {
        return List.copyOf(signals);
    }

    public Optional<Finding> finding() {
        return Optional.ofNullable(finding);
    }

    public Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
    }
}
