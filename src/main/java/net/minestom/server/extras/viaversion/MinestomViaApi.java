package net.minestom.server.extras.viaversion;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.ViaAPI;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.legacy.LegacyViaAPI;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.ServerProtocolVersion;
import io.netty.buffer.ByteBuf;
import net.minestom.server.network.player.PlayerConnection;
import org.jetbrains.annotations.Nullable;

import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

final class MinestomViaApi implements ViaAPI<PlayerConnection> {
    @Override
    public ServerProtocolVersion getServerVersion() {
        return Via.getManager().getProtocolManager().getServerProtocolVersion();
    }

    @Override
    public ProtocolVersion getPlayerProtocolVersion(PlayerConnection player) {
        return ProtocolVersion.unknown;
    }

    @Override
    public ProtocolVersion getPlayerProtocolVersion(UUID uuid) {
        return ProtocolVersion.unknown;
    }

    @Override
    public boolean isInjected(UUID uuid) {
        return false;
    }

    @Override
    public @Nullable UserConnection getConnection(UUID uuid) {
        return null;
    }

    @Override
    public String getVersion() {
        return Via.getPlatform().getPluginVersion();
    }

    @Override
    public void sendRawPacket(PlayerConnection player, ByteBuf packet) {
        throw new UnsupportedOperationException("Raw packet sending is not supported by the Minestom Via platform");
    }

    @Override
    public void sendRawPacket(UUID uuid, ByteBuf packet) {
        throw new UnsupportedOperationException("Raw packet sending is not supported by the Minestom Via platform");
    }

    @Override
    public SortedSet<ProtocolVersion> getSupportedProtocolVersions() {
        return new TreeSet<>(ProtocolVersion.getProtocols());
    }

    @Override
    public SortedSet<ProtocolVersion> getFullSupportedProtocolVersions() {
        return new TreeSet<>(ProtocolVersion.getProtocols());
    }

    @Override
    public LegacyViaAPI<PlayerConnection> legacyAPI() {
        return (title, health, color, style) -> {
            throw new UnsupportedOperationException("Legacy boss bars are not supported by the Minestom Via platform");
        };
    }
}
