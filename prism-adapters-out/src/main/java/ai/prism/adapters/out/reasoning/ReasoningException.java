package ai.prism.adapters.out.reasoning;

/**
 * Raised when the model's response cannot be turned into a reasoning step —
 * for example, no tool was called or its arguments could not be parsed. The
 * application loop catches this and fails the investigation.
 */
class ReasoningException extends RuntimeException {

    ReasoningException(String message) {
        super(message);
    }

    ReasoningException(String message, Throwable cause) {
        super(message, cause);
    }
}
