package org.eptura;

/**
 * Base exception for GraphAuthProvider-specific errors.
 * <p>
 * This exception is defined at the interface level to avoid coupling
 * between {@link IGraphAuthProvider} and its implementations.
 * </p>
 *
 * @see IGraphAuthProvider
 */
public class GraphAuthException extends RuntimeException {

    private final boolean circuitOpen;

    /**
     * Creates a new GraphAuthException.
     *
     * @param message Error message
     * @param cause   Underlying cause (may be null)
     */
    public GraphAuthException(String message, Throwable cause) {
        this(message, cause, false);
    }

    /**
     * Creates a new GraphAuthException with circuit breaker state.
     *
     * @param message     Error message
     * @param cause       Underlying cause (may be null)
     * @param circuitOpen Whether this exception was thrown because the circuit breaker is open
     */
    public GraphAuthException(String message, Throwable cause, boolean circuitOpen) {
        super(message, cause);
        this.circuitOpen = circuitOpen;
    }

    /**
     * Indicates if this exception was thrown because the circuit breaker is open.
     * <p>
     * When the circuit breaker is open, it means Azure AD has been unreachable
     * and requests are failing fast to prevent cascade failures.
     * </p>
     *
     * @return true if the circuit breaker is open, false otherwise
     */
    public boolean isCircuitOpen() {
        return circuitOpen;
    }
}

