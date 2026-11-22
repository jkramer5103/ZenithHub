package com.zenith.util.config;

import java.io.Reader;
import java.io.Writer;

/** How a config object is written to and read from its file. */
public interface ConfigSerializer {
    void write(Object config, Writer writer);

    <T> T read(Class<T> configClass, Reader reader);
}
