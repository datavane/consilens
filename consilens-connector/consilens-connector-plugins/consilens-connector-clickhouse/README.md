# ClickHouse Database Connector

ClickHouse database dialect implementation for the Consilens data comparison framework.

## Overview

This module provides ClickHouse-specific implementations of database dialect components following the SPI (Service Provider Interface) pattern.

### Components

- **ClickHouseDatabaseDialect** - Main dialect entry point, composes all ClickHouse-specific components
- **ClickHouseCapabilityProvider** - Defines ClickHouse features, capabilities, and identifier quotes (double quotes)
- **ClickHouseSqlQueryGenerator** - Generates ClickHouse-specific SQL (LIMIT, UNION for FULL OUTER JOIN)
- **ClickHouseMetadataQueryGenerator** - Queries ClickHouse's "information_schema" for metadata
- **ClickHouseDataTypeHandler** - Handles ClickHouse data type normalization and mapping

## Architecture

"""
ClickHouseDatabaseDialect
├── ClickHouseCapabilityProvider (defines features & quotes)
├── ClickHouseDataTypeHandler (type mapping & normalization)
├── ClickHouseSqlQueryGenerator (SQL generation)
│   └── uses: CapabilityProvider, DataTypeHandler
└── ClickHouseMetadataQueryGenerator (metadata queries)
    └── uses: CapabilityProvider
"""

## Features

### SQL Generation
- ✅ ClickHouse-specific LIMIT syntax: "LIMIT offset, limit"
- ✅ Checksum calculation using MD5 and GROUP_CONCAT
- ✅ FULL OUTER JOIN simulation (UNION of LEFT and RIGHT joins)
- ✅ Multi-row batch INSERT with VALUES

### Data Type Handling
- ✅ Comprehensive type normalization for checksum calculation
- ✅ Special handling for BOOLEAN (TINYINT(1))
- ✅ Date/Time formatting with DATE_FORMAT
- ✅ JSON type support

### Metadata Queries
- ✅ Information_schema based queries
- ✅ SQL injection protection (escapeString method)
- ✅ Table/Column/Index metadata retrieval
- ✅ Primary/Foreign key discovery

## Usage

### Via DialectFactory (Recommended)

"""java
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.core.database.dialect.DialectFactory;

// Load ClickHouse dialect via PluginManager-backed DialectFactory
DatabaseDialect clickhouse = DialectFactory.getDialect(DatabaseType.CLICKHOUSE);

// Use the dialect
SqlQueryGenerator queryGen = clickhouse.getSqlQueryGenerator();
"""

### Direct Instantiation

"""java
import com.consilens.connector.clickhouse.ClickHouseDatabaseDialect;

// Create dialect instance
DatabaseDialect clickhouse = new ClickHouseDatabaseDialect();

// Generate SQL
SqlQueryGenerator queryGen = clickhouse.getSqlQueryGenerator();
String limitSql = queryGen.getLimitClause(10, 20);  // "LIMIT 10, 20"

// Get metadata
MetadataQueryGenerator metaGen = clickhouse.getMetadataQueryGenerator();
String tableExistsSql = metaGen.getTableExistsSQL("mydb", "users");
"""

### Code Examples

#### Checksum Calculation
"""java
List<String> columns = Arrays.asList("id", "name", "email");
String checksumSql = queryGen.getChecksumSQL(
    "mydb", "users", columns, "status = 'active'"
);
// Generates MD5-based checksum query
"""

#### Data Type Normalization
"""java
DataTypeHandler typeHandler = clickhouse.getDataTypeHandler();

// Normalize for checksum
String normalized = typeHandler.normalizeColumn("created_at", "TIMESTAMP");
// Returns: COALESCE(DATE_FORMAT("created_at", '%Y-%m-%d %H:%i:%s'), '')

// Map data type
String clickhouseType = typeHandler.getDataTypeMapping("varchar", 255, 0, 0);
// Returns: VARCHAR(255)
"""

## Dependencies

"""xml
<dependency>
    <groupId>com.consilens</groupId>
    <artifactId>consilens-connector-clickhouse</artifactId>
    <version>0.1-SNAPSHOT</version>
</dependency>
"""

**Runtime Dependencies:**
- "consilens-connector-base" - Base implementations
- "consilens-connector-api" - API interfaces  
- "consilens-spi" - Plugin runtime used by consilens-core

**Optional:**
- ClickHouse JDBC Driver (for actual database connections)

## Supported ClickHouse Features

| Feature | Supported | Implementation |
|---------|-----------|----------------|
| Window Functions | ✅ | ClickHouse 8.0+ |
| JSON Functions | ✅ | JSON data type |
| Unique Constraints | ✅ | UNIQUE keyword |
| Stored Procedures | ✅ | DELIMITER syntax |
| Transactions | ✅ | BEGIN/COMMIT/ROLLBACK |
| Savepoints | ✅ | SAVEPOINT |
| Check Constraints | ✅ | ClickHouse 8.0.16+ |
| FULL OUTER JOIN | ⚠️ | Simulated via UNION |

## Design Decisions

### Why Backtick Quotes?
ClickHouse uses double quotes (") for identifier quoting, different from SQL standard double quotes (").

### Why UNION for FULL OUTER JOIN?
ClickHouse does not support native FULL OUTER JOIN. We simulate it using:
"""sql
(SELECT * FROM t1 LEFT JOIN t2 ON ...) 
UNION 
(SELECT * FROM t1 RIGHT JOIN t2 ON ...)
"""

### Checksum Boolean Handling
TINYINT(1) is checked **before** general INT types to correctly identify ClickHouse booleans.

## Testing

Run tests:
"""bash
mvn test -pl consilens-connector-clickhouse
"""

Test coverage:
- ClickHouseDatabaseDialectTest (7 tests)
- ClickHouseSqlQueryGeneratorTest (8 tests)
- ClickHouseDataTypeHandlerTest (8 tests)
- ClickHouseMetadataQueryGeneratorTest (12 tests)

**Total: 35+ unit tests**

## Migrating from Core Implementation

If migrating from "consilens-core" ClickHouseDialect:

1. Replace direct instantiation:
   """java
   // Old
   ClickHouseDialect dialect = new ClickHouseDialect();
   
   // New
   ClickHouseDatabaseDialect dialect = new ClickHouseDatabaseDialect();
   """

2. Use component getters:
   """java
   // Access components
   dialect.getSqlQueryGenerator();
   dialect.getMetadataQueryGenerator();
   dialect.getDataTypeHandler();
   """

## License

Copyright © 2025 Consilens

---

**Next:** See other connector implementations (ClickHouse, Oracle, SQL Server...)
