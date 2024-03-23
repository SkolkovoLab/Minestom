package net.minestom.scratch.tools;

import it.unimi.dsi.fastutil.ints.IntArrays;
import net.minestom.server.ServerFlag;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.PacketProcessor;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.configuration.ClientFinishConfigurationPacket;
import net.minestom.server.network.packet.client.handshake.ClientHandshakePacket;
import net.minestom.server.network.packet.client.login.ClientLoginAcknowledgedPacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.utils.ObjectPool;
import net.minestom.server.utils.PacketUtils;
import net.minestom.server.utils.binary.BinaryBuffer;
import org.jctools.queues.MpmcUnboundedXaddArrayQueue;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.zip.DataFormatException;

public final class ScratchNetworkTools {
    private static final PacketProcessor PACKET_PROCESSOR = new PacketProcessor(null);
    private static final ObjectPool<ByteBuffer> PACKET_POOL = new ObjectPool<>(() -> ByteBuffer.allocateDirect(ServerFlag.POOLED_BUFFER_SIZE), ByteBuffer::clear);

    public static void readPackets(ByteBuffer buffer,
                                   AtomicReference<ConnectionState> stateRef,
                                   Consumer<ClientPacket> consumer) {
        var b = BinaryBuffer.wrap(buffer);
        b.readerOffset(buffer.position());
        b.writerOffset(buffer.limit());
        try {
            PacketUtils.readPackets(b, false,
                    (id, payload) -> {
                        final ConnectionState state = stateRef.get();
                        final ClientPacket packet = PACKET_PROCESSOR.create(state, id, payload);
                        consumer.accept(packet);
                    });
            buffer.position(b.readerOffset());
        } catch (DataFormatException e) {
            throw new RuntimeException(e);
        }
    }

    public static void write(NetworkContext.Packet packet, ByteBuffer buffer, Predicate<ByteBuffer> fullCallback) {
        final int checkLength = buffer.limit() / 2;
        if (packet instanceof NetworkContext.Packet.PacketIdPair packetPair) {
            final ServerPacket packetLoop = packetPair.packet;
            final int idLoop = packetPair.id;
            if (buffer.position() >= checkLength) {
                if (!fullCallback.test(buffer)) return;
            }
            PacketUtils.writeFramedPacket(buffer, idLoop, packetLoop, 0);
        } else if (packet instanceof NetworkContext.Packet.PlayList playList) {
            final List<ServerPacket.Play> packets = playList.packets();
            final int[] exception = playList.exception();
            int index = 0;
            for (ServerPacket.Play packetLoop : packets) {
                final int idLoop = packetLoop.playId();
                if (exception.length > 0 && Arrays.binarySearch(exception, index++) >= 0) continue;
                if (buffer.position() >= checkLength) {
                    if (!fullCallback.test(buffer)) return;
                }
                PacketUtils.writeFramedPacket(buffer, idLoop, packetLoop, 0);
            }
        } else {
            throw new IllegalStateException("Unexpected packet type: " + packet);
        }
    }

    public static ConnectionState nextState(ClientPacket packet, ConnectionState currentState) {
        return switch (packet) {
            case ClientHandshakePacket handshakePacket -> switch (handshakePacket.intent()) {
                case 1 -> ConnectionState.STATUS;
                case 2 -> ConnectionState.LOGIN;
                default -> throw new IllegalStateException("Unexpected value: " + handshakePacket.intent());
            };
            case ClientLoginAcknowledgedPacket ignored -> ConnectionState.CONFIGURATION;
            case ClientFinishConfigurationPacket ignored -> ConnectionState.PLAY;
            default -> currentState;
        };
    }

    public interface NetworkContext {

        boolean read(Function<ByteBuffer, Integer> reader, Consumer<ClientPacket> consumer);

        void write(Packet packet);

        void flush();

        ConnectionState state();

        default void write(ServerPacket packet) {
            write(new Packet.PacketIdPair(packet, packet.getId(state())));
        }

        default void writePlays(List<ServerPacket.Play> packets) {
            write(new NetworkContext.Packet.PlayList(packets));
        }

        sealed interface Packet {
            record PacketIdPair(ServerPacket packet, int id) implements Packet {
            }

            record PlayList(List<ServerPacket.Play> packets, int[] exception) implements Packet {
                public PlayList {
                    packets = List.copyOf(packets);
                }

                public PlayList(List<ServerPacket.Play> packets) {
                    this(packets, IntArrays.EMPTY_ARRAY);
                }
            }
        }

        final class Async implements NetworkContext {
            final AtomicReference<ConnectionState> stateRef = new AtomicReference<>(ConnectionState.HANDSHAKE);
            final MpmcUnboundedXaddArrayQueue<Packet> packetWriteQueue = new MpmcUnboundedXaddArrayQueue<>(1024);

            final ReentrantLock writeLock = new ReentrantLock();
            final Condition writeCondition = writeLock.newCondition();

            @Override
            public boolean read(Function<ByteBuffer, Integer> reader, Consumer<ClientPacket> consumer) {
                try (ObjectPool<ByteBuffer>.Holder hold = PACKET_POOL.hold()) {
                    ByteBuffer buffer = hold.get();
                    while (buffer.hasRemaining()) {
                        final int length = reader.apply(buffer);
                        if (length == -1) return false;
                        readPackets(buffer.flip(), stateRef, clientPacket -> {
                            stateRef.set(nextState(clientPacket, stateRef.get()));
                            consumer.accept(clientPacket);
                        });
                    }
                }
                return true;
            }

            public boolean write(Function<ByteBuffer, Integer> writer) {
                try {
                    this.writeLock.lock();
                    this.writeCondition.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } finally {
                    this.writeLock.unlock();
                }

                AtomicBoolean result = new AtomicBoolean(true);
                try (ObjectPool<ByteBuffer>.Holder hold = PACKET_POOL.hold()) {
                    ByteBuffer buffer = hold.get();
                    Packet packet;
                    while ((packet = packetWriteQueue.poll()) != null) {
                        ScratchNetworkTools.write(packet, buffer, b -> {
                            final int length = writer.apply(b);
                            b.compact();
                            if (length == -1) {
                                result.setPlain(false);
                                return false;
                            }
                            return true;
                        });
                    }
                    while (buffer.hasRemaining()) {
                        final int length = writer.apply(buffer);
                        if (length == -1) {
                            result.setPlain(false);
                            break;
                        }
                    }
                }
                return result.getPlain();
            }

            @Override
            public void write(Packet packet) {
                this.packetWriteQueue.add(packet);
            }

            @Override
            public void flush() {
                try {
                    this.writeLock.lock();
                    this.writeCondition.signal();
                } finally {
                    this.writeLock.unlock();
                }
            }

            @Override
            public ConnectionState state() {
                return stateRef.get();
            }
        }

        final class Sync implements NetworkContext {
            final AtomicReference<ConnectionState> stateRef = new AtomicReference<>(ConnectionState.HANDSHAKE);
            final Predicate<ByteBuffer> writer;
            final ArrayDeque<Packet> packetWriteQueue = new ArrayDeque<>();

            public Sync(Predicate<ByteBuffer> writer) {
                this.writer = writer;
            }

            @Override
            public boolean read(Function<ByteBuffer, Integer> reader, Consumer<ClientPacket> consumer) {
                try (ObjectPool<ByteBuffer>.Holder hold = PACKET_POOL.hold()) {
                    ByteBuffer buffer = hold.get();
                    while (buffer.hasRemaining()) {
                        final int length = reader.apply(buffer);
                        if (length == -1) return false;
                        readPackets(buffer.flip(), stateRef, clientPacket -> {
                            stateRef.set(nextState(clientPacket, stateRef.get()));
                            consumer.accept(clientPacket);
                        });
                    }
                }
                return true;
            }

            @Override
            public void write(Packet packet) {
                this.packetWriteQueue.add(packet);
            }

            @Override
            public void flush() {
                try (ObjectPool<ByteBuffer>.Holder hold = PACKET_POOL.hold()) {
                    ByteBuffer buffer = hold.get();
                    Packet packet;
                    while ((packet = packetWriteQueue.poll()) != null) {
                        ScratchNetworkTools.write(packet, buffer, b -> {
                            final boolean result = writer.test(b);
                            b.compact();
                            return result;
                        });
                    }
                    while (buffer.hasRemaining() && writer.test(buffer)) ;
                }
            }

            @Override
            public ConnectionState state() {
                return stateRef.get();
            }
        }
    }
}
