package net.minestom.server.extras.viaversion;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.exception.CancelCodecException;
import com.viaversion.viaversion.exception.CancelDecoderException;
import com.viaversion.viaversion.exception.CancelEncoderException;
import com.viaversion.viaversion.platform.ViaDecodeHandler;
import com.viaversion.viaversion.platform.ViaEncodeHandler;
import com.viaversion.viaversion.protocol.ProtocolPipelineImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CodecException;
import net.minestom.server.network.NetworkBuffer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-connection ViaVersion state. The transform itself is a buffer operation - Via rewrites a packet's
 * {@code [packet id][payload]} between protocol versions - so the only bridging is copying the relevant
 * region of a {@link NetworkBuffer} into Via's {@link ByteBuf} and back.
 * <p>
 * Some protocols also inject extra packets (e.g. ViaBackwards bridging the pre-1.20.2 login flow). Via emits
 * those through the connection's channel, so we give it an in-memory {@link EmbeddedChannel} (no sockets or
 * event loops) and hand whatever it injects to the {@link ViaPacketSink}. Read and write run on separate
 * threads, while Via assumes a single channel thread, so transforms are serialized under {@link #lock}.
 */
@ApiStatus.Internal
public final class ViaConnection {
    private final UserConnection user;
    private final EmbeddedChannel channel;
    private final ViaPacketSink sink;
    private final Object lock = new Object();

    ViaConnection(ViaPacketSink sink) {
        this.sink = sink;
        this.channel = new EmbeddedChannel();
        this.user = new UserConnectionImpl(this.channel, false);
        new ProtocolPipelineImpl(this.user); // installs the base protocol that reads the handshake
        // Via routes injected packets through these handlers, looked up by the injector's default names.
        this.channel.pipeline().addLast(ViaEncodeHandler.NAME, new ViaEncodeHandler(this.user));
        this.channel.pipeline().addLast(ViaDecodeHandler.NAME, new ViaDecodeHandler(this.user));
    }

    /**
     * Whether packets should be transformed; Via sets this to {@code false} for native clients.
     */
    public boolean active() {
        return this.user.shouldTransformPacket();
    }

    /**
     * The client protocol version negotiated from the handshake, or {@code unknown} before it is read.
     */
    public ProtocolVersion clientVersion() {
        return this.user.getProtocolInfo().protocolVersion();
    }

    /**
     * The result of a clientbound transform: packets Via injected while handling the source packet
     * (to be written to the client first, in order) and the transformed source packet itself
     * ({@code null} if Via dropped it).
     */
    public record ClientboundResult(List<byte[]> injectedBefore, byte @Nullable [] output) {
    }

    /**
     * Transforms a serverbound {@code [packet id][payload]} in {@code [from, to)} from the client's version
     * to the server's, returning the new bytes or {@code null} if Via dropped the packet.
     */
    public byte @Nullable [] transformServerbound(NetworkBuffer source, long from, long to) {
        final List<byte[]> injectedClientbound = new ArrayList<>();
        final List<byte[]> injectedServerbound = new ArrayList<>();
        final byte[] output = this.transform(source, from, to, true, injectedClientbound, injectedServerbound);
        // Clientbound packets Via emits while handling a serverbound packet have no clientbound packet they
        // must precede, so they ride the normal write queue.
        for (byte[] packet : injectedClientbound) this.sink.sendInjectedClientbound(packet);
        for (byte[] packet : injectedServerbound) this.sink.dispatchInjectedServerbound(packet);
        return output;
    }

    /**
     * Transforms a clientbound {@code [packet id][payload]} in {@code [from, to)} from the server's version
     * to the client's. Returns the transformed packet plus any packets Via injected while handling it (e.g.
     * the {@code world_clock} registry Via sends during {@code finish_configuration}); those must be written
     * to the client <i>before</i> the transformed packet, exactly as Via emits them on a real pipeline.
     */
    public ClientboundResult transformClientbound(NetworkBuffer source, long from, long to) {
        final List<byte[]> injectedClientbound = new ArrayList<>();
        final List<byte[]> injectedServerbound = new ArrayList<>();
        final byte[] output = this.transform(source, from, to, false, injectedClientbound, injectedServerbound);
        // Serverbound packets injected while translating a clientbound packet (e.g. ViaBackwards' login
        // acknowledgement) are dispatched as if received from the client.
        for (byte[] packet : injectedServerbound) this.sink.dispatchInjectedServerbound(packet);
        return new ClientboundResult(injectedClientbound, output);
    }

    private byte @Nullable [] transform(NetworkBuffer source, long from, long to, boolean serverbound,
                                        List<byte[]> injectedClientbound, List<byte[]> injectedServerbound) {
        final int length = (int) (to - from);
        final byte[] input = new byte[length];
        source.copyTo(from, input, 0, length);

        byte[] output = null;
        boolean cancelled = false;

        final ByteBuf buffer = Unpooled.buffer(Math.max(length, 1));

        try {
            buffer.writeBytes(input);

            synchronized (this.lock) {
                try {
                    if (serverbound) {
                        this.user.transformIncoming(buffer, CancelDecoderException::generate);
                    } else {
                        this.user.transformOutgoing(buffer, CancelEncoderException::generate);
                    }
                    output = readBytes(buffer);
                } catch (CodecException exception) {
                    if (!(exception instanceof CancelCodecException)) throw exception;
                    cancelled = true; // Via fully handled or dropped this packet.
                } finally {
                    this.drainInjected(injectedClientbound, injectedServerbound);
                }
            }
        } finally {
            buffer.release();
        }

        return cancelled ? null : output;
    }

    // Runs Via's pending channel tasks and collects what it injected: outbound packets are clientbound,
    // inbound are serverbound. Called under the lock.
    private void drainInjected(List<byte[]> clientbound, List<byte[]> serverbound) {
        boolean progressed = true;

        while (progressed) {
            progressed = false;
            this.channel.runPendingTasks();
            ByteBuf out;

            while ((out = this.channel.readOutbound()) != null) {
                progressed = true;
                clientbound.add(readBytes(out));
                out.release();
            }


            ByteBuf in;
            while ((in = this.channel.readInbound()) != null) {
                progressed = true;
                serverbound.add(readBytes(in));
                in.release();
            }
        }
    }

    private static byte[] readBytes(ByteBuf buffer) {
        final byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return bytes;
    }

    public UserConnection user() {
        return this.user;
    }

    /**
     * Releases the Via state held for this connection.
     * <p>
     * Via registers every server-side {@link UserConnection} in its global connection manager on login
     * success and normally drops it again through the close listener of a real Netty pipeline. The
     * {@link EmbeddedChannel} used here is never closed by Via itself, so without this call each connection
     * leaks its whole protocol pipeline, and every reconnect of the same player logs a duplicate-UUID
     * warning because the stale entry is still registered.
     */
    public void close() {
        synchronized (this.lock) {
            Via.getManager().getConnectionManager().onDisconnect(this.user);
            this.channel.finishAndReleaseAll();
        }
    }
}
