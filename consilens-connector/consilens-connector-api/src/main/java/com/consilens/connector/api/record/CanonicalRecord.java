package com.consilens.connector.api.record;

import java.util.Map;

public interface CanonicalRecord {

    RecordKey getKey();

    Map<String, CanonicalValue> getValues();
}
