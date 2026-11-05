package com.consilens.server.application.datasource;

import com.consilens.server.api.dto.DataSourceTypeDto;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.DatabaseDialectProvider;
import com.consilens.connector.api.DatabaseDialects;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Thin SPI facade over {@link DatabaseDialects} so services can be unit-tested
 * with a mock instead of the real ServiceLoader.
 */
@Component
public class DialectSupport {

    public Optional<DatabaseDialect> find(String type) {
        return DatabaseDialects.find(type);
    }

    /**
     * Enumerate all connector types registered via META-INF/services, sorted by type name.
     */
    public List<DataSourceTypeDto> listTypes() {
        List<DataSourceTypeDto> types = new ArrayList<>();
        ServiceLoader<DatabaseDialectProvider> loader = ServiceLoader.load(DatabaseDialectProvider.class);
        for (DatabaseDialectProvider provider : loader) {
            DatabaseDialect dialect = provider.create();
            types.add(DataSourceTypeDto.builder()
                    .type(provider.getConnectorType())
                    .defaultPort(dialect.getDefaultPort())
                    .driverClass(dialect.getJdbcDriverClassName())
                    .build());
        }
        types.sort(Comparator.comparing(DataSourceTypeDto::getType));
        return types;
    }
}
