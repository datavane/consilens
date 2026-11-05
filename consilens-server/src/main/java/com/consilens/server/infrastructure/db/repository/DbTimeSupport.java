package com.consilens.server.infrastructure.db.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

final class DbTimeSupport {

    private static volatile ZoneId configuredZone = ZoneId.systemDefault();

    private DbTimeSupport() {
    }

    static void configure(ZoneId zone) {
        configuredZone = zone != null ? zone : ZoneId.systemDefault();
    }

    static LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, configuredZone);
    }

    static Instant toInstant(LocalDateTime localDateTime) {
        return localDateTime == null ? null : localDateTime.atZone(configuredZone).toInstant();
    }
}
