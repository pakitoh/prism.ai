package ai.prism.domain.investigation;

/**
 * Thrown when an operation is attempted that is not legal for the
 * investigation's current {@link InvestigationStatus}.
 */
public class InvestigationStateException extends RuntimeException {

    public InvestigationStateException(String message) {
        super(message);
    }
}
