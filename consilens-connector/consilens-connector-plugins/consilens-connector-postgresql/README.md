# PostgreSQL Database Connector

PostgreSQL database dialect implementation for the Consilens data comparison framework.

## Overview

This module provides PostgreSQL-specific implementations of database dialect components following the SPI (Service Provider Interface) pattern.

### Components

- **PostgreSQLDatabaseDialect** - Main dialect entry point, composes all PostgreSQL-specific components
- **PostgreSQLCapabilityProvider** - Defines PostgreSQL features, capabilities, and identifier quotes (double quotes `"`)
- **PostgreSQLSqlQueryGenerator** - Generates PostgreSQL-specific SQL (native FULL OUTER JOIN, `LIMIT n OFFSET m`)
- **PostgreSQLMetadataQueryGenerator** - Queries PostgreSQL's `information_schema` for metadata
- **PostgreSQLDataTypeHandler** - Handles PostgreSQL data type normalization and mapping

## Architecture

```
PostgreSQLDatabaseDialect
├── PostgreSQLCapabilityProvider (defines features & quotes)
├── PostgreSQLDataTypeHandler (type mapping & normalization)
├── PostgreSQLSqlQueryGenerator (SQL generation)
│   └── uses: CapabilityProvider, DataTypeHandler
└── PostgreSQLMetadataQueryGenerator (metadata queries)
    └── uses: CapabilityProvider
```

## Features

### SQL Generation
- ✅ PostgreSQL `LIMIT n OFFSET m` syntax
- ✅ Checksum calculation using MD5 and `STRING_AGG`
- ✅ Native `FULL OUTER JOIN` support
- ✅ Multi-row batch INSERT with VALUES

### Data Type Handling
- ✅ Comprehensive type normalization for checksum calculation
- ✅ `BOOLEAN` type support
- ✅ Date/Time formatting with `TO_CHAR`
- ✅ `JSONB`/`JSON` type support

### Metadata Queries
- ✅ `information_schema` based queries
- ✅ SQL injection protection (escapeString method)
- ✅ Table/Column/Index metadata retrieval
- ✅ Primary/Foreign key discovery

## Usage

### Via DialectFactory (Recommended)

```java
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.core.database.dialect.DialectFactory;

// Load PostgreSQL dialect via PluginManager-backed DialectFactory
DatabaseDialect postgresql = DialectFactory.getDialect(DatabaseType.POSTGRESQL);

// Use the dialect
SqlQueryGenerator queryGen = postgresql.getSqlQueryGenerator();
```

### Direct Instantiation

```java
import com.consilens.connector.postgresql.PostgreSQLDatabaseDialect;

// Create dialect instance
DatabaseDialect postgresql = new PostgreSQLDatabaseDialect();

// Generate SQL
SqlQueryGenerator queryGen = postgresql.getSqlQueryGenerator();
String limitSql = queryGen.getLimitClause(10, 20);  // "LIMIT 20 OFFSET 10"

// Get metadata
MetadataQueryGenerator metaGen = postgresql.getMetadataQueryGenerator();
String tableExistsSql = metaGen.getTableExistsSQL("mydb", "users");
```

### Code Examples

#### Checksum Calculation
```java
List<String> columns = Arrays.asList("id", "name", "email");
String checksumSql = queryGen.getChecksumSQL(
    "mydb", "users", columns, "status = 'active'"
);
// Generates MD5-based checksum query using TO_CHAR for type normalization
```

#### Data Type Normalization
```java
DataTypeHandler typeHandler = postgresql.getDataTypeHandler();

// Normalize timestamp for checksum
String normalized = typeHandler.normalizeColumn("created_at", "TIMESTAMP");
// Returns: COALESCE(TO_CHAR("created_at", 'YYYY-MM-DD HH24:MI:SS'), '')

// Map data type
String postgresqlType = typeHandler.getDataTypeMapping("varchar", 255, 0, 0);
// Returns: VARCHAR(255)
```

## Dependencies

```xml
<dependency>
    <groupId>com.consilens</groupId>
    <artifactId>consilens-connector-postgresql</artifactId>
    <version>0.1-SNAPSHOT</version>
</dependency>
```

**Runtime Dependencies:**
- `consilens-connector-base` - Base implementations
- `consilens-connector-api` - API interfaces
- `consilens-spi` - Plugin runtime used by `consilens-core`

**Optional:**
- PostgreSQL JDBC Driver (for actual database connections)

## Supported PostgreSQL Features

| Feature | Supported | Notes |
|---------|-----------|-------|
| Window Functions | ✅ | PostgreSQL 8.4+ |
| JSON/JSONB Functions | ✅ | JSON and JSONB types |
| Unique Constraints | ✅ | UNIQUE keyword |
| Stored Procedures | ✅ | PL/pgSQL (no DELIMITER syntax) |
| Transactions | ✅ | BEGIN/COMMIT/ROLLBACK |
| Savepoints | ✅ | SAVEPOINT |
| Check Constraints | ✅ | Standard SQL |
| FULL OUTER JOIN | ✅ | Native support |

## Design Decisions

### Why Double-Quote Identifiers?
PostgreSQL uses double quotes (`"`) to quote identifiers, which is the SQL standard. This preserves case sensitivity for column and table names.

### Native FULL OUTER JOIN
PostgreSQL natively supports `FULL OUTER JOIN`, so no UNION simulation is needed. The connector uses it directly for the `join` comparison strategy.

### Checksum Boolean Handling
PostgreSQL uses the native `BOOLEAN` type (values `true`/`false`). The data type handler maps it to `DataType.BOOLEAN` and normalizes it to a consistent string form for cross-database checksum comparison.

### Date/Time Normalization
All date/time values are normalized using `TO_CHAR` (PostgreSQL's formatting function) to produce consistent strings before checksum calculation, enabling accurate comparison with other databases that use different formatting.

## Testing

Run tests:
```bash
mvn test -pl consilens-connector-postgresql
```

Test coverage:
- PostgreSQLDatabaseDialectTest (7 tests)
- PostgreSQLSqlQueryGeneratorTest (8 tests)
- PostgreSQLDataTypeHandlerTest (8 tests)
- PostgreSQLMetadataQueryGeneratorTest (12 tests)

**Total: 35+ unit tests**

## Migrating from Core Implementation

If migrating from `consilens-core` PostgreSQLDialect:

1. Replace direct instantiation:
   ```java
   // Old
   PostgreSQLDialect dialect = new PostgreSQLDialect();
   
   // New
   PostgreSQLDatabaseDialect dialect = new PostgreSQLDatabaseDialect();
   ```

2. Use component getters:
   ```java
   // Access components
   dialect.getSqlQueryGenerator();
   dialect.getMetadataQueryGenerator();
   dialect.getDataTypeHandler();
   ```

## License

Copyright © 2025 Consilens

---

**Next:** See other connector implementations (MySQL, Oracle, SQL Server...)
