package com.consilens.server.support.crypto;

import com.consilens.server.domain.model.DataSourceRecord;
import com.consilens.server.domain.repository.DataSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatasourceSecretMigrationTest {

    private final DataSourceRepository repository = mock(DataSourceRepository.class);
    private final AesGcmSecretProtector protector = SecretProtectorTestKeys.protector();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void convertsPlaintextAndLegacyEnvelopesToV2() throws Exception {
        List<DataSourceRecord> saved = new ArrayList<>();
        DataSourceRecord plaintext = DataSourceRecord.builder()
                .id(1L).name("plain").type("mysql")
                .paramJson("{\"host\":\"h\",\"password\":\"clear\"}")
                .build();
        DataSourceRecord alreadyV2 = DataSourceRecord.builder()
                .id(2L).name("v2").type("mysql")
                .paramJson("{\"host\":\"h\",\"password\":\"" + protector.protect("ok") + "\"}")
                .build();
        when(repository.listAll()).thenReturn(List.of(plaintext, alreadyV2));
        when(repository.save(any())).thenAnswer(invocation -> {
            DataSourceRecord record = invocation.getArgument(0);
            saved.add(record);
            return record;
        });

        DatasourceSecretMigration migration =
                new DatasourceSecretMigration(repository, mapper, protector);
        int migrated = migration.migrate();

        assertEquals(1, migrated);
        assertEquals(1, saved.size());
        assertTrue(saved.get(0).getParamJson().contains("v2:"));
        assertTrue(!saved.get(0).getParamJson().contains("clear"));
        assertEquals("clear", protector.reveal(passwordOf(saved.get(0))));
    }

    @SuppressWarnings("unchecked")
    private String passwordOf(DataSourceRecord record) throws Exception {
        java.util.Map<String, Object> param =
                mapper.readValue(record.getParamJson(), java.util.Map.class);
        return (String) param.get("password");
    }
}
