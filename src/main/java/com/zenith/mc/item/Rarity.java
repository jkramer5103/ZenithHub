package com.zenith.mc.item;

import lombok.Getter;

@Getter
public enum Rarity {
    COMMON(0, "common"),
    UNCOMMON(1, "uncommon"),
    RARE(2, "rare"),
    EPIC(3, "epic");

    private final int id;
    private final String name;

    Rarity(final int id, final String name) {
        this.id = id;
        this.name = name;
    }


}
