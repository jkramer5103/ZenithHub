package com.zenith.mc.block.properties;

import com.zenith.mc.block.properties.api.StringRepresentable;

public enum TestBlockMode implements StringRepresentable {
    START(0, "start"),
    LOG(1, "log"),
    FAIL(2, "fail"),
    ACCEPT(3, "accept");
    private final int id;
    private final String name;

    TestBlockMode(final int id, final String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }
}
