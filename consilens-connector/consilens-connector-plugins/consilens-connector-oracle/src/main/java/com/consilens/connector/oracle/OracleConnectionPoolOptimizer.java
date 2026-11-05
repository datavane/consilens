package com.consilens.connector.oracle;

import com.consilens.connector.api.ConnectionPoolOptimizer;
import com.consilens.connector.api.model.PoolConfiguration;
import lombok.extern.slf4j.Slf4j;

import java.util.Properties;

/**
 * Oracle-specific connection pool optimizer.
 * 
 * <p>
 * Provides Oracle-specific optimization properties and default configuration including:
 * <ul>
 * <li>Row prefetch optimization</li>
 * <li>NChar handling</li>
 * <li>AutoCommit behavior tuning</li>
 * </ul>
 * 
 * @since 1.1.0
 */
@Slf4j
public class OracleConnectionPoolOptimizer implements ConnectionPoolOptimizer {

    /**
     * 固定 NLS 时间格式：TO_CHAR(datetime) 的 ORDER BY 必须与 Java 侧归一化后的
     * ISO 文本同序，否则跨引擎 streaming merge 会错位（默认 DD-MON-RR 按月份名排序）。
     */
    public static final String DEFAULT_CONNECTION_INIT_SQL =
            "ALTER SESSION SET NLS_DATE_FORMAT='YYYY-MM-DD HH24:MI:SS' "
                    + "NLS_TIMESTAMP_FORMAT='YYYY-MM-DD HH24:MI:SS.FF9' "
                    + "NLS_TIMESTAMP_TZ_FORMAT='YYYY-MM-DD HH24:MI:SS.FF9 TZH:TZM'";

    @Override
    public Properties getOptimizationProperties(boolean useSSL) {
        log.debug("Generating Oracle-specific connection pool optimization properties");

        Properties props = new Properties();

        // Row prefetch optimization
        props.setProperty("oracle.jdbc.defaultRowPrefetch", "1000");

        // NChar handling
        props.setProperty("oracle.jdbc.defaultNChar", "true");
        props.setProperty("oracle.jdbc.ConvertNcharLiterals", "true");

        // AutoCommit optimization
        props.setProperty("oracle.jdbc.autoCommitSpecCompliant", "false");

        return props;
    }

    @Override
    public PoolConfiguration getDefaultConfiguration() {
        log.debug("Generating Oracle default connection pool configuration");
        
        PoolConfiguration config = new PoolConfiguration();
        // The session timezone must be configured per datasource (e.g. via an explicit
        // connectionInitSql override on PoolConfiguration): Oracle SQL normalization
        // interprets the stored wall clock in the *session* timezone, so it has to match
        // the source data's zone -- an environment fact that must not be hardcoded here.
        config.setMaxPoolSize(15);
        config.setMinIdle(3);
        config.setMaxLifetime(3600000); // 60 minutes
        config.setIdleTimeout(1800000); // 30 minutes
        config.setConnectionTimeout(60000); // 60 seconds
        config.setLeakDetectionThreshold(120000); // 2 minutes
        config.setValidationQuery("SELECT 1 FROM DUAL");
        config.setConnectionInitSql(DEFAULT_CONNECTION_INIT_SQL);
        
        return config;
    }
}
