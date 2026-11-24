package com.example.ahakey.diagnostics;

import com.example.ahakey.protocol.AhaKeyProtocol;
import com.example.ahakey.protocol.BleTcpPacket;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Connection diagnostics for the BLE/USB path: app log lines, raw frames sent to and
 * received from the keyboard, and the BLE driver's own log. Kept in memory for the
 * connection log window and appended to a daily file so earlier sessions stay readable.
 */
public final class ConnectionLog {
    public enum Source { APP, BRIDGE, TX, RX }
    public enum Level { DEBUG, INFO, WARN, ERROR }

    public record Entry(Instant time, Source source, Level level, String transport,
                        String message, byte[] raw) {
        public boolean isFrame() { return source == Source.TX || source == Source.RX; }
    }

    private static final int CAPACITY = 20_000;
    private static final DateTimeFormatter FILE_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final ConnectionLog INSTANCE = new ConnectionLog();

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final List<Consumer<Entry>> listeners = new CopyOnWriteArrayList<>();
    private BufferedWriter writer;
    private LocalDate writerDate;
    private volatile boolean started;
    private static volatile java.util.function.Supplier<String> statusProvider = () -> "";

    private ConnectionLog() {}

    public static ConnectionLog get() { return INSTANCE; }

    /** Starts the logback capture and the BLE driver log tail once per process. */
    public static synchronized void start() {
        if (INSTANCE.started) return;
        INSTANCE.started = true;
        LogbackCapture.install();
        BridgeLogTailer.start();
    }

    public static void setStatusProvider(java.util.function.Supplier<String> provider) {
        statusProvider = provider == null ? () -> "" : provider;
    }

    public static String statusSummary() {
        try { return statusProvider.get(); } catch (RuntimeException e) { return ""; }
    }

    /** Folder of the daily connection log files (shared with the BLE driver). */
    public static Path logDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = localAppData != null && !localAppData.isBlank()
            ? Path.of(localAppData, "AhaKeyStudio")
            : Path.of(System.getProperty("user.home"), ".ahakey");
        return base.resolve("logs");
    }

    public static Path todayFile() {
        return logDirectory().resolve("connection-" + LocalDate.now() + ".log");
    }

    public static void tx(String transport, byte tcpType, byte[] data) {
        INSTANCE.add(new Entry(Instant.now(), Source.TX, Level.DEBUG, transport,
            describe(transport, tcpType, data, true), copy(data)));
    }

    public static void rx(String transport, byte tcpType, byte[] data) {
        INSTANCE.add(new Entry(Instant.now(), Source.RX, Level.DEBUG, transport,
            describe(transport, tcpType, data, false), copy(data)));
    }

    public static void event(Level level, String message) {
        INSTANCE.add(new Entry(Instant.now(), Source.APP, level, "", message, null));
    }

    void add(Entry entry) {
        synchronized (entries) {
            if (entries.size() >= CAPACITY) entries.removeFirst();
            entries.addLast(entry);
            writeToFile(entry);
        }
        for (Consumer<Entry> listener : listeners) {
            try { listener.accept(entry); } catch (RuntimeException ignored) { }
        }
    }

    public List<Entry> snapshot() {
        synchronized (entries) { return new ArrayList<>(entries); }
    }

    public void clear() {
        synchronized (entries) { entries.clear(); }
    }

    public void addListener(Consumer<Entry> listener) { listeners.add(listener); }

    public void removeListener(Consumer<Entry> listener) { listeners.remove(listener); }

    public static String format(Entry entry) {
        StringBuilder line = new StringBuilder(FILE_TIME.format(entry.time()))
            .append(' ').append(entry.source()).append(' ').append(entry.level());
        if (!entry.transport().isEmpty()) line.append(' ').append(entry.transport());
        line.append(' ').append(entry.message().replace("\r", "").replace("\n", " | "));
        if (entry.raw() != null) line.append(" | ").append(hex(entry.raw()));
        return line.toString();
    }

    private void writeToFile(Entry entry) {
        // The BLE driver writes its own file; repeating its lines here would double them.
        if (entry.source() == Source.BRIDGE || !started) return;
        try {
            LocalDate today = LocalDate.now();
            if (writer == null || !today.equals(writerDate)) {
                if (writer != null) writer.close();
                Files.createDirectories(logDirectory());
                writer = Files.newBufferedWriter(todayFile(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                writerDate = today;
            }
            writer.write(format(entry));
            writer.newLine();
            writer.flush();
        } catch (IOException ignored) {
            writer = null;
        }
    }

    // ------------------------------------------------------------------ decoding

    private static final Map<Byte, String> TCP_TYPES = Map.of(
        BleTcpPacket.WRITE_DATA, "데이터 쓰기",
        BleTcpPacket.WRITE_COMMAND, "명령 쓰기",
        BleTcpPacket.QUERY_BLE_STATUS, "블루투스 상태 확인",
        BleTcpPacket.QUERY_DEVICE_INFO, "키보드 정보 확인",
        BleTcpPacket.BLE_NOTIFY, "키보드 알림",
        BleTcpPacket.BLE_STATUS_RESP, "블루투스 상태 응답",
        BleTcpPacket.DEVICE_INFO_RESP, "키보드 정보 응답");

    private static final Map<Byte, String> COMMANDS = Map.ofEntries(
        Map.entry(AhaKeyProtocol.CMD_QUERY_STATUS, "상태 조회"),
        Map.entry(AhaKeyProtocol.CMD_CHANGE_NAME, "이름 변경"),
        Map.entry(AhaKeyProtocol.CMD_CHANGE_APPEARANCE, "외형 변경"),
        Map.entry(AhaKeyProtocol.CMD_SAVE_CONFIG, "설정 저장"),
        Map.entry(AhaKeyProtocol.CMD_UPDATE_CUSTOM_KEY, "키 설정"),
        Map.entry(AhaKeyProtocol.CMD_PREPARE_WRITE, "쓰기 준비"),
        Map.entry(AhaKeyProtocol.CMD_WRITE_RESULT, "쓰기 결과"),
        Map.entry(AhaKeyProtocol.CMD_UPDATE_PIC, "이미지 쓰기"),
        Map.entry(AhaKeyProtocol.CMD_READ_PIC_STATE, "이미지 상태"),
        Map.entry(AhaKeyProtocol.CMD_SET_AI_LIGHT_CONFIG, "AI 조명 설정"),
        Map.entry(AhaKeyProtocol.CMD_SET_LIGHT_BRIGHTNESS, "밝기 설정"),
        Map.entry(AhaKeyProtocol.CMD_LEGACY_CONFLICT, "구버전 명령"),
        Map.entry(AhaKeyProtocol.CMD_UPDATE_STATE, "AI 상태 전송"),
        Map.entry(AhaKeyProtocol.CMD_SET_LIGHT_EFFECT, "조명 효과"),
        Map.entry(AhaKeyProtocol.CMD_SET_WORK_MODE, "모드 변경"),
        Map.entry(AhaKeyProtocol.CMD_SET_AI_OLED_CONFIG, "OLED 설정"),
        Map.entry(AhaKeyProtocol.CMD_READ_AI_OLED_CONFIG, "OLED 설정 읽기"),
        Map.entry(AhaKeyProtocol.CMD_STANDBY_TIMEOUT, "자동 대기"),
        Map.entry(AhaKeyProtocol.CMD_FACTORY_RESET, "초기화"),
        Map.entry(AhaKeyProtocol.CMD_VOICE_KEY_CONFIG, "음성 키 설정"),
        Map.entry(AhaKeyProtocol.CMD_TASK_LIGHT_MODE, "작업 조명 모드"),
        Map.entry(AhaKeyProtocol.CMD_TASK_SLOT_UPDATE, "작업 칸 갱신"),
        Map.entry(AhaKeyProtocol.CMD_TASK_HEARTBEAT, "작업 신호"),
        Map.entry(AhaKeyProtocol.CMD_MODE_SYNC, "모드 동기화"),
        Map.entry(AhaKeyProtocol.CMD_GIF_LAYOUT, "GIF 배치"),
        Map.entry(AhaKeyProtocol.CMD_CONFIG_QUERY, "설정 조회"),
        Map.entry(AhaKeyProtocol.CMD_QUERY_CAPABILITIES, "기능 조회"));

    /** True for frames sent or received by the periodic status poll. */
    public static boolean isStatusPoll(Entry entry) {
        if (!entry.isFrame() || entry.raw() == null) return false;
        Byte command = frameCommand(entry.raw());
        return command != null && command == AhaKeyProtocol.CMD_QUERY_STATUS;
    }

    static String describe(String transport, byte tcpType, byte[] data, boolean outgoing) {
        StringBuilder text = new StringBuilder(outgoing ? "보냄" : "받음");
        if ("BLE".equals(transport)) {
            text.append(" · ").append(TCP_TYPES.getOrDefault(tcpType,
                String.format("TCP 0x%02X", tcpType & 0xFF)));
        }
        Byte command = data == null ? null : frameCommand(data);
        if (command != null) {
            text.append(" · ").append(COMMANDS.getOrDefault(command, "명령"))
                .append(String.format(" (0x%02X)", command & 0xFF));
        }
        text.append(" · ").append(data == null ? 0 : data.length).append("바이트");
        return text.toString();
    }

    /** Command byte of an `AA BB cmd ... CC DD` frame, or null. */
    static Byte frameCommand(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xFF) == 0xAA && (data[1] & 0xFF) == 0xBB) return data[2];
        return null;
    }

    public static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) out.append(' ');
            out.append(String.format("%02X", data[i] & 0xFF));
        }
        return out.toString();
    }

    private static byte[] copy(byte[] data) {
        return data == null ? new byte[0] : data.clone();
    }
}
