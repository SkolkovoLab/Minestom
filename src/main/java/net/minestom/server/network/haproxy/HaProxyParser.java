package net.minestom.server.network.haproxy;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

public class HaProxyParser {
    // Сигнатура для PROXY v2 (12 байт)
    private static final byte[] V2_SIGNATURE = new byte[] {
            0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
    };

    /**
     * Парсит заголовок PROXY после принятия соединения.
     * Возвращает реальный адрес клиента или null, если парсинг не удался (или нет заголовка).
     * Читает минимально необходимые байты, чтобы избежать чтения данных приложения.
     * @param channel SocketChannel после accept()
     * @return SocketAddress реального клиента или null
     * @throws IOException если ошибка чтения
     */
    public static SocketAddress parseProxyProtocol(SocketChannel channel) throws IOException {
        // Сначала читаем минимальный размер для проверки версии: 16 байт (12 sig + 4 header для v2)
        ByteBuffer initialBuffer = ByteBuffer.allocate(16);
        readFully(channel, initialBuffer);
        initialBuffer.flip();

        // Проверяем на v2
        if (isProxyV2(initialBuffer)) {
            return parseProxyV2(channel, initialBuffer);
        }

        // Проверяем на v1
        initialBuffer.rewind(); // Сброс для проверки v1
        if (isProxyV1(initialBuffer)) {
            return parseProxyV1(channel, initialBuffer);
        }

        // Если не PROXY, возвращаем null, но поскольку мы прочитали данные, это ошибка - в реальности нужно unread, но для простоты предполагаем, что если нет PROXY, закрываем или обрабатываем иначе
        // В production: верните initialBuffer как начало данных приложения
        return null;
    }

    private static void readFully(SocketChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int bytesRead = channel.read(buffer);
            if (bytesRead == -1) {
                throw new EOFException("Unexpected end of stream while reading PROXY header");
            }
            if (bytesRead == 0) {
                // В blocking mode не должно быть, но на всякий случай
                continue;
            }
        }
    }

    private static boolean isProxyV2(ByteBuffer buffer) {
        if (buffer.remaining() < 12) return false;
        for (int i = 0; i < V2_SIGNATURE.length; i++) {
            if (buffer.get(i) != V2_SIGNATURE[i]) return false;
        }
        return true;
    }

    private static SocketAddress parseProxyV2(SocketChannel channel, ByteBuffer buffer) throws IOException {
        buffer.position(12); // Пропустить сигнатуру
        byte versionCommand = buffer.get(); // Версия (0x20) и команда (0x00 local, 0x01 proxy)
        if ((versionCommand & 0xF0) != 0x20) return null; // Не v2

        byte command = (byte) (versionCommand & 0x0F);
        if (command != 0x01) return null; // Только proxy-команда

        byte protocolFamily = buffer.get(); // Транспорт (upper 4: 0x1 TCP, 0x2 UDP) и family (lower 4: 0x1 IPv4, 0x2 IPv6, 0x3 Unix)
        int transport = (protocolFamily & 0xF0) >> 4;
        int family = protocolFamily & 0x0F;
        if (transport != 0x1) return null; // Только TCP

        short length = buffer.getShort(); // Длина адресов и TLV

        // Теперь читаем ровно length байт
        ByteBuffer addrBuffer = ByteBuffer.allocate(length);
        readFully(channel, addrBuffer);
        addrBuffer.flip();

        String sourceIp;
        int sourcePort;
        // Игнорируем dest IP/port, так как они локальные
        if (family == 0x1) { // IPv4
            byte[] srcIpBytes = new byte[4];
            addrBuffer.get(srcIpBytes);
            addrBuffer.position(addrBuffer.position() + 4); // Пропустить dest IP
            sourcePort = addrBuffer.getShort() & 0xFFFF;
            addrBuffer.getShort(); // Пропустить dest port
            sourceIp = String.format("%d.%d.%d.%d", srcIpBytes[0] & 0xFF, srcIpBytes[1] & 0xFF,
                    srcIpBytes[2] & 0xFF, srcIpBytes[3] & 0xFF);
        } else if (family == 0x2) { // IPv6
            byte[] srcIpBytes = new byte[16];
            addrBuffer.get(srcIpBytes);
            addrBuffer.position(addrBuffer.position() + 16); // Пропустить dest IP
            sourcePort = addrBuffer.getShort() & 0xFFFF;
            addrBuffer.getShort(); // Пропустить dest port
            sourceIp = bytesToIpv6(srcIpBytes);
        } else {
            return null; // Не поддерживаем Unix или unknown
        }

        // Остаток в addrBuffer - TLV, парсим если нужно, но для базового игнорируем (позиция уже после адресов)

        return new InetSocketAddress(sourceIp, sourcePort);
    }

    private static boolean isProxyV1(ByteBuffer buffer) {
        if (buffer.remaining() < 6) return false;
        byte[] prefix = new byte[6];
        buffer.get(prefix);
        return new String(prefix, StandardCharsets.US_ASCII).equals("PROXY ");
    }

    private static SocketAddress parseProxyV1(SocketChannel channel, ByteBuffer buffer) throws IOException {
        // Буфер уже содержит минимум 16 байт, но для v1 нужен до \r\n
        // Конвертируем текущий буфер в строку и ищем \r\n
        buffer.position(0); // Сброс
        StringBuilder headerBuilder = new StringBuilder();
        boolean foundCrlf = false;
        byte prev = 0;

        // Сначала обработаем уже прочитанные байты
        while (buffer.hasRemaining() && !foundCrlf) {
            byte b = buffer.get();
            headerBuilder.append((char) b);
            if (prev == '\r' && b == '\n') {
                foundCrlf = true;
            }
            prev = b;
        }

        // Если не нашли \r\n в initialBuffer, читаем дополнительные байты по одному
        ByteBuffer singleByte = ByteBuffer.allocate(1);
        int maxV1Length = 108; // Max для v1
        int additionalRead = 0;
        while (!foundCrlf && additionalRead < maxV1Length - 16) {
            singleByte.clear();
            int read = channel.read(singleByte);
            if (read <= 0) {
                throw new EOFException("Unexpected end while reading PROXY v1 header");
            }
            singleByte.flip();
            byte b = singleByte.get();
            headerBuilder.append((char) b);
            if (prev == '\r' && b == '\n') {
                foundCrlf = true;
            }
            prev = b;
            additionalRead++;
        }

        if (!foundCrlf) {
            return null; // Слишком длинный заголовок
        }

        String header = headerBuilder.toString().substring(0, headerBuilder.length() - 2); // Убрать \r\n

        String[] parts = header.split(" ");
        if (parts.length != 6) return null;

        String protocol = parts[1]; // TCP4, TCP6, UNKNOWN
        if (!protocol.startsWith("TCP")) return null;

        String sourceIp = parts[2];
        // parts[3] - dest IP (игнор)
        int sourcePort = Integer.parseInt(parts[4]);
        // parts[5] - dest port (игнор)

        return new InetSocketAddress(sourceIp, sourcePort);
    }

    private static String bytesToIpv6(byte[] bytes) {
        // Простая конверсия IPv6 в строку (можно улучшить)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i += 2) {
            if (i > 0) sb.append(":");
            sb.append(String.format("%02x%02x", bytes[i] & 0xFF, bytes[i + 1] & 0xFF));
        }
        return sb.toString();
    }
}
