package com.consilens.conncetor.base;

import lombok.extern.slf4j.Slf4j;

import java.util.Properties;

/**
 * Connection properties for MySQL-protocol dialects (StarRocks, Doris, TiDB, ...).
 *
 * <p>Checksum comparison aggregates every row hash into a single
 * {@code MD5(GROUP_CONCAT(row_checksum ORDER BY pk_key SEPARATOR '|'))}. The server default of
 * {@code group_concat_max_len} (1024 bytes) silently truncates that aggregation once a segment has
 * more than a few dozen rows, so both sides collapse to the same prefix and real differences are
 * missed. MySQL and OceanBase already raise the limit in their own pool optimizers; the remaining
 * MySQL-protocol dialects share this implementation.
 *
 * @since 1.1.0
 */
@Slf4j
public class MySQLProtocolConnectionPoolOptimizer extends BaseConnectionPoolOptimizer {

    private static final String GROUP_CONCAT_MAX_LEN = "1073741824";

    @Override
    public Properties getOptimizationProperties(boolean useSSL) {
        Properties properties = new Properties();
        properties.setProperty("sessionVariables", "group_concat_max_len=" + GROUP_CONCAT_MAX_LEN);
        log.debug("MySQL-protocol pool optimizer: group_concat_max_len={}", GROUP_CONCAT_MAX_LEN);
        return properties;
    }
}
