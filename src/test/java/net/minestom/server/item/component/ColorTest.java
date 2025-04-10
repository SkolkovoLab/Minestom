package net.minestom.server.item.component;

import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.util.RGBLike;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.color.Color;
import net.minestom.server.component.DataComponent;
import net.minestom.server.component.DataComponents;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static java.util.Map.entry;
import static net.minestom.server.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ColorTest extends AbstractItemComponentTest<RGBLike> {
    // This is not a test, but it creates a compile error if the component type is changed away from Integer,
    // as a reminder that tests should be added for that new component type.
    private static final List<DataComponent<RGBLike>> SHARED_COMPONENTS = List.of(
            DataComponents.MAP_COLOR
    );

    @Override
    protected @NotNull DataComponent<RGBLike> component() {
        return SHARED_COMPONENTS.getFirst();
    }

    @Override
    protected @NotNull List<Map.Entry<String, RGBLike>> directReadWriteEntries() {
        return List.of(
                entry("simple", new Color(0x123456))
        );
    }

    @Test
    void namedTextColor() {
        var tag = assertOk(DataComponents.MAP_COLOR.encode(Transcoder.NBT, NamedTextColor.YELLOW));
        assertEquals(IntBinaryTag.intBinaryTag(16777045), tag);
    }
}
