package com.consilens.server.support.crypto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.consilens.server.domain.model.DataSourceRecord;
import com.consilens.server.domain.repository.DataSourceRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * One-time migration of legacy datasource passwords (plaintext or the old
 * "enc:" format) into the v2 SecretProtector envelope. Runs before deploying
 * the new Agent runtime; after a successful, verified run the legacy reader
 * (CryptoSupport) is deleted and no dual-format path remains.
 */
@Component
public class DatasourceSecretMigration {

    private static final String LEGACY_PREFIX = "enc:";

    private final DataSourceRepository dataSourceRepository;
    private final ObjectMapper objectMapper;
    private final SecretProtector protector;

    public DatasourceSecretMigration(DataSourceRepository dataSourceRepository,
                                     ObjectMapper objectMapper,
                                     SecretProtector protector) {
        this.dataSourceRepository = dataSourceRepository;
        this.objectMapper = objectMapper;
        this.protector = protector;
    }

    /**
     * @return number of datasource rows rewritten into v2 envelopes.
     */
    public int migrate() {
        if (!(protector instanceof AesGcmSecretProtector)) {
            throw new IllegalStateException("legacy migration requires AesGcmSecretProtector");
        }
        AesGcmSecretProtector aesProtector = (AesGcmSecretProtector) protector;
        int migrated = 0;
        for (DataSourceRecord record : dataSourceRepository.listAll()) {
            Map<String, Object> param = fromJson(record.getParamJson());
            Object password = param.get("password");
            if (!(password instanceof String)) {
                continue;
            }
            String value = (String) password;
            if (value.startsWith("v2:")) {
                continue;
            }
            String plaintext = value.startsWith(LEGACY_PREFIX)
                    ? aesProtector.decryptLegacyEnc(value)
                    : value;
            param.put("password", protector.protect(plaintext));
            record.setParamJson(toJson(param));
            dataSourceRepository.save(record);
            migrated++;
        }
        return migrated;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("datasource param json is corrupted", e);
        }
    }

    private String toJson(Map<String, Object> param) {
        try {
            return objectMapper.writeValueAsString(param);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize migrated param", e);
        }
    }
}
