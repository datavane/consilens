# MySQL Database Connector

MySQL database dialect implementation for the Consilens data comparison framework.

## Overview

This module provides MySQL-specific implementations of database dialect components following the SPI (Service Provider Interface) pattern.

### Components

- **MySQLDatabaseDialect** - Main dialect entry point, composes all MySQL-specific components
- **MySQLCapabilityProvider** - Defines MySQL features, capabilities, and identifier quotes (backticks)
- **MySQLSqlQueryGenerator** - Generates MySQL-specific SQL (LIMIT, UNION for FULL OUTER JOIN)
- **MySQLMetadataQueryGenerator** - Queries MySQL's `information_schema` for metadata
- **MySQLDataTypeHandler** - Handles MySQL data type normalization and mapping

## Architecture

```
MySQLDatabaseDialect
├── MySQLCapabilityProvider (defines features & quotes)
├── MySQLDataTypeHandler (type mapping & normalization)
├── MySQLSqlQueryGenerator (SQL generation)
│   └── uses: CapabilityProvider, DataTypeHandler
└── MySQLMetadataQueryGenerator (metadata queries)
    └── uses: CapabilityProvider
```

## Features

### SQL Generation
- ✅ MySQL-specific LIMIT syntax: `LIMIT offset, limit`
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

```java
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.core.database.dialect.DialectFactory;

// Load MySQL dialect via PluginManager-backed DialectFactory
DatabaseDialect mysql = DialectFactory.getDialect(DatabaseType.MYSQL);

// Use the dialect
SqlQueryGenerator queryGen = mysql.getSqlQueryGenerator();
```

### Direct Instantiation

```java
import com.consilens.connector.mysql.MySQLDatabaseDialect;

// Create dialect instance
DatabaseDialect mysql = new MySQLDatabaseDialect();

// Generate SQL
SqlQueryGenerator queryGen = mysql.getSqlQueryGenerator();
String limitSql = queryGen.getLimitClause(10, 20);  // "LIMIT 10, 20"

// Get metadata
MetadataQueryGenerator metaGen = mysql.getMetadataQueryGenerator();
String tableExistsSql = metaGen.getTableExistsSQL("mydb", "users");
```

### Code Examples

#### Checksum Calculation
```java
List<String> columns = Arrays.asList("id", "name", "email");
String checksumSql = queryGen.getChecksumSQL(
    "mydb", "users", columns, "status = 'active'"
);
// Generates MD5-based checksum query
```

#### Data Type Normalization
```java
DataTypeHandler typeHandler = mysql.getDataTypeHandler();

// Normalize for checksum
String normalized = typeHandler.normalizeColumn("created_at", "TIMESTAMP");
// Returns: COALESCE(DATE_FORMAT(`created_at`, '%Y-%m-%d %H:%i:%s'), '')

// Map data type
String mysqlType = typeHandler.getDataTypeMapping("varchar", 255, 0, 0);
// Returns: VARCHAR(255)
```

## Dependencies

```xml
<dependency>
    <groupId>com.consilens</groupId>
    <artifactId>consilens-connector-mysql</artifactId>
    <version>0.1-SNAPSHOT</version>
</dependency>
```

**Runtime Dependencies:**
- `consilens-connector-base` - Base implementations
- `consilens-connector-api` - API interfaces  
- `consilens-spi` - Plugin runtime used by `consilens-core`

**Optional:**
- MySQL JDBC Driver (for actual database connections)

## Supported MySQL Features

| Feature | Supported | Implementation |
|---------|-----------|----------------|
| Window Functions | ✅ | MySQL 8.0+ |
| JSON Functions | ✅ | JSON data type |
| Unique Constraints | ✅ | UNIQUE keyword |
| Stored Procedures | ✅ | DELIMITER syntax |
| Transactions | ✅ | BEGIN/COMMIT/ROLLBACK |
| Savepoints | ✅ | SAVEPOINT |
| Check Constraints | ✅ | MySQL 8.0.16+ |
| FULL OUTER JOIN | ⚠️ | Simulated via UNION |

## Design Decisions

### Why Backtick Quotes?
MySQL uses backticks (`) for identifier quoting, different from SQL standard double quotes (").

### Why UNION for FULL OUTER JOIN?
MySQL does not support native FULL OUTER JOIN. We simulate it using:
```sql
(SELECT * FROM t1 LEFT JOIN t2 ON ...) 
UNION 
(SELECT * FROM t1 RIGHT JOIN t2 ON ...)
```

### Checksum Boolean Handling
TINYINT(1) is checked **before** general INT types to correctly identify MySQL booleans.

## Testing

Run tests:
```bash
mvn test -pl consilens-connector-mysql
```

Test coverage:
- MySQLDatabaseDialectTest (7 tests)
- MySQLSqlQueryGeneratorTest (8 tests)
- MySQLDataTypeHandlerTest (8 tests)
- MySQLMetadataQueryGeneratorTest (12 tests)

**Total: 35+ unit tests**

## Migrating from Core Implementation

If migrating from `consilens-core` MySQLDialect:

1. Replace direct instantiation:
   ```java
   // Old
   MySQLDialect dialect = new MySQLDialect();
   
   // New
   MySQLDatabaseDialect dialect = new MySQLDatabaseDialect();
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

**Next:** See other connector implementations (PostgreSQL, Oracle, SQL Server...)
