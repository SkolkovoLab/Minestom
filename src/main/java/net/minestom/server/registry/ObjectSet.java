package net.minestom.server.registry;

import net.kyori.adventure.key.Key;
import net.minestom.server.codec.Codec;
import net.minestom.server.gamedata.tags.Tag;
import net.minestom.server.network.NetworkBuffer;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * A set of some protocol objects. May contain a single element, multiple elements, or a single tag (which itself contains multiple elements).
 *
 * @param <T> The type of protocol object represented by this set.
 */
public sealed interface ObjectSet<T extends ProtocolObject> permits ObjectSetImpl {

    static <T extends ProtocolObject> @NotNull ObjectSet<T> empty() {
        //noinspection unchecked
        return (ObjectSet<T>) ObjectSetImpl.Empty.INSTANCE;
    }

    static <T extends ProtocolObject> @NotNull ObjectSet<T> of(@NotNull Collection<Key> entries) {
        return new ObjectSetImpl.Entries<>(java.util.List.copyOf(entries));
    }

    static <T extends ProtocolObject> @NotNull ObjectSet<T> of(@NotNull Tag tag) {
        return new ObjectSetImpl.Tag<>(tag);
    }

    static <T extends ProtocolObject> NetworkBuffer.@NotNull Type<ObjectSet<T>> networkType(@NotNull Tag.BasicType tagType) {
        return new ObjectSetImpl.NetworkType<>(tagType);
    }

    static <T extends ProtocolObject> @NotNull Codec<ObjectSet<T>> codec(@NotNull Tag.BasicType tagType) {
        return new ObjectSetImpl.CodecImpl<>(tagType);
    }

    /**
     * <p>Check if this set contains the given object, tested against its namespace id.</p>
     *
     * <p>Present for compatibility with non-dynamic registries. Will be removed in the future.</p>
     *
     * @param object The object to check for.
     * @return True if this set contains the object, false otherwise.
     */
    default boolean contains(@NotNull StaticProtocolObject object) {
        return contains(object.key());
    }

    default boolean contains(@NotNull DynamicRegistry.Key<T> key) {
        return contains(key.key());
    }

    boolean contains(@NotNull Key namespace);

}
