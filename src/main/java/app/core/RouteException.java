package app.core;

/**
 * Runtime exception carrying a normalized route error category.
 */
public class RouteException extends RuntimeException {
    /**
     * Normalized error category for HTTP mapping.
     */
    private final RouteError error;

    /**
     * Creates an exception using the default message from the error category.
     *
     * @param error route error category
     */
    public RouteException(RouteError error) {
        super(error.message());
        this.error = error;
    }

    /**
     * Creates an exception with a custom detail message.
     *
     * @param error route error category
     * @param message detail message
     */
    public RouteException(RouteError error, String message) {
        super(message);
        this.error = error;
    }

    /**
     * Creates an exception with a custom message and cause.
     *
     * @param error route error category
     * @param message detail message
     * @param cause underlying cause
     */
    public RouteException(RouteError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    /**
     * Returns the normalized route error category.
     *
     * @return route error category
     */
    public RouteError error() {
        return error;
    }
}
