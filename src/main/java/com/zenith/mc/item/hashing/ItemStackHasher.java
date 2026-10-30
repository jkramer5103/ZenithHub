package com.zenith.mc.item.hashing;

import org.geysermc.mcprotocollib.protocol.data.game.item.HashedStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponent;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.zenith.Globals.DEFAULT_LOG;
import static com.zenith.mc.item.hashing.DataComponentHashers.NOT_HASHED;

public class ItemStackHasher {

    public static HashedStack hash(ItemStack stack) {
        if (stack == null) {
            return null;
        }

        DataComponents patch = stack.getDataComponents();
        if (patch == null) {
            return new HashedStack(stack.getId(), stack.getAmount(), Map.of(), Set.of());
        }
        Map<DataComponentType<?>, DataComponent<?, ?>> components = patch.getDataComponents();
        Map<DataComponentType<?>, Integer> hashedAdditions = new HashMap<>();
        Set<DataComponentType<?>> removals = new HashSet<>();
        for (Map.Entry<DataComponentType<?>, DataComponent<?, ?>> component : components.entrySet()) {
            if (NOT_HASHED.contains(component.getKey())) {
                DEFAULT_LOG.debug("Not hashing component {} on stack {}", component.getKey(), stack);
            } else if (component.getValue().getValue() == null) {
                removals.add(component.getKey());
            } else {
                hashedAdditions.put(component.getKey(), DataComponentHashers.hash((DataComponentType) component.getKey(), component.getValue().getValue()).asInt());
            }
        }
        return new HashedStack(stack.getId(), stack.getAmount(), hashedAdditions, removals);
    }

    public static boolean matches(HashedStack hashedStack, ItemStack stack) {
        if (hashedStack == null && stack == null) return true;
        if (hashedStack == null || stack == null) return false;
        if (hashedStack.id() != stack.getId()) return false;
        if (hashedStack.count() != stack.getAmount()) return false;
        var stackDataComponents = stack.getDataComponents();
        if (stackDataComponents == null) {
            if (!hashedStack.addedComponents().isEmpty()) return false;
            if (!hashedStack.removedComponents().isEmpty()) return false;
            return true;
        } else {
            // todo: can this be optimized?
            //  pre-cache stack hashes?
            var hashedAdded = hashedStack.addedComponents();
            var hashedRemoved = hashedStack.removedComponents();
            for (var component : stackDataComponents.getDataComponents().entrySet()) {
                var componentType = component.getKey();
                if (NOT_HASHED.contains(componentType)) continue;
                var isRemoved = component.getValue().getValue() == null;
                if (isRemoved) {
                    if (hashedAdded.containsKey(componentType)) return false;
                    if (!hashedRemoved.contains(componentType)) return false;
                } else {
                    if (!hashedAdded.containsKey(componentType)) return false;
                    if (hashedRemoved.contains(componentType)) return false;
                    var hashedValue = DataComponentHashers.hash((DataComponentType) componentType, component.getValue().getValue()).asInt();
                    if (hashedAdded.get(componentType) != hashedValue) return false;
                }
            }
            // todo: can we avoid needing these extra iterations?
            for (var entry : hashedAdded.entrySet()) {
                var componentType = entry.getKey();
                if (!stackDataComponents.getDataComponents().containsKey(componentType)) return false;
                if (entry.getValue() != DataComponentHashers.hash((DataComponentType) componentType, stackDataComponents.getDataComponents().get(componentType).getValue()).asInt()) return false;
            }
            for (var componentType : hashedRemoved) {
                if (!stackDataComponents.getDataComponents().containsKey(componentType)) return false;
                if (stackDataComponents.getDataComponents().get(componentType).getValue() != null) return false;
            }
        }
        return true;
    }
}
