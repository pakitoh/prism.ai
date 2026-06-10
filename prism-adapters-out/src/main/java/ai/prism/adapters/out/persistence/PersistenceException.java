package ai.prism.adapters.out.persistence;

/**
 * Wraps a low-level persistence failure (SQL or JSON serialization) as an
 * unchecked exception at the repository boundary.
 */
public class PersistenceException extends RuntimeException {

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
