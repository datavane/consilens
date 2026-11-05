package com.consilens.connector.api.dataset;

import com.consilens.connector.api.DatabaseDialect;

/**
 * 数据集句柄可选能力：暴露该数据源所使用的方言。
 *
 * <p>流式/记录级比较需要与 checksum 路径相同的"方言值归一化"（如 MySQL YEAR
 * 在驱动里是 {@code java.sql.Date}，Oracle TIMESTAMP 是字符串），否则同一行
 * 在两条计划下会得到不同的规范化结果，产生计划间不一致的差异结论。
 */
public interface DialectProvider {

    DatabaseDialect getDialect();
}
