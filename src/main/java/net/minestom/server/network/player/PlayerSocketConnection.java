package net.minestom.server.network.player;

import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.adventure.MinestomAdventure;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.ListenerHandle;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.extras.mojangAuth.MojangCrypt;
import net.minestom.server.extras.viaversion.ViaConnection;
import net.minestom.server.extras.viaversion.ViaPacketSink;
import net.minestom.server.extras.viaversion.ViaVersion;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.PacketParser;
import net.minestom.server.network.packet.PacketReading;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.PacketWriting;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.common.ClientCookieResponsePacket;
import net.minestom.server.network.packet.client.common.ClientKeepAlivePacket;
import net.minestom.server.network.packet.client.common.ClientPingRequestPacket;
import net.minestom.server.network.packet.client.configuration.ClientFinishConfigurationPacket;
import net.minestom.server.network.packet.client.configuration.ClientSelectKnownPacksPacket;
import net.minestom.server.network.packet.client.handshake.ClientHandshakePacket;
import net.minestom.server.network.packet.client.login.ClientEncryptionResponsePacket;
import net.minestom.server.network.packet.client.login.ClientLoginAcknowledgedPacket;
import net.minestom.server.network.packet.client.login.ClientLoginPluginResponsePacket;
import net.minestom.server.network.packet.client.login.ClientLoginStartPacket;
import net.minestom.server.network.packet.client.status.StatusRequestPacket;
import net.minestom.server.network.packet.server.*;
import net.minestom.server.network.packet.server.login.SetCompressionPacket;
import net.minestom.server.utils.collection.ConcurrentMessageQueues;
import net.minestom.server.utils.validate.Check;
import org.jctools.queues.MessagePassingQueue;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.DataFormatException;

/**
 * Represents a socket connection.
 * <p>
 * It is the implementation used for all network client.
 */
@ApiStatus.Internal
public class PlayerSocketConnection extends PlayerConnection implements ViaPacketSink {
    private static final Set<Class<? extends ClientPacket>> IMMEDIATE_PROCESS_PACKETS = Set.of(
            ClientHandshakePacket.class, // First received packet
            ClientCookieResponsePacket.class,
            StatusRequestPacket.class,
            ClientPingRequestPacket.class,
            ClientKeepAlivePacket.class, // Used to calculate latency
            ClientLoginStartPacket.class,
            ClientEncryptionResponsePacket.class, // Auth request
            ClientLoginPluginResponsePacket.class,
            ClientSelectKnownPacksPacket.class, // Immediate answer to server request on config
            ClientLoginAcknowledgedPacket.class, // Handle config state
            ClientFinishConfigurationPacket.class // Enter play state
    );

    // Pause taken by the write loop when the socket cannot accept more data, instead of retrying immediately.
    private static final long WRITE_BACKOFF_NANOS = TimeUnit.MILLISECONDS.toNanos(
            Long.getLong("minestom.write-backoff-millis", 5));

    private final SocketChannel channel;
    private final AtomicBoolean channelClosed = new AtomicBoolean();
    private SocketAddress remoteAddress;

    //Could be null. Only used for Mojang Auth
    private volatile EncryptionContext encryptionContext;
    private byte[] nonce = new byte[4];

    // Data from client packets
    private String loginUsername;
    private GameProfile gameProfile;
    private String serverAddress;
    private int serverPort;
    private int protocolVersion;

    private final NetworkBuffer readBuffer = NetworkBuffer.resizableBuffer(ServerFlag.POOLED_BUFFER_SIZE, MinecraftServer.process());
    // ViaVersion translation for this connection, or null if Via is not enabled.
    private final ViaConnection viaConnection = ViaVersion.newConnection(this);
    // Clientbound packets Via injected, already in the client's version; written verbatim, bypassing Via.
    private final MessagePassingQueue<byte[]> injectedClientbound = ConcurrentMessageQueues.mpscUnboundedArrayQueue(16);
    private final MessagePassingQueue<SendablePacket> packetQueue = ConcurrentMessageQueues.mpscUnboundedArrayQueue(1024);
    private final Thread readThread, writeThread;

    private final AtomicLong sentPacketCounter = new AtomicLong();
    // Index where compression starts, linked to `sentPacketCounter`
    // Used instead of a simple boolean so we can get proper timing for serialization
    private volatile long compressionStart = Long.MAX_VALUE;

    // Write lock as the default behavior of the writing thread is to park itself
    // Requires ServerFlag.FASTER_SOCKET_WRITES to be enabled
    private final AtomicBoolean writeSignaled = new AtomicBoolean(false);

    private final ListenerHandle<PlayerPacketOutEvent> outgoing = EventDispatcher.getHandle(PlayerPacketOutEvent.class);

    private final boolean haProxy;

    public PlayerSocketConnection(SocketChannel channel, SocketAddress remoteAddress, Thread readThread, Thread writeThread, boolean haProxy) {
        super();
        this.channel = channel;
        this.remoteAddress = remoteAddress;
        this.writeThread = writeThread;
        this.readThread = readThread;
        this.haProxy = haProxy;
    }

    public PlayerSocketConnection(SocketChannel channel, SocketAddress remoteAddress, Thread readThread, Thread writeThread) {
        this(channel, remoteAddress, readThread, writeThread, false);
    }

    public void read(PacketParser<ClientPacket> packetParser) throws IOException {
        NetworkBuffer readBuffer = this.readBuffer;
        final long writeIndex = readBuffer.writeIndex();
        final int length = readBuffer.readChannel(channel);
        // Decrypt newly read data
        final EncryptionContext encryptionContext = this.encryptionContext;
        if (encryptionContext != null) {
            readBuffer.cipher(encryptionContext.decrypt(), writeIndex, length);
        }
        // Process packets
        processPackets(readBuffer, packetParser);
    }

    private boolean compression() {
        return compressionStart != Long.MAX_VALUE;
    }

    private void processPackets(NetworkBuffer readBuffer, PacketParser<ClientPacket> packetParser) {
        final ConnectionState startingState = getClientState();
        final PacketReading.Result<ClientPacket> result;
        try {
            result = PacketReading.readPackets(
                    readBuffer,
                    packetParser,
                    startingState, PacketVanilla::nextClientState,
                    compression(),
                    viaConnection
            );
        } catch (DataFormatException e) {
            MinecraftServer.getExceptionManager().handleException(e);
            disconnect();
            return;
        }
        switch (result) {
            case PacketReading.Result.Success<ClientPacket> success -> {
                for (PacketReading.ParsedPacket<ClientPacket> parsedPacket : success.packets()) {
                    handleClientPacket(parsedPacket.packet());
                }
                // Compact in case of incomplete read
                readBuffer.compact();
            }
            case PacketReading.Result.Empty<ClientPacket> ignored -> {
                // Empty
            }
            case PacketReading.Result.Skipped<ClientPacket> ignored -> {
                // Dropped by ViaVersion inside readPackets; nothing to do.
            }
            case PacketReading.Result.Failure<ClientPacket> failure -> {
                // Resize for next read
                final long requiredCapacity = failure.requiredCapacity();
                assert requiredCapacity > readBuffer.capacity() :
                        "New capacity should be greater than the current one: " + requiredCapacity + " <= " + readBuffer.capacity();
                readBuffer.resize(requiredCapacity);
            }
        }
    }

    private void handleClientPacket(ClientPacket packet) {
        try {
            final boolean processImmediately = IMMEDIATE_PROCESS_PACKETS.contains(packet.getClass());
            if (processImmediately) {
                // Interpret the packet using the connection state we received it.
                MinecraftServer.getPacketListenerManager().processClientPacket(packet, this);
            } else {
                // To be processed during the next player tick
                final Player player = getPlayer();
                assert player != null;
                player.addPacketToQueue(packet);
            }
        } catch (Exception e) {
            MinecraftServer.getExceptionManager().handleException(e);
        }
    }

    @Override
    public void sendInjectedClientbound(byte[] idAndData) {
        this.injectedClientbound.relaxedOffer(idAndData);
        unlockWriteThread();
    }

    @Override
    public void dispatchInjectedServerbound(byte[] idAndData) {
        // Buffers from PACKET_POOL are created with MinecraftServer.process() registries, identical to
        // readBuffer's, so the framed payload reads back with the right registries without an extra setter.
        final NetworkBuffer framed = PacketVanilla.PACKET_POOL.get();

        try {
            framed.write(NetworkBuffer.VAR_INT, idAndData.length);
            framed.write(NetworkBuffer.RAW_BYTES, idAndData);
            final PacketReading.Result<ClientPacket> result = PacketReading.readClient(framed, getClientState(), false);

            if (result instanceof PacketReading.Result.Success<ClientPacket> success) {
                for (PacketReading.ParsedPacket<ClientPacket> parsedPacket : success.packets()) {
                    handleClientPacket(parsedPacket.packet());
                }
            }
        } catch (Exception e) {
            MinecraftServer.getExceptionManager().handleException(e);
        } finally {
            PacketVanilla.PACKET_POOL.add(framed);
        }
    }

    /**
     * Sets the encryption key and add the codecs to the pipeline.
     *
     * @param secretKey the secret key to use in the encryption
     * @throws IllegalStateException if encryption is already enabled for this connection
     */
    public void setEncryptionKey(SecretKey secretKey) {
        Check.stateCondition(encryptionContext != null, "Encryption is already enabled!");
        this.encryptionContext = new EncryptionContext(MojangCrypt.getCipher(1, secretKey), MojangCrypt.getCipher(2, secretKey));
    }

    /**
     * Enables compression and add a new codec to the pipeline.
     *
     * @throws IllegalStateException if encryption is already enabled for this connection
     */
    public void startCompression() {
        Check.stateCondition(compression(), "Compression is already enabled!");
        this.compressionStart = sentPacketCounter.get();
        final int threshold = MinecraftServer.getCompressionThreshold();
        Check.stateCondition(threshold == 0, "Compression cannot be enabled because the threshold is equal to 0");
        sendPacket(new SetCompressionPacket(threshold));
    }

    @Override
    public void sendPacket(SendablePacket packet) {
        if (!isOnline()) return;
        this.packetQueue.relaxedOffer(packet);
        unlockWriteThread();
    }

    @Override
    public void sendPackets(Collection<SendablePacket> packets) {
        if (!isOnline()) return;
        for (SendablePacket packet : packets) this.packetQueue.relaxedOffer(packet);
        unlockWriteThread();
    }

    // Requires ServerFlag.FASTER_SOCKET_WRITES
    private void unlockWriteThread() {
        if (!ServerFlag.FASTER_SOCKET_WRITES) return;
        if (!this.writeSignaled.compareAndExchange(false, true)) {
            LockSupport.unpark(writeThread);
        }
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * Changes the internal remote address field.
     * <p>
     * Mostly unsafe, used internally when interacting with a proxy.
     *
     * @param remoteAddress the new connection remote address
     */
    @ApiStatus.Internal
    public void setRemoteAddress(SocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    public SocketChannel getChannel() {
        return channel;
    }

    public @Nullable GameProfile gameProfile() {
        return gameProfile;
    }

    public void UNSAFE_setProfile(GameProfile gameProfile) {
        this.gameProfile = gameProfile;
    }

    /**
     * Retrieves the username received from the client during connection.
     * <p>
     * This value has not been checked and could be anything.
     *
     * @return the username given by the client, unchecked
     */
    public @Nullable String getLoginUsername() {
        return loginUsername;
    }

    /**
     * Sets the internal login username field.
     *
     * @param loginUsername the new login username field
     */
    public void UNSAFE_setLoginUsername(String loginUsername) {
        this.loginUsername = loginUsername;
    }

    /**
     * Gets the server address that the client used to connect.
     * <p>
     * WARNING: it is given by the client, it is possible for it to be wrong.
     *
     * @return the server address used
     */
    @Override
    public @Nullable String getServerAddress() {
        return serverAddress;
    }

    /**
     * Gets the server port that the client used to connect.
     * <p>
     * WARNING: it is given by the client, it is possible for it to be wrong.
     *
     * @return the server port used
     */
    @Override
    public int getServerPort() {
        return serverPort;
    }

    /**
     * Gets the protocol version of a client.
     *
     * @return protocol version of client.
     */
    @Override
    public int getProtocolVersion() {
        return protocolVersion;
    }

    /**
     * Used in {@link ClientHandshakePacket} to change the internal fields.
     *
     * @param serverAddress   the server address which the client used
     * @param serverPort      the server port which the client used
     * @param protocolVersion the protocol version which the client used
     */
    public void refreshServerInformation(@Nullable String serverAddress, int serverPort, int protocolVersion) {
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
        this.protocolVersion = protocolVersion;
    }

    public byte[] getNonce() {
        return nonce;
    }

    public void setNonce(byte[] nonce) {
        this.nonce = nonce;
    }

    private boolean writeSendable(NetworkBuffer buffer, SendablePacket sendable, boolean compressed) {
        final long start = buffer.writeIndex();
        final boolean result = writePacketSync(buffer, sendable, compressed);
        if (!result) return false;
        // ViaVersion: translate the frame(s) we just wrote to the client's version, before encryption.
        final ViaConnection viaConnection = this.viaConnection;
        if (viaConnection != null && viaConnection.active()) {
            try {
                transformClientboundRegion(buffer, start, compressed);
            } catch (IndexOutOfBoundsException exception) {
                buffer.writeIndex(start);
                return false;
            }
        }
        // Encrypt data
        final long length = buffer.writeIndex() - start;
        final EncryptionContext encryptionContext = this.encryptionContext;
        if (encryptionContext != null && length > 0) { // Encryption support
            buffer.cipher(encryptionContext.encrypt(), start, length);
        }
        return true;
    }

    // Translates every frame in [start, writeIndex) to the client's version: unwrap each to its
    // [packet id][payload] body (decompressing if needed), transform it, and re-frame.
    private void transformClientboundRegion(NetworkBuffer buffer, long start, boolean compressed) {
        final ViaConnection viaConnection = this.viaConnection;
        final long end = buffer.writeIndex();
        final int compressionThreshold = compressed ? MinecraftServer.getCompressionThreshold() : 0;
        final long savedReadIndex = buffer.readIndex();
        final NetworkBuffer scratch = PacketVanilla.PACKET_POOL.get();
        NetworkBuffer decompressed = null;
        try {
            long cursor = start;
            while (cursor < end) {
                buffer.readIndex(cursor);
                final int frameLength = buffer.read(NetworkBuffer.VAR_INT);
                final long payloadStart = buffer.readIndex();
                final long payloadEnd = payloadStart + frameLength;

                // Resolve the uncompressed [packet id][payload] body for this frame.
                final NetworkBuffer body;
                final long bodyFrom;
                final long bodyTo;
                if (compressionThreshold <= 0) {
                    body = buffer;
                    bodyFrom = payloadStart;
                    bodyTo = payloadEnd;
                } else {
                    final int dataLength = buffer.read(NetworkBuffer.VAR_INT);
                    final long afterDataLength = buffer.readIndex();
                    if (dataLength == 0) {
                        body = buffer;
                        bodyFrom = afterDataLength;
                        bodyTo = payloadEnd;
                    } else {
                        if (decompressed == null) decompressed = PacketVanilla.PACKET_POOL.get();
                        decompressed.clear();
                        if (decompressed.capacity() < dataLength) decompressed.resize(dataLength);
                        buffer.decompress(afterDataLength, payloadEnd - afterDataLength, decompressed);
                        body = decompressed;
                        bodyFrom = 0;
                        bodyTo = decompressed.writeIndex();
                    }
                }

                final ViaConnection.ClientboundResult result = viaConnection.transformClientbound(body, bodyFrom, bodyTo);
                // Packets Via injected while handling this frame (e.g. world_clock before finish_configuration)
                // must reach the client first, then the transformed frame itself.
                for (byte[] injected : result.injectedBefore()) {
                    final NetworkBuffer content = NetworkBuffer.wrap(injected, 0, injected.length, buffer.registries());
                    PacketWriting.writeFramedRaw(scratch, content, 0, injected.length, compressionThreshold);
                }
                final byte[] transformed = result.output();
                if (transformed != null) {
                    final NetworkBuffer content = NetworkBuffer.wrap(transformed, 0, transformed.length, buffer.registries());
                    PacketWriting.writeFramedRaw(scratch, content, 0, transformed.length, compressionThreshold);
                }
                cursor = payloadEnd;
            }
            // Replace the original framed region with the transformed frames.
            final long newLength = scratch.writeIndex();
            buffer.writeIndex(start);
            buffer.ensureWritable(newLength);
            NetworkBuffer.copy(scratch, 0, buffer, start, newLength);
            buffer.advanceWrite(newLength);
        } catch (java.util.zip.DataFormatException exception) {
            throw new RuntimeException("Failed to transform clientbound packet with ViaVersion", exception);
        } finally {
            buffer.readIndex(savedReadIndex);
            PacketVanilla.PACKET_POOL.add(scratch);
            if (decompressed != null) PacketVanilla.PACKET_POOL.add(decompressed);
        }
    }

    private boolean writePacketSync(NetworkBuffer buffer, SendablePacket packet, boolean compressed) {
        final Player player = getPlayer();
        final ConnectionState state = getServerState();
        if (player != null) {
            // Outgoing event
            if (outgoing.hasListener()) {
                final ServerPacket serverPacket = SendablePacket.extractServerPacket(state, packet);
                if (serverPacket != null) { // Events are not called for buffered packets
                    PlayerPacketOutEvent event = new PlayerPacketOutEvent(player, serverPacket);
                    outgoing.call(event);
                    if (event.isCancelled()) return true;
                }
            }
            // Translation
            if (ServerFlag.AUTOMATIC_COMPONENT_TRANSLATION && packet instanceof ServerPacket.ComponentHolding translatablePacket) {
                packet = translatablePacket.copyWithOperator(component ->
                        MinestomAdventure.COMPONENT_TRANSLATOR.apply(component, Objects.requireNonNullElseGet(player.getLocale(), MinestomAdventure::getDefaultLocale)));
            }
        }
        // Write packet
        final long start = buffer.writeIndex();
        final int compressionThreshold = compressed ? MinecraftServer.getCompressionThreshold() : 0;
        try {
            return switch (packet) {
                case ServerPacket serverPacket -> {
                    var nextState = PacketVanilla.nextServerState(serverPacket, state);
                    if (nextState != state) setServerState(nextState);

                    PacketWriting.writeFramedPacket(buffer, state, serverPacket, compressionThreshold);
                    yield true;
                }
                case FramedPacket framedPacket -> {
                    final NetworkBuffer body = framedPacket.body();
                    yield writeBuffer(buffer, body, 0, body.capacity());
                }
                case CachedPacket cachedPacket -> {
                    final NetworkBuffer body = cachedPacket.body(state);
                    if (body != null) {
                        yield writeBuffer(buffer, body, 0, body.capacity());
                    } else {
                        PacketWriting.writeFramedPacket(buffer, state, cachedPacket.packet(state), compressionThreshold);
                        yield true;
                    }
                }
                case LazyPacket lazyPacket -> {
                    PacketWriting.writeFramedPacket(buffer, state, lazyPacket.packet(), compressionThreshold);
                    yield true;
                }
                case BufferedPacket bufferedPacket -> {
                    final NetworkBuffer rawBuffer = bufferedPacket.buffer();
                    final long index = bufferedPacket.index();
                    final long length = bufferedPacket.length();
                    yield writeBuffer(buffer, rawBuffer, index, length);
                }
            };
        } catch (IndexOutOfBoundsException exception) {
            buffer.writeIndex(start);
            return false;
        }
    }

    private boolean writeBuffer(NetworkBuffer buffer, NetworkBuffer body, long index, long length) {
        if (buffer.writableBytes() < length) {
            // Not enough space in the buffer
            return false;
        }
        NetworkBuffer.copy(body, index, buffer, buffer.writeIndex(), length);
        buffer.advanceWrite(length);
        return true;
    }

    private NetworkBuffer writeLeftover = null;

    public void flushSync() throws IOException {
        // Write leftover if any
        NetworkBuffer leftover = this.writeLeftover;
        if (leftover != null) {
            final boolean success = leftover.writeChannel(channel);
            if (success) {
                this.writeLeftover = null;
                PacketVanilla.PACKET_POOL.add(leftover);
            } else {
                // Failed to write the whole leftover, try again next flush.
                // Returning straight away would spin the write loop at 100% CPU for as long as the client
                // stays behind. These loops run on virtual threads, so a handful of slow clients would pin
                // every carrier and starve the readers of every other connection.
                backoff();
                return;
            }
        }
        // Consume queued packets
        var packetQueue = this.packetQueue;
        if (packetQueue.isEmpty() && injectedClientbound.isEmpty()) {
            if (!ServerFlag.FASTER_SOCKET_WRITES) {
                try {
                    // Can probably be improved by waking up at the end of the tick
                    // But this work well enough and without additional state.
                    Thread.sleep(1000 / ServerFlag.SERVER_TICKS_PER_SECOND / 2);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            } else {
                assert this.writeThread == Thread.currentThread(): "writeThread should be the current thread";
                this.writeSignaled.set(false);
                if (!isOnline()) return; // already offline, don't park
                LockSupport.park(this);
                if (packetQueue.isEmpty() && injectedClientbound.isEmpty()) return; // woken by disconnect signal
            }
        }
        if (!channel.isConnected()) throw new EOFException("Channel is closed");
        NetworkBuffer buffer = PacketVanilla.PACKET_POOL.get();
        // ViaVersion-injected packets first (already in the client's version).
        writeInjectedClientbound(buffer);
        // Write to buffer
        PacketWriting.writeQueue(buffer, packetQueue, 1, (b, packet) -> {
            final boolean compressed = sentPacketCounter.get() > compressionStart;
            final boolean success = writeSendable(b, packet, compressed);
            if (success) sentPacketCounter.getAndIncrement();
            return success;
        });
        if (buffer.writeIndex() == 0 && !packetQueue.isEmpty()) {
            // Nothing could be written even though packets are queued, which means the head packet does not
            // fit in a max-size buffer and never will. It stays queued (dropping it silently would hide a
            // protocol bug), but we must not spin on it.
            backoff();
        }
        // Write to channel
        final boolean success = buffer.writeChannel(channel);
        // Keep the buffer if not fully written
        if (success) PacketVanilla.PACKET_POOL.add(buffer);
        else this.writeLeftover = buffer;
    }

    // Yields the carrier thread for a moment instead of retrying a write that cannot make progress yet.
    private void backoff() {
        LockSupport.parkNanos(this, WRITE_BACKOFF_NANOS);
    }

    private void writeInjectedClientbound(NetworkBuffer buffer) {
        byte[] idAndData;
        while ((idAndData = injectedClientbound.poll()) != null) {
            final long start = buffer.writeIndex();
            final boolean compressed = sentPacketCounter.get() > compressionStart;
            final int compressionThreshold = compressed ? MinecraftServer.getCompressionThreshold() : 0;
            buffer.ensureWritable(idAndData.length + 15); // headroom for length + data-length + id varints
            final NetworkBuffer content = NetworkBuffer.wrap(idAndData, 0, idAndData.length);
            PacketWriting.writeFramedRaw(buffer, content, 0, idAndData.length, compressionThreshold);
            final long length = buffer.writeIndex() - start;
            final EncryptionContext encryptionContext = this.encryptionContext;
            if (encryptionContext != null && length > 0) {
                buffer.cipher(encryptionContext.encrypt(), start, length);
            }
            sentPacketCounter.getAndIncrement();
        }
    }

    @Override
    public void disconnect() {
        super.disconnect();
        LockSupport.unpark(writeThread);
    }

    /**
     * Half-closes the socket once the last packet has been flushed.
     * <p>
     * A plain {@link SocketChannel#close()} here is answered with RST rather than FIN whenever the client
     * still has bytes sitting unread in our receive queue, and RST discards the send buffer - so the
     * disconnect packet we just wrote never reaches the client, which reports a bare connection reset.
     * Sending FIN first lets the client read the reason; {@link #closeChannel()} finishes the job once the
     * client answers, or once the linger deadline expires.
     */
    public void finishOutput() throws IOException {
        if (channelClosed.get() || !channel.isOpen()) return;
        try {
            channel.shutdownOutput();
        } catch (IOException ignored) {
            // Peer already went away, nothing graceful left to do.
            closeChannel();
        }
    }

    /**
     * Closes the socket and releases the per-connection ViaVersion state. Safe to call more than once.
     */
    public void closeChannel() {
        if (!channelClosed.compareAndSet(false, true)) return;
        try {
            channel.close();
        } catch (IOException ignored) {
            // Already gone.
        } finally {
            final ViaConnection viaConnection = this.viaConnection;
            if (viaConnection != null) viaConnection.close();
        }
    }

    public Thread readThread() {
        return readThread;
    }

    public Thread writeThread() {
        return writeThread;
    }

    public void clearPacketQueue() {
        packetQueue.clear();
    }

    record EncryptionContext(Cipher encrypt, Cipher decrypt) {
    }
    public boolean isHaProxy() {
        return haProxy;
    }
}
