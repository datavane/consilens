# SQLServer Database Connector

SQLServer database dialect implementation for the Consilens data comparison framework.

## Overview

This module provides SQLServer-specific implementations of database dialect components following the SPI (Service Provider Interface) pattern.

### Components

- **SQLServerDatabaseDialect** - Main dialect entry point, composes all SQLServer-specific components
- **SQLServerCapabilityProvider** - Defines SQLServer features, capabilities, and identifier quotes (double quotes)
- **SQLServerSqlQueryGenerator** - Generates SQLServer-specific SQL (LIMIT, UNION for FULL OUTER JOIN)
- **SQLServerMetadataQueryGenerator** - Queries SQLServer's "information_schema" for metadata
- **SQLServerDataTypeHandler** - Handles SQLServer data type normalization and mapping

## Architecture

"""
SQLServerDatabaseDialect
├── SQLServerCapabilityProvider (defines features & quotes)
├── SQLServerDataTypeHandler (type mapping & normalization)
├── SQLServerSqlQueryGenerator (SQL generation)
│   └── uses: CapabilityProvider, DataTypeHandler
└── SQLServerMetadataQueryGenerator (metadata queries)
    └── uses: CapabilityProvider
"""

## Features

### SQL Generation
- ✅ SQLServer-specific LIMIT syntax: "LIMIT offset, limit"
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

// Load SQLServer dialect via PluginManager-backed DialectFactory
DatabaseDialect sqlserver = DialectFactory.getDialect(DatabaseType.SQL_SERVER);

// Use the dialect
SqlQueryGenerator queryGen = sqlserver.getSqlQueryGenerator();
"""

### Direct Instantiation

"""java
import com.consilens.connector.sqlserver.SQLServerDatabaseDialect;

// Create dialect instance
DatabaseDialect sqlserver = new SQLServerDatabaseDialect();

// Generate SQL
SqlQueryGenerator queryGen = sqlserver.getSqlQueryGenerator();
String limitSql = queryGen.getLimitClause(10, 20);  // "LIMIT 10, 20"

// Get metadata
MetadataQueryGenerator metaGen = sqlserver.getMetadataQueryGenerator();
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
DataTypeHandler typeHandler = sqlserver.getDataTypeHandler();

// Normalize for checksum
String normalized = typeHandler.normalizeColumn("created_at", "TIMESTAMP");
// Returns: COALESCE(DATE_FORMAT("created_at", '%Y-%m-%d %H:%i:%s'), '')

// Map data type
String sqlserverType = typeHandler.getDataTypeMapping("varchar", 255, 0, 0);
// Returns: VARCHAR(255)
"""

## Dependencies

"""xml
<dependency>
    <groupId>com.consilens</groupId>
    <artifactId>consilens-connector-sqlserver</artifactId>
    <version>0.1-SNAPSHOT</version>
</dependency>
"""

**Runtime Dependencies:**
- "consilens-connector-base" - Base implementations
- "consilens-connector-api" - API interfaces  
- "consilens-spi" - Plugin runtime used by consilens-core

**Optional:**
- SQLServer JDBC Driver (for actual database connections)

## Supported SQLServer Features

| Feature | Supported | Implementation |
|---------|-----------|----------------|
| Window Functions | ✅ | SQLServer 8.0+ |
| JSON Functions | ✅ | JSON data type |
| Unique Constraints | ✅ | UNIQUE keyword |
| Stored Procedures | ✅ | DELIMITER syntax |
| Transactions | ✅ | BEGIN/COMMIT/ROLLBACK |
| Savepoints | ✅ | SAVEPOINT |
| Check Constraints | ✅ | SQLServer 8.0.16+ |
| FULL OUTER JOIN | ⚠️ | Simulated via UNION |

## Design Decisions

### Why Backtick Quotes?
SQLServer uses double quotes (") for identifier quoting, different from SQL standard double quotes (").

### Why UNION for FULL OUTER JOIN?
SQLServer does not support native FULL OUTER JOIN. We simulate it using:
"""sql
(SELECT * FROM t1 LEFT JOIN t2 ON ...) 
UNION 
(SELECT * FROM t1 RIGHT JOIN t2 ON ...)
"""

### Checksum Boolean Handling
TINYINT(1) is checked **before** general INT types to correctly identify SQLServer booleans.

## Testing

Run tests:
"""bash
mvn test -pl consilens-connector-sqlserver
"""

Test coverage:
- SQLServerDatabaseDialectTest (7 tests)
- SQLServerSqlQueryGeneratorTest (8 tests)
- SQLServerDataTypeHandlerTest (8 tests)
- SQLServerMetadataQueryGeneratorTest (12 tests)

**Total: 35+ unit tests**

## Migrating from Core Implementation

If migrating from "consilens-core" SQLServerDialect:

1. Replace direct instantiation:
   """java
   // Old
   SQLServerDialect dialect = new SQLServerDialect();
   
   // New
   SQLServerDatabaseDialect dialect = new SQLServerDatabaseDialect();
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

**Next:** See other connector implementations (SQLServer, Oracle, SQL Server...)
