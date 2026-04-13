package com.consilens.core.util;

import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;

/**
 * Unified resource management utility.
 * Provides safe resource closing and management to avoid leaks.
 */
@Slf4j
public final class ResourceManager {

    private ResourceManager() {
        // Utility class, prevent instantiation
    }

    /**
     * Quietly close multiple AutoCloseable resources.
     * @param resources resources to close
     */
    public static void closeQuietly(AutoCloseable... resources) {
        if (resources == null) {
            return;
        }

        for (AutoCloseable resource : resources) {
            closeQuietly(resource);
        }
    }

    /**
     * Quietly close a single AutoCloseable resource.
     * @param resource resource to close
     */
    public static void closeQuietly(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception e) {
                log.debug("Error closing resource: {}", resource.getClass().getSimpleName(), e);
            }
        }
    }

    /**
     * Quietly close JDBC connection, statement, and result set.
     * @param connection database connection
     * @param statement SQL statement
     * @param resultSet result set
     */
    public static void closeJdbcResources(Connection connection, Statement statement, ResultSet resultSet) {
        closeQuietly(resultSet, statement, connection);
    }

    /**
     * Quietly close JDBC statement and result set.
     * @param statement SQL statement
     * @param resultSet result set
     */
    public static void closeJdbcResources(Statement statement, ResultSet resultSet) {
        closeQuietly(resultSet, statement);
    }

    /**
     * Quietly close a database connection.
     * @param connection database connection
     */
    public static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                }
            } catch (Exception e) {
                log.warn("Error closing database connection", e);
            }
        }
    }

    /**
     * Quietly close a Stream.
     * @param stream stream to close
     * @param <T> stream element type
     */
    public static <T extends AutoCloseable> void closeQuietly(Stream<T> stream) {
        if (stream != null) {
            try {
                if (stream instanceof AutoCloseable) {
                    ((AutoCloseable) stream).close();
                }
            } catch (Exception e) {
                log.debug("Error closing stream", e);
            }
        }
    }

    /**
     * Execute an operation and ensure the resource is closed.
     * @param resource resource to use
     * @param operation operation to execute
     * @param <T> resource type
     * @param <R> return type
     * @return operation result
     * @throws Exception if the operation throws
     */
    public static <T extends AutoCloseable, R> R withResource(T resource, ResourceOperation<T, R> operation) throws Exception {
        try {
            return operation.execute(resource);
        } finally {
            closeQuietly(resource);
        }
    }

    /**
     * Execute a void operation and ensure the resource is closed.
     * @param resource resource to use
     * @param operation operation to execute
     * @param <T> resource type
     * @throws Exception if the operation throws
     */
    public static <T extends AutoCloseable> void withResource(T resource, ResourceConsumer<T> operation) throws Exception {
        try {
            operation.accept(resource);
        } finally {
            closeQuietly(resource);
        }
    }

    /**
     * Functional interface for resource operations that return a value.
     * @param <T> resource type
     * @param <R> return type
     */
    @FunctionalInterface
    public interface ResourceOperation<T, R> {
        R execute(T resource) throws Exception;
    }

    /**
     * Functional interface for void resource operations.
     * @param <T> resource type
     */
    @FunctionalInterface
    public interface ResourceConsumer<T> {
        void accept(T resource) throws Exception;
    }
}