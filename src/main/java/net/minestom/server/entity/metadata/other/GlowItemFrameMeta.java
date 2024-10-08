package net.minestom.server.entity.metadata.other;

import net.minestom.server.entity.Entity;
import net.minestom.server.entity.MetadataHolder;
import org.jetbrains.annotations.NotNull;

public class GlowItemFrameMeta extends ItemFrameMeta {
    public static final byte OFFSET = ItemFrameMeta.MAX_OFFSET;
    public static final byte MAX_OFFSET = OFFSET + 0;

    public GlowItemFrameMeta(@NotNull Entity entity, @NotNull MetadataHolder metadata) {
        super(entity, metadata);
    }
}
