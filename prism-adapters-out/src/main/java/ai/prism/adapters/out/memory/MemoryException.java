package ai.prism.adapters.out.memory;

/**
 * Wraps a low-level failure (SQL or embedding) raised while storing or recalling
 * investigation memory, as an unchecked exception at the memory adapter boundary.
 */
public class MemoryException extends RuntimeException {

    public MemoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
