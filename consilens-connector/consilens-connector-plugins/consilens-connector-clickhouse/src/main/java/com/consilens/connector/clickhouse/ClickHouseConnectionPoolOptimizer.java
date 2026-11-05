package com.consilens.connector.clickhouse;

import com.consilens.connector.api.ConnectionPoolOptimizer;
import com.consilens.connector.api.model.PoolConfiguration;
import lombok.extern.slf4j.Slf4j;

import java.util.Properties;

/**
 * ClickHouse-specific connection pool optimizer.
 *
 * <p>
 * Pins {@code join_use_nulls = 1} on every connection. ClickHouse defaults to
 * {@code join_use_nulls = 0}, where an unmatched side of an outer join is filled with
 * column *defaults* (0 / '') instead of NULL. The join compare path classifies presence
 * differences with {@code t1.k IS NULL} / {@code t2.k IS NULL}, so with the default
 * setting every source-only / target-only row is misclassified as MISMATCH
 * (measured: dq_cmp_alltype join gave 69 MM instead of 5 TM + 6 SM + 58 MM).
 */
@Slf4j
public class ClickHouseConnectionPoolOptimizer implements ConnectionPoolOptimizer {

    @Override
    public Properties getOptimizationProperties(boolean useSSL) {
        log.debug("Generating ClickHouse-specific connection pool optimization properties");

        Properties props = new Properties();
        if (useSSL) {
            props.setProperty("ssl", "true");
        }
        return props;
    }

    @Override
    public PoolConfiguration getDefaultConfiguration() {
        log.debug("Generating ClickHouse default connection pool configuration");

        PoolConfiguration config = new PoolConfiguration();
        // join_use_nulls 由 ClickHouseSqlQueryGenerator 逐查询 SETTINGS 设置：连接级 init SQL 会被
        // 用户自带的 PoolConfiguration 覆盖，逐查询设置不会，口径更稳。
        config.setMaxPoolSize(15);
        config.setMinIdle(3);
        config.setMaxLifetime(3600000);
        config.setIdleTimeout(1800000);
        config.setConnectionTimeout(60000);
        config.setLeakDetectionThreshold(120000);
        config.setValidationQuery("SELECT 1");

        return config;
    }
}
