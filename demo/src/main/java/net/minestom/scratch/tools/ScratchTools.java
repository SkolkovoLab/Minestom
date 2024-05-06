package net.minestom.scratch.tools;

import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.message.Messenger;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.world.DimensionTypeManager;
import net.minestom.server.world.biomes.BiomeManager;

import java.util.ArrayList;
import java.util.List;

public final class ScratchTools {
    public static List<SendablePacket> REGISTRY_DATA_PACKETS = new ArrayList<>();

    static {
        DimensionTypeManager dimensionTypeManager = new DimensionTypeManager();
        BiomeManager biomeManager = new BiomeManager();
        REGISTRY_DATA_PACKETS.add(Messenger.registryDataPacket());
        REGISTRY_DATA_PACKETS.add(dimensionTypeManager.registryDataPacket());
        REGISTRY_DATA_PACKETS.add(biomeManager.registryDataPacket());
        REGISTRY_DATA_PACKETS.add(DamageType.registryDataPacket());
    }
}