package com.zenith.mc.potion;

import com.zenith.mc.RegistryData;

public record PotionData(
    int id,
    String name
) implements RegistryData {
}
