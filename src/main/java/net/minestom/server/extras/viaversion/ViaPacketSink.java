package net.minestom.server.extras.viaversion;

import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public interface ViaPacketSink {
    void sendInjectedClientbound(byte[] idAndData);

    void dispatchInjectedServerbound(byte[] idAndData);
}
