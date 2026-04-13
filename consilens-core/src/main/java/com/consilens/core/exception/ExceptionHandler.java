package com.consilens.core.exception;

import com.consilens.core.util.LogUtils;
import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Utility class for unified exception handling.
 * Provides standard patterns for catching, converting, and handling exceptions.
 */
@Slf4j
public final class ExceptionHandler {

    private ExceptionHandler() {
        // Utility class, do not instantiate.
    }

    /**
     * Execute an operation and handle any exception via the provided handler.
     * @param operation the operation to execute
     * @param exceptionHandler exception handler (null to log only)
     * @param <T> return type
     * @return operation result, or null on exception
     */
    public static <T> T handle(Supplier<T> operation, Consumer<Exception> exceptionHandler) {
        try {
            return operation.get();
        } catch (Exception e) {
            if (exceptionHandler != null) {
                exceptionHandler.accept(e);
            } else {
                logException("Operation failed", e);
            }
            return null;
        }
    }

    /**
     * Execute an operation and return a default value on exception.
     * @param operation the operation to execute
     * @param defaultValue value to return on exception
     * @param <T> return type
     * @return operation result, or defaultValue on exception
     */
    public static <T> T handleWithDefault(Supplier<T> operation, T defaultValue) {
        try {
            return operation.get();
        } catch (Exception e) {
            logException("Operation failed, using default value", e);
            return defaultValue;
        }
    }

    /**
     * Execute an operation that may throw checked exceptions.
     * @param operation the operation to execute
     * @param operationDescription description for error messages
     * @throws RuntimeException wrapping the original exception
     */
    public static void run(ThrowingRunnable operation, String operationDescription) {
        try {
            operation.run();
        } catch (Exception e) {
            throw wrapException(operationDescription, e);
        }
    }

    /**
     * Execute an operation that may throw checked exceptions and return a result.
     * @param operation the operation to execute
     * @param operationDescription description for error messages
     * @param <T> return type
     * @return operation result
     * @throws RuntimeException wrapping the original exception
     */
    public static <T> T execute(ThrowingSupplier<T> operation, String operationDescription) {
        try {
            return operation.get();
        } catch (Exception e) {
            throw wrapException(operationDescription, e);
        }
    }

    /**
     * Execute a database operation safely, logging SQL state and error code on failure.
     * @param operation the database operation
     * @param operationDescription description for error messages
     * @param <T> return type
     * @return operation result
     */
    public static <T> T executeDatabase(ThrowingSupplier<T> operation, String operationDescription) {
        try {
            return operation.get();
        } catch (SQLException e) {
            logDatabaseException(operationDescription, e);
            throw wrapException("Database operation failed: " + operationDescription, e);
        } catch (Exception e) {
            logException("Unexpected error during database operation: " + operationDescription, e);
            throw wrapException("Unexpected error during database operation: " + operationDescription, e);
        }
    }

    /**
     * Handle exceptions from an async operation.
     * @param futureSupplier future supplier
     * @param operationDescription description for error messages
     * @param <T> return type
     * @return operation result
     */
    public static <T> T handleAsync(Supplier<T> futureSupplier, String operationDescription) {
        try {
            return futureSupplier.get();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            throw wrapException(operationDescription + " (async)", cause != null ? cause : e);
        } catch (RuntimeException e) {
            // Handle ExecutionException, which may be a subclass of RuntimeException
            if (e.getCause() instanceof ExecutionException) {
                Throwable cause = e.getCause().getCause();
                throw wrapException(operationDescription + " (async)", cause != null ? cause : e);
            }
            throw wrapException(operationDescription + " (async)", e);
        } catch (Exception e) {
            throw wrapException(operationDescription + " (async)", e);
        }
    }

    /**
     * Retry an operation up to maxRetries times.
     * @param operation the operation to execute
     * @param maxRetries maximum number of retries
     * @param operationDescription description for error messages
     * @param <T> return type
     * @return operation result
     */
    public static <T> T retry(ThrowingSupplier<T> operation, int maxRetries, String operationDescription) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            try {
                if (attempt > 1) {
                    LogUtils.logDebug("Retrying operation", "operation=" + operationDescription, "attempt=" + attempt);
                }
                return operation.get();
            } catch (Exception e) {
                lastException = e;
                if (attempt <= maxRetries) {
                    LogUtils.logWarning("Operation failed, will retry",
                            "operation=" + operationDescription,
                            "attempt=" + attempt,
                            "error=" + e.getMessage());

                    // Wait before retry with linear back-off
                    try {
                        Thread.sleep(1000 * attempt); // linear back-off
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw wrapException("Retry interrupted for " + operationDescription, ie);
                    }
                } else {
                    logException("Operation failed after " + maxRetries + " retries: " + operationDescription, e);
                }
            }
        }

        throw wrapException("Operation failed after " + maxRetries + " retries: " + operationDescription, lastException);
    }

    /**
     * Wrap a throwable as a RuntimeException.
     * @param message error message
     * @param cause original throwable
     * @return wrapped RuntimeException
     */
    private static RuntimeException wrapException(String message, Throwable cause) {
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        return new RuntimeException(message, cause);
    }

    /**
     * Log an exception.
     * @param message log message
     * @param e exception
     */
    private static void logException(String message, Exception e) {
        if (e instanceof SQLException) {
            logDatabaseException(message, (SQLException) e);
        } else {
            LogUtils.logOperationFailure("Exception: " + message, e);
        }
    }

    /**
     * Log a database exception with SQL state and error code.
     * @param message log message
     * @param e SQL exception
     */
    private static void logDatabaseException(String message, SQLException e) {
        LogUtils.logOperationFailure("Database Exception: " + message +
                " (SQL State: " + e.getSQLState() + ", Error Code: " + e.getErrorCode() + ")", e);
    }

    /**
     * Functional interface for an operation that may throw checked exceptions.
     */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Functional interface for a supplier that may throw checked exceptions.
     */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /**
     * Functional interface for a consumer that may throw checked exceptions.
     */
    @FunctionalInterface
    public interface ThrowingConsumer<T> {
        void accept(T t) throws Exception;
    }

    /**
     * Functional interface for a function that may throw checked exceptions.
     */
    @FunctionalInterface
    public interface ThrowingFunction<T, R> {
        R apply(T t) throws Exception;
    }

    /**
     * Common exception handlers.
     */
    public static class CommonHandlers {

        /**
         * Log the exception and continue.
         */
        public static final Consumer<Exception> LOG_AND_CONTINUE = e -> {
            logException("Exception occurred but continuing", e);
        };

        /**
         * Log the exception and rethrow.
         */
        public static final Consumer<Exception> LOG_AND_RETHROW = e -> {
            logException("Exception occurred", e);
            throw wrapException("Rethrown exception", e);
        };

        /**
         * Ignore the exception silently.
         */
        public static final Consumer<Exception> IGNORE = e -> {
            // no-op
        };

        /**
         * Log a warning and continue.
         */
        public static final Consumer<Exception> LOG_WARNING_AND_CONTINUE = e -> {
            LogUtils.logWarning("Exception occurred but continuing: " + e.getMessage());
        };
    }

    /**
     * Builder for configuring exception handling behavior.
     */
    public static class HandlerBuilder {
        private String operationDescription = "Operation";
        private int maxRetries = 0;
        private Consumer<Exception> exceptionHandler = CommonHandlers.LOG_AND_CONTINUE;
        private boolean wrapExceptions = true;

        public static HandlerBuilder forOperation(String description) {
            HandlerBuilder builder = new HandlerBuilder();
            builder.operationDescription = description;
            return builder;
        }

        public HandlerBuilder withRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public HandlerBuilder withExceptionHandler(Consumer<Exception> handler) {
            this.exceptionHandler = handler;
            return this;
        }

        public HandlerBuilder wrapExceptions(boolean wrap) {
            this.wrapExceptions = wrap;
            return this;
        }

        public <T> T execute(ThrowingSupplier<T> operation) {
            if (maxRetries > 0) {
                return retry(operation, maxRetries, operationDescription);
            } else {
                try {
                    return operation.get();
                } catch (Exception e) {
                    exceptionHandler.accept(e);
                    if (wrapExceptions) {
                        throw wrapException(operationDescription, e);
                    }
                    return null;
                }
            }
        }

        public void execute(ThrowingRunnable operation) {
            execute(() -> {
                operation.run();
                return null;
            });
        }
    }
}
