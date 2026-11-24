package com.example.ahakey.view;

import com.example.ahakey.diagnostics.ConnectionLog;
import com.example.ahakey.diagnostics.ConnectionLog.Entry;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Connection log: app events, BLE driver events and every frame sent to or received
 * from the keyboard, newest at the bottom. Opened from More → Connection log.
 */
public final class ConnectionLogWindow {
    private static final DateTimeFormatter TIME =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final int MAX_ROWS = 20_000;
    private static Stage open;

    private enum Detail {
        IMPORTANT("중요한 것만"), DETAILED("자세히"), RAW("원본 데이터까지");
        final String label;
        Detail(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final ObservableList<Entry> rows = FXCollections.observableArrayList();
    private final FilteredList<Entry> visible = new FilteredList<>(rows);
    private final ConcurrentLinkedQueue<Entry> pending = new ConcurrentLinkedQueue<>();
    private final Consumer<Entry> listener = pending::add;
    private final ComboBox<Detail> detail = new ComboBox<>(FXCollections.observableArrayList(Detail.values()));
    private final CheckBox hidePolls = new CheckBox("상태 확인 숨기기");
    private final CheckBox showApp = new CheckBox("앱");
    private final CheckBox showDriver = new CheckBox("BLE 드라이버");
    private final CheckBox showFrames = new CheckBox("주고받은 데이터");
    private final TextField search = new TextField();
    private final ToggleButton pause = new ToggleButton("일시 정지");
    private final Label status = new Label();
    private final Label counts = new Label();
    private final ListView<Entry> list = new ListView<>(visible);
    private final TextArea details = new TextArea();

    /** Shows the window, reusing it when it is already open. */
    public static void show(Window owner) {
        if (open != null) {
            open.show();
            open.toFront();
            return;
        }
        open = new ConnectionLogWindow().build(owner);
        open.show();
    }

    private Stage build(Window owner) {
        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.setTitle("연결 기록");

        detail.setValue(Detail.DETAILED);
        hidePolls.setSelected(true);
        for (CheckBox box : List.of(showApp, showDriver, showFrames)) box.setSelected(true);
        search.setPromptText("검색 (예: 끊김, 0x9F, AA BB)");
        HBox.setHgrow(search, Priority.ALWAYS);
        for (var control : List.of(detail.valueProperty(), hidePolls.selectedProperty(),
                showApp.selectedProperty(), showDriver.selectedProperty(),
                showFrames.selectedProperty(), search.textProperty())) {
            control.addListener((observable, before, after) -> applyFilter());
        }

        Button clear = new Button("화면 비우기");
        clear.setOnAction(event -> { rows.clear(); ConnectionLog.get().clear(); details.clear(); });
        Button copy = new Button("보이는 기록 복사");
        copy.setOnAction(event -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(String.join("\n", visible.stream().map(ConnectionLog::format).toList()));
            Clipboard.getSystemClipboard().setContent(content);
        });
        Button save = new Button("파일로 저장");
        save.setOnAction(event -> saveVisible(stage));
        Button folder = new Button("기록 폴더 열기");
        folder.setOnAction(event -> openFolder());

        Label viewLabel = new Label("보기");
        HBox filters = row(viewLabel, detail, showApp, showDriver, showFrames, hidePolls);
        HBox actions = row(search, pause, clear, copy, save, folder);
        status.setWrapText(true);
        status.setStyle("-fx-font-weight: 700;");
        counts.setStyle("-fx-text-fill: #6b7280;");
        Label help = new Label("연결이 끊길 때는 '원본 데이터까지'로 두고 끊긴 시각 앞뒤를 보세요. "
            + "기록은 날짜별 파일로도 남아서 앱을 다시 켜도 기록 폴더에서 볼 수 있어요.");
        help.setWrapText(true);
        help.setStyle("-fx-text-fill: #6b7280;");
        VBox top = new VBox(8, status, filters, actions, help);
        top.setPadding(new Insets(12));

        list.setCellFactory(view -> new EntryCell());
        list.getSelectionModel().selectedItemProperty().addListener((observable, before, entry) -> showDetails(entry));
        details.setEditable(false);
        details.setWrapText(false);
        details.setStyle("-fx-font-family: Consolas, 'Malgun Gothic', monospace; -fx-font-size: 12px;");
        details.setPromptText("줄을 선택하면 자세한 내용과 원본 데이터가 여기에 보여요.");

        SplitPane split = new SplitPane(list, details);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.72);

        HBox bottom = new HBox(counts);
        bottom.setPadding(new Insets(6, 12, 8, 12));
        BorderPane root = new BorderPane(split, top, null, bottom, null);
        stage.setScene(new Scene(root, 1040, 720));

        rows.addAll(ConnectionLog.get().snapshot());
        applyFilter();
        list.scrollTo(visible.size() - 1);
        ConnectionLog.get().addListener(listener);

        Timeline refresh = new Timeline(new KeyFrame(Duration.millis(300), event -> flush()));
        refresh.setCycleCount(Timeline.INDEFINITE);
        refresh.play();
        stage.setOnHidden(event -> {
            refresh.stop();
            ConnectionLog.get().removeListener(listener);
            open = null;
        });
        flush();
        return stage;
    }

    private static HBox row(javafx.scene.Node... nodes) {
        HBox box = new HBox(10, nodes);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void flush() {
        status.setText(ConnectionLog.statusSummary().isBlank()
            ? "연결 상태를 아직 모르는 상태예요." : ConnectionLog.statusSummary());
        if (!pause.isSelected()) {
            List<Entry> batch = new ArrayList<>();
            for (Entry entry; (entry = pending.poll()) != null; ) batch.add(entry);
            if (!batch.isEmpty()) {
                boolean atEnd = isAtEnd();
                rows.addAll(batch);
                if (rows.size() > MAX_ROWS) rows.remove(0, rows.size() - MAX_ROWS);
                if (atEnd) list.scrollTo(visible.size() - 1);
            }
        }
        long drops = rows.stream().filter(ConnectionLogWindow::isDisconnect).count();
        counts.setText("보이는 줄 " + visible.size() + "개 · 전체 " + rows.size() + "개 · 끊김 관련 "
            + drops + "번" + (pause.isSelected() ? " · 일시 정지 중 (새 기록 " + pending.size() + "개 대기)" : ""));
    }

    private boolean isAtEnd() {
        var selected = list.getSelectionModel().getSelectedIndex();
        return selected < 0 || selected >= visible.size() - 1;
    }

    private void applyFilter() {
        Detail level = detail.getValue();
        String query = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        visible.setPredicate(entry -> {
            switch (entry.source()) {
                case APP -> { if (!showApp.isSelected()) return false; }
                case BRIDGE -> { if (!showDriver.isSelected()) return false; }
                case TX, RX -> {
                    if (!showFrames.isSelected() || level != Detail.RAW) return false;
                    if (hidePolls.isSelected() && ConnectionLog.isStatusPoll(entry)) return false;
                }
            }
            if (level == Detail.IMPORTANT && entry.level() == ConnectionLog.Level.DEBUG) return false;
            if (query.isEmpty()) return true;
            return ConnectionLog.format(entry).toLowerCase(Locale.ROOT).contains(query);
        });
    }

    private void showDetails(Entry entry) {
        if (entry == null) { details.clear(); return; }
        StringBuilder text = new StringBuilder(ConnectionLog.format(entry)).append("\n\n");
        text.append("출처: ").append(sourceName(entry)).append('\n');
        text.append("수준: ").append(entry.level()).append('\n');
        if (!entry.transport().isEmpty()) text.append("구분: ").append(entry.transport()).append('\n');
        text.append("내용: ").append(entry.message()).append('\n');
        if (entry.raw() != null && entry.raw().length > 0) {
            text.append("\n원본 데이터 (").append(entry.raw().length).append("바이트)\n");
            byte[] raw = entry.raw();
            for (int offset = 0; offset < raw.length; offset += 16) {
                StringBuilder hex = new StringBuilder();
                StringBuilder ascii = new StringBuilder();
                for (int i = offset; i < Math.min(raw.length, offset + 16); i++) {
                    hex.append(String.format("%02X ", raw[i] & 0xFF));
                    int c = raw[i] & 0xFF;
                    ascii.append(c >= 32 && c < 127 ? (char) c : '.');
                }
                text.append(String.format("%04X  %-48s %s%n", offset, hex, ascii));
            }
        }
        details.setText(text.toString());
    }

    private void saveVisible(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("연결 기록 저장");
        chooser.setInitialFileName("ahakey-connection-log.txt");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("텍스트 파일", "*.txt"));
        var file = chooser.showSaveDialog(stage);
        if (file == null) return;
        try {
            Files.writeString(file.toPath(),
                String.join(System.lineSeparator(), visible.stream().map(ConnectionLog::format).toList()),
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR,
                "파일을 저장하지 못했어요: " + e.getMessage()).showAndWait();
        }
    }

    private static void openFolder() {
        try {
            Files.createDirectories(ConnectionLog.logDirectory());
            new ProcessBuilder("explorer.exe", ConnectionLog.logDirectory().toString()).start();
        } catch (IOException ignored) {
            // Explorer is Windows only; the folder path is shown in the details pane instead.
        }
    }

    private static String sourceName(Entry entry) {
        return switch (entry.source()) {
            case APP -> "앱";
            case BRIDGE -> "BLE 드라이버";
            case TX -> "키보드로 보냄 (" + entry.transport() + ")";
            case RX -> "키보드에서 받음 (" + entry.transport() + ")";
        };
    }

    private static boolean isDisconnect(Entry entry) {
        String text = entry.message();
        return entry.level() != ConnectionLog.Level.DEBUG
            && (text.contains("끊") || text.contains("disconnect") || text.contains("lost"));
    }

    private static final class EntryCell extends ListCell<Entry> {
        @Override
        protected void updateItem(Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) { setText(null); setGraphic(null); setStyle(""); return; }
            String tag = switch (entry.source()) {
                case APP -> "앱";
                case BRIDGE -> "드라이버";
                case TX -> "→ " + entry.transport();
                case RX -> "← " + entry.transport();
            };
            String message = entry.message();
            if (entry.isFrame() && entry.raw() != null && entry.raw().length > 0) {
                String hex = ConnectionLog.hex(entry.raw());
                message += "   " + (hex.length() > 96 ? hex.substring(0, 96) + " …" : hex);
            }
            setText(TIME.format(entry.time()) + "  " + pad(tag) + "  " + message);
            String color = switch (entry.level()) {
                case ERROR -> "#b91c1c";
                case WARN -> "#b45309";
                case INFO -> entry.source() == ConnectionLog.Source.BRIDGE ? "#1d4ed8" : "#111827";
                case DEBUG -> entry.isFrame() ? "#4b5563" : "#6b7280";
            };
            String weight = isDisconnect(entry) || message.contains("연결했어요") || message.contains("연결됐")
                ? "-fx-font-weight: 700;" : "";
            setStyle("-fx-text-fill: " + color + "; -fx-font-family: Consolas, 'Malgun Gothic', monospace; "
                + "-fx-font-size: 12px; " + weight);
        }

        private static String pad(String tag) {
            return tag.length() >= 8 ? tag : tag + " ".repeat(8 - tag.length());
        }
    }
}
