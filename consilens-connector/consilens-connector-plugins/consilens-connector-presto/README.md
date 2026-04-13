# Presto Database Connector

Presto database dialect implementation for the Consilens data comparison framework.

## Overview

This module provides Presto-specific implementations of database dialect components following the SPI (Service Provider Interface) pattern.

### Components

- **PrestoDatabaseDialect** - Main dialect entry point, composes all Presto-specific components
- **PrestoCapabilityProvider** - Defines Presto features, capabilities, and identifier quotes (double quotes)
- **PrestoSqlQueryGenerator** - Generates Presto-specific SQL (LIMIT, UNION for FULL OUTER JOIN)
- **PrestoMetadataQueryGenerator** - Queries Presto's "information_schema" for metadata
- **PrestoDataTypeHandler** - Handles Presto data type normalization and mapping

## Architecture

"""
PrestoDatabaseDialect
├── PrestoCapabilityProvider (defines features & quotes)
├── PrestoDataTypeHandler (type mapping & normalization)
├── PrestoSqlQueryGenerator (SQL generation)
│   └── uses: CapabilityProvider, DataTypeHandler
└── PrestoMetadataQueryGenerator (metadata queries)
    └── uses: CapabilityProvider
"""

## Features

### SQL Generation
- ✅ Presto-specific LIMIT syntax: "LIMIT offset, limit"
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

// Load Presto dialect via PluginManager-backed DialectFactory
DatabaseDialect presto = DialectFactory.getDialect(DatabaseType.PRESTO);

// Use the dialect
SqlQueryGenerator queryGen = presto.getSqlQueryGenerator();
"""

### Direct Instantiation

"""java
import com.consilens.connector.presto.PrestoDatabaseDialect;

// Create dialect instance
DatabaseDialect presto = new PrestoDatabaseDialect();

// Generate SQL
SqlQueryGenerator queryGen = presto.getSqlQueryGenerator();
String limitSql = queryGen.getLimitClause(10, 20);  // "LIMIT 10, 20"

// Get metadata
MetadataQueryGenerator metaGen = presto.getMetadataQueryGenerator();
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
DataTypeHandler typeHandler = presto.getDataTypeHandler();

// Normalize for checksum
String normalized = typeHandler.normalizeColumn("created_at", "TIMESTAMP");
// Returns: COALESCE(DATE_FORMAT("created_at", '%Y-%m-%d %H:%i:%s'), '')

// Map data type
String prestoType = typeHandler.getDataTypeMapping("varchar", 255, 0, 0);
// Returns: VARCHAR(255)
"""

## Dependencies

"""xml
<dependency>
    <groupId>com.consilens</groupId>
    <artifactId>consilens-connector-presto</artifactId>
    <version>0.1-SNAPSHOT</version>
</dependency>
"""

**Runtime Dependencies:**
- "consilens-connector-base" - Base implementations
- "consilens-connector-api" - API interfaces  
- "consilens-spi" - Plugin runtime used by consilens-core

**Optional:**
- Presto JDBC Driver (for actual database connections)

## Supported Presto Features

| Feature | Supported | Implementation |
|---------|-----------|----------------|
| Window Functions | ✅ | Presto 8.0+ |
| JSON Functions | ✅ | JSON data type |
| Unique Constraints | ✅ | UNIQUE keyword |
| Stored Procedures | ✅ | DELIMITER syntax |
| Transactions | ✅ | BEGIN/COMMIT/ROLLBACK |
| Savepoints | ✅ | SAVEPOINT |
| Check Constraints | ✅ | Presto 8.0.16+ |
| FULL OUTER JOIN | ⚠️ | Simulated via UNION |

## Design Decisions

### Why Backtick Quotes?
Presto uses double quotes (") for identifier quoting, different from SQL standard double quotes (").

### Why UNION for FULL OUTER JOIN?
Presto does not support native FULL OUTER JOIN. We simulate it using:
"""sql
(SELECT * FROM t1 LEFT JOIN t2 ON ...) 
UNION 
(SELECT * FROM t1 RIGHT JOIN t2 ON ...)
"""

### Checksum Boolean Handling
TINYINT(1) is checked **before** general INT types to correctly identify Presto booleans.

## Testing

Run tests:
"""bash
mvn test -pl consilens-connector-presto
"""

Test coverage:
- PrestoDatabaseDialectTest (7 tests)
- PrestoSqlQueryGeneratorTest (8 tests)
- PrestoDataTypeHandlerTest (8 tests)
- PrestoMetadataQueryGeneratorTest (12 tests)

**Total: 35+ unit tests**

## Migrating from Core Implementation

If migrating from "consilens-core" PrestoDialect:

1. Replace direct instantiation:
   """java
   // Old
   PrestoDialect dialect = new PrestoDialect();
   
   // New
   PrestoDatabaseDialect dialect = new PrestoDatabaseDialect();
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

**Next:** See other connector implementations (Presto, Presto, Presto...)
