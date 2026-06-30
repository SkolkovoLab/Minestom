package net.minestom.server.extras.viaversion;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.platform.NoopInjector;
import net.minestom.server.MinecraftServer;

import java.util.SortedSet;
import java.util.TreeSet;

final class MinestomViaInjector extends NoopInjector {
    @Override
    public ProtocolVersion getServerProtocolVersion() {
        return ProtocolVersion.getProtocol(MinecraftServer.PROTOCOL_VERSION);
    }

    @Override
    public SortedSet<ProtocolVersion> getServerProtocolVersions() {
        final SortedSet<ProtocolVersion> versions = new TreeSet<>();
        versions.add(this.getServerProtocolVersion());
        return versions;
    }
}
