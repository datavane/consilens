package com.consilens.core.database.connection;

import com.consilens.connector.api.model.PoolConfiguration;
import lombok.Data;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/**
 * Unified database connection pool interface.
 * Integrates the best features from all modules.
 */
public interface ConnectionPool {

    /**
     * Get a connection from the pool.
     */
    Connection getConnection() throws SQLException;

    /**
     * Release a connection back to the pool.
     */
    void releaseConnection(Connection connection);

    /**
     * Close the connection pool.
     */
    void close();

    /**
     * Get the number of active connections.
     */
    int getActiveConnections();

    /**
     * Get the number of idle connections.
     */
    int getIdleConnections();

    /**
     * Get the maximum pool size.
     */
    int getMaxPoolSize();

    /**
     * Get the minimum idle connections.
     */
    int getMinIdleConnections();

    /**
     * Get comprehensive pool statistics.
     */
    PoolStatistics getStatistics();

    /**
     * Check if the pool is closed.
     */
    boolean isClosed();

    /**
     * Get the connector type identifier this pool is configured for (e.g. "mysql").
     */
    String getConnectorType();

    /**
     * Get pool configuration.
     */
    PoolConfiguration getConfiguration();

    /**
     * Execute a health check on the pool.
     */
    boolean isHealthy();

    /**
     * Get pool metrics for monitoring.
     */
    Map<String, Object> getMetrics();

    /**
     * Connection pool statistics.
     */
    @Data
    class PoolStatistics {
        private final int totalConnections;
        private final int activeConnections;
        private final int idleConnections;
        private final int waitingThreads;
        private final int maxPoolSize;
        private final int minIdle;
        private final long totalCreated;
        private final long totalClosed;
        private final long totalLeaked;
        private final double averageWaitTime;
        private final long maxWaitTime;
        private final long minWaitTime;

        public PoolStatistics(int totalConnections, int activeConnections, int idleConnections,
                int waitingThreads, int maxPoolSize, int minIdle,
                long totalCreated, long totalClosed, long totalLeaked,
                double averageWaitTime, long maxWaitTime, long minWaitTime) {
            this.totalConnections = totalConnections;
            this.activeConnections = activeConnections;
            this.idleConnections = idleConnections;
            this.waitingThreads = waitingThreads;
            this.maxPoolSize = maxPoolSize;
            this.minIdle = minIdle;
            this.totalCreated = totalCreated;
            this.totalClosed = totalClosed;
            this.totalLeaked = totalLeaked;
            this.averageWaitTime = averageWaitTime;
            this.maxWaitTime = maxWaitTime;
            this.minWaitTime = minWaitTime;
        }

        /**
         * Calculate the pool utilization percentage.
         */
        public double getUtilizationPercentage() {
            return maxPoolSize > 0 ? (double) activeConnections / maxPoolSize * 100 : 0;
        }

        /**
         * Check if the pool is under pressure.
         */
        public boolean isUnderPressure() {
            return waitingThreads > 0 || getUtilizationPercentage() > 80;
        }
    }
}
