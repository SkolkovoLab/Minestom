package net.minestom.server.entity;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.kyori.adventure.key.Key;
import net.minestom.server.codec.Codec;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.registry.StaticProtocolObject;
import org.jetbrains.annotations.NotNull;

/**
 * AUTOGENERATED by GenericEnumGenerator
 */
public enum VillagerType implements StaticProtocolObject {
    DESERT(Key.key("minecraft:desert")),

    JUNGLE(Key.key("minecraft:jungle")),

    PLAINS(Key.key("minecraft:plains")),

    SAVANNA(Key.key("minecraft:savanna")),

    SNOW(Key.key("minecraft:snow")),

    SWAMP(Key.key("minecraft:swamp")),

    TAIGA(Key.key("minecraft:taiga"));

    private static final Map<Key, VillagerType> BY_KEY = Arrays.stream(values()).collect(Collectors.toMap(VillagerType::key, Function.identity()));

    public static final NetworkBuffer.Type<VillagerType> NETWORK_TYPE = NetworkBuffer.Enum(VillagerType.class);

    public static final Codec<VillagerType> CODEC = Codec.KEY.transform(BY_KEY::get, VillagerType::key);

    private final Key key;

    VillagerType(@NotNull Key key) {
        this.key = key;
    }

    @NotNull
    @Override
    public Key key() {
        return this.key;
    }

    @Override
    public int id() {
        return this.ordinal();
    }
}
