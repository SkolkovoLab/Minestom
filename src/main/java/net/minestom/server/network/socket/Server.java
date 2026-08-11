package net.minestom.server.network.socket;

import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.network.haproxy.HaProxyParser;
import net.minestom.server.network.packet.PacketParser;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.player.PlayerSocketConnection;
import net.minestom.server.utils.validate.Check;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.net.*;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static net.minestom.server.ServerFlag.HAPROXY_ENABLED;

public final class Server {
    private static final Logger log = LoggerFactory.getLogger(Server.class);
    // How long a half-closed connection may wait for the client to answer our FIN before we close anyway.
    private static final long CLOSE_LINGER_MILLIS = Long.getLong("minestom.close-linger-millis", 10_000);

    private volatile boolean stop;

    // Bounds the half-close above; a client that never answers the FIN must not pin a socket forever.
    private final ScheduledExecutorService closeWatchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Ms-Socket-Close-Watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private final PacketParser.Client packetParser;

    private ServerSocketChannel serverSocket;
    private SocketAddress socketAddress;
    private String address;
    private int port;

    public Server(PacketParser.Client packetParser) {
        this.packetParser = packetParser;
    }

    public Server() {
        this(PacketVanilla.CLIENT_PACKET_PARSER);
    }

    @ApiStatus.Internal
    public void init(SocketAddress address) throws IOException {
        ProtocolFamily family;
        switch (address) {
            case InetSocketAddress inetSocketAddress -> {
                this.address = inetSocketAddress.getHostString();
                this.port = inetSocketAddress.getPort();
                family = inetSocketAddress.getAddress().getAddress().length == 4 ? StandardProtocolFamily.INET : StandardProtocolFamily.INET6;
            }
            case UnixDomainSocketAddress unixDomainSocketAddress -> {
                this.address = "unix://" + unixDomainSocketAddress.getPath();
                this.port = 0;
                family = StandardProtocolFamily.UNIX;
            }
            default ->
                    throw new IllegalArgumentException("Address must be an InetSocketAddress or a UnixDomainSocketAddress");
        }

        ServerSocketChannel server = ServerSocketChannel.open(family);
        server.bind(address);
        this.serverSocket = server;
        this.socketAddress = address;

        if (address instanceof InetSocketAddress && port == 0) {
            port = server.socket().getLocalPort();
        }
    }

    @ApiStatus.Internal
    public void start() {
        // Use named thread builders for logging
        var readBuilder = Thread.ofVirtual().name("Ms-Socket-Reader-", 0);
        var writeBuilder = Thread.ofVirtual().name("Ms-Socket-Writer-", 0);
        var initBuilder = Thread.ofVirtual().name("Ms-Socket-Parser", 0);
        Thread.ofVirtual().name("Ms-Socket-Server").start(() -> {
            while (!stop) {
                try {
                    final SocketChannel client = serverSocket.accept();
                    configureSocket(client);
                    initBuilder.start(() -> {
                        try {
                            boolean haproxy = false;
                            SocketAddress remoteAddress = client.getRemoteAddress();
                            if (HAPROXY_ENABLED) {
                                remoteAddress = HaProxyParser.parseProxyProtocol(client);
                                if (remoteAddress == null) {
                                    client.close();
                                    return;
                                }
                                haproxy = true;
                            }
                            AtomicReference<PlayerSocketConnection> reference = new AtomicReference<>(null);
                            Thread readThread = readBuilder.unstarted(() -> playerReadLoop(reference.get()));
                            Thread writeThread = writeBuilder.unstarted(() -> playerWriteLoop(reference.get()));
                            PlayerSocketConnection connection = new PlayerSocketConnection(client, remoteAddress, readThread, writeThread, haproxy);
                            reference.set(connection);
                            readThread.start();
                            writeThread.start();
                        } catch (AsynchronousCloseException ignored) {
                            // We are exiting, bye bye!
                        } catch (IOException e) {
                            log.error("Error while init player", e);
                            try {
                                client.close();
                            } catch (IOException ignored) {
                            }
                        }
                    });
                } catch (AsynchronousCloseException ignored) {
                    // We are exiting, bye bye!
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private void configureSocket(SocketChannel channel) throws IOException {
        if (channel.getLocalAddress() instanceof InetSocketAddress) {
            Socket socket = channel.socket();
            socket.setSendBufferSize(ServerFlag.SOCKET_SEND_BUFFER_SIZE);
            socket.setReceiveBufferSize(ServerFlag.SOCKET_RECEIVE_BUFFER_SIZE);
            socket.setTcpNoDelay(ServerFlag.SOCKET_NO_DELAY);
            socket.setSoTimeout(ServerFlag.SOCKET_TIMEOUT);
        }
    }

    private void playerReadLoop(PlayerSocketConnection connection) {
        Check.notNull(connection, "connection cannot be null");
        try {
            while (!stop) {
                try {
                    // Read & process packets
                    connection.read(packetParser);
                } catch (ClosedChannelException ignored) {
                    break; // We closed the socket during read, just exit.
                } catch (EOFException e) {
                    connection.disconnect();
                    break;
                } catch (Throwable e) {
                    boolean isExpected = e instanceof SocketException && "Connection reset".equals(e.getMessage());
                    if (!isExpected) MinecraftServer.getExceptionManager().handleException(e);
                    connection.disconnect();
                    break;
                }
            }
        } finally {
            // The reader is the only thread that drains the receive queue, so it is the only one that can
            // close without leaving unread bytes behind. Closing with bytes still queued makes the kernel
            // answer with RST instead of FIN, which also discards the send buffer holding the disconnect
            // packet - the client then reports a bare connection reset with no reason.
            connection.closeChannel();
        }
    }

    private void playerWriteLoop(PlayerSocketConnection connection) {
        Check.notNull(connection, "connection cannot be null");
        while (!stop) {
            try {
                connection.flushSync();
            } catch (ClosedChannelException ignored) {
                break; // We closed the socket during write, just exit.
            } catch (EOFException e) {
                connection.disconnect();
                break;
            } catch (Throwable e) {
                boolean isExpected = e instanceof IOException && "Broken pipe".equals(e.getMessage());
                if (!isExpected) MinecraftServer.getExceptionManager().handleException(e);

                connection.disconnect();
                break;
            }
            if (!connection.isOnline()) {
                try {
                    connection.flushSync();
                    // Half-close only: FIN lets the client read everything we just flushed, including the
                    // disconnect reason. The reader closes for real once the client answers.
                    connection.finishOutput();
                    closeWatchdog.schedule(connection::closeChannel, CLOSE_LINGER_MILLIS, TimeUnit.MILLISECONDS);
                    break;
                } catch (IOException e) {
                    connection.closeChannel();
                    break;
                }
            }
        }
        connection.clearPacketQueue();
    }

    public boolean isOpen() {
        return !stop;
    }

    public void stop() {
        this.stop = true;
        this.closeWatchdog.shutdownNow();
        try {
            if (serverSocket != null) {
                this.serverSocket.close();
            }

            if (socketAddress instanceof UnixDomainSocketAddress unixDomainSocketAddress) {
                Files.deleteIfExists(unixDomainSocketAddress.getPath());
            }
        } catch (IOException e) {
            MinecraftServer.getExceptionManager().handleException(e);
        }
    }

    @ApiStatus.Internal
    public PacketParser.Client packetParser() {
        return packetParser;
    }

    public SocketAddress socketAddress() {
        return socketAddress;
    }

    public String getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }
}
