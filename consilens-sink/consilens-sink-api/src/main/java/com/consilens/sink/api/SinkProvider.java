package com.consilens.sink.api;

/**
 * Sink SPI interface; providers register by format + type as a unique key.
 * Register via META-INF/services/com.consilens.sink.api.SinkProvider.
 */
public interface SinkProvider {

    String getFormat();

    String getType();

    Sink create();
}
