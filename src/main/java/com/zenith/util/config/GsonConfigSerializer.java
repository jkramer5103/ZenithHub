package com.zenith.util.config;

import java.io.Reader;
import java.io.Writer;

import static com.zenith.Globals.GSON;

public final class GsonConfigSerializer implements ConfigSerializer {
    public static final GsonConfigSerializer INSTANCE = new GsonConfigSerializer();

    private GsonConfigSerializer() {}

    @Override
    public void write(final Object config, final Writer writer) {
        GSON.toJson(config, writer);
    }

    @Override
    public <T> T read(final Class<T> configClass, final Reader reader) {
        return GSON.fromJson(reader, configClass);
    }
}
