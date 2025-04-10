package net.minestom.server.item.component;

import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.NetworkBufferTemplate;
import net.minestom.server.network.packet.server.play.data.WorldPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record LodestoneTracker(@Nullable WorldPos target, boolean tracked) {

    public static final NetworkBuffer.Type<LodestoneTracker> NETWORK_TYPE = NetworkBufferTemplate.template(
            WorldPos.NETWORK_TYPE.optional(), LodestoneTracker::target,
            NetworkBuffer.BOOLEAN, LodestoneTracker::tracked,
            LodestoneTracker::new);
    public static final Codec<LodestoneTracker> CODEC = StructCodec.struct(
            "target", WorldPos.CODEC.optional(), LodestoneTracker::target,
            "tracked", Codec.BOOLEAN.optional(true), LodestoneTracker::tracked,
            LodestoneTracker::new);

    public LodestoneTracker(@NotNull String dimension, @NotNull Point blockPosition, boolean tracked) {
        this(new WorldPos(dimension, blockPosition), tracked);
    }

    public @NotNull LodestoneTracker withTarget(@Nullable WorldPos target) {
        return new LodestoneTracker(target, tracked);
    }

    public @NotNull LodestoneTracker withTracked(boolean tracked) {
        return new LodestoneTracker(target, tracked);
    }

}
