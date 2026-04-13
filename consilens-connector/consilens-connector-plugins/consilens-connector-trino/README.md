# Trino Database Connector

Trino database dialect implementation for the Consilens data comparison framework.

## Overview

This module provides Trino-specific implementations of database dialect components following the SPI (Service Provider Interface) pattern.

### Components

- **TrinoDatabaseDialect** - Main dialect entry point, composes all Trino-specific components
- **TrinoCapabilityProvider** - Defines Trino features, capabilities, and identifier quotes (double quotes)
- **TrinoSqlQueryGenerator** - Generates Trino-specific SQL (LIMIT, UNION for FULL OUTER JOIN)
- **TrinoMetadataQueryGenerator** - Queries Trino's "information_schema" for metadata
- **TrinoDataTypeHandler** - Handles Trino data type normalization and mapping

## Architecture

"""
TrinoDatabaseDialect
├── TrinoCapabilityProvider (defines features & quotes)
├── TrinoDataTypeHandler (type mapping & normalization)
├── TrinoSqlQueryGenerator (SQL generation)
│   └── uses: CapabilityProvider, DataTypeHandler
└── TrinoMetadataQueryGenerator (metadata queries)
    └── uses: CapabilityProvider
"""

## Features

### SQL Generation
- ✅ Trino-specific LIMIT syntax: "LIMIT offset, limit"
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

// Load Trino dialect via PluginManager-backed DialectFactory
DatabaseDialect trino = DialectFactory.getDialect(DatabaseType.TRINO);

// Use the dialect
SqlQueryGenerator queryGen = trino.getSqlQueryGenerator();
"""

### Direct Instantiation

"""java
import com.consilens.connector.trino.TrinoDatabaseDialect;

// Create dialect instance
DatabaseDialect trino = new TrinoDatabaseDialect();

// Generate SQL
SqlQueryGenerator queryGen = trino.getSqlQueryGenerator();
String limitSql = queryGen.getLimitClause(10, 20);  // "LIMIT 10, 20"

// Get metadata
MetadataQueryGenerator metaGen = trino.getMetadataQueryGenerator();
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
DataTypeHandler typeHandler = trino.getDataTypeHandler();

// Normalize for checksum
String normalized = typeHandler.normalizeColumn("created_at", "TIMESTAMP");
// Returns: COALESCE(DATE_FORMAT("created_at", '%Y-%m-%d %H:%i:%s'), '')

// Map data type
String trinoType = typeHandler.getDataTypeMapping("varchar", 255, 0, 0);
// Returns: VARCHAR(255)
"""

## Dependencies

"""xml
<dependency>
    <groupId>com.consilens</groupId>
    <artifactId>consilens-connector-trino</artifactId>
    <version>0.1-SNAPSHOT</version>
</dependency>
"""

**Runtime Dependencies:**
- "consilens-connector-base" - Base implementations
- "consilens-connector-api" - API interfaces  
- "consilens-spi" - Plugin runtime used by consilens-core

**Optional:**
- Trino JDBC Driver (for actual database connections)

## Supported Trino Features

| Feature | Supported | Implementation |
|---------|-----------|----------------|
| Window Functions | ✅ | Trino 8.0+ |
| JSON Functions | ✅ | JSON data type |
| Unique Constraints | ✅ | UNIQUE keyword |
| Stored Procedures | ✅ | DELIMITER syntax |
| Transactions | ✅ | BEGIN/COMMIT/ROLLBACK |
| Savepoints | ✅ | SAVEPOINT |
| Check Constraints | ✅ | Trino 8.0.16+ |
| FULL OUTER JOIN | ⚠️ | Simulated via UNION |

## Design Decisions

### Why Backtick Quotes?
Trino uses double quotes (") for identifier quoting, different from SQL standard double quotes (").

### Why UNION for FULL OUTER JOIN?
Trino does not support native FULL OUTER JOIN. We simulate it using:
"""sql
(SELECT * FROM t1 LEFT JOIN t2 ON ...) 
UNION 
(SELECT * FROM t1 RIGHT JOIN t2 ON ...)
"""

### Checksum Boolean Handling
TINYINT(1) is checked **before** general INT types to correctly identify Trino booleans.

## Testing

Run tests:
"""bash
mvn test -pl consilens-connector-trino
"""

Test coverage:
- TrinoDatabaseDialectTest (7 tests)
- TrinoSqlQueryGeneratorTest (8 tests)
- TrinoDataTypeHandlerTest (8 tests)
- TrinoMetadataQueryGeneratorTest (12 tests)

**Total: 35+ unit tests**

## Migrating from Core Implementation

If migrating from "consilens-core" TrinoDialect:

1. Replace direct instantiation:
   """java
   // Old
   TrinoDialect dialect = new TrinoDialect();
   
   // New
   TrinoDatabaseDialect dialect = new TrinoDatabaseDialect();
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

**Next:** See other connector implementations (Trino, Trino, Trino...)
