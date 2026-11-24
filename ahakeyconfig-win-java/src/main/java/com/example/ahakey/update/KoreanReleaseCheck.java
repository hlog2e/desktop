package com.example.ahakey.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Update check of the Korean fork build. It never downloads or runs the official
 * installer (that would replace the Korean build with the official one); it only
 * looks at the fork's latest GitHub release and opens its download page.
 */
final class KoreanReleaseCheck {
    static final String REPOSITORY = "hlog2e/desktop";
    static final String RELEASES_PAGE = "https://github.com/" + REPOSITORY + "/releases/latest";
    private static final URI LATEST = URI.create("https://api.github.com/repos/" + REPOSITORY + "/releases/latest");

    private KoreanReleaseCheck() {}

    static boolean isKoreanBuild() {
        return Boolean.getBoolean("ahakey.koreanBuild");
    }

    /** Latest release version from a tag like {@code ko-v1.5.4}, if newer than the running build. */
    static Optional<String> newerVersion() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        HttpRequest request = HttpRequest.newBuilder(LATEST).timeout(Duration.ofSeconds(10))
            .header("Accept", "application/vnd.github+json").GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return Optional.empty();
        if (response.statusCode() != 200) throw new IllegalStateException("GitHub HTTP " + response.statusCode());
        JsonNode release = new ObjectMapper().readTree(response.body());
        String tag = release.path("tag_name").asText("").replaceFirst("^ko-v", "");
        SemanticVersion latest = SemanticVersion.parse(tag);
        return latest.compareTo(AppUpdateCoordinator.CURRENT) > 0 ? Optional.of(tag) : Optional.empty();
    }

    static void check(Stage owner, boolean manual) {
        Thread thread = new Thread(() -> {
            try {
                Optional<String> newer = newerVersion();
                Platform.runLater(() -> {
                    if (newer.isPresent()) offer(owner, newer.get());
                    else if (manual) show(owner, Alert.AlertType.INFORMATION, "최신 버전이에요",
                        "지금 쓰는 한국어판 " + AppUpdateCoordinator.CURRENT + "이 가장 최신이에요.");
                });
            } catch (Exception e) {
                if (manual) Platform.runLater(() -> show(owner, Alert.AlertType.WARNING, "업데이트를 확인하지 못했어요",
                    "인터넷 연결을 확인한 뒤 다시 시도해 주세요. (" + e.getMessage() + ")"));
            }
        }, "korean-release-check");
        thread.setDaemon(true);
        thread.start();
    }

    static void showAbout(Stage owner) {
        Alert about = alert(owner, Alert.AlertType.INFORMATION, "버전 정보와 업데이트",
            "AhaKey Studio 한국어판 " + AppUpdateCoordinator.CURRENT + "\nWindows 10/11 x64\n\n"
                + "한국어판은 GitHub(" + REPOSITORY + ")에서 새 버전을 확인해요. "
                + "공식 버전으로 자동 업데이트하지 않아요.");
        ButtonType check = new ButtonType("업데이트 확인", ButtonBar.ButtonData.OK_DONE);
        about.getButtonTypes().setAll(check, ButtonType.CLOSE);
        if (about.showAndWait().filter(check::equals).isPresent()) check(owner, true);
    }

    private static void offer(Stage owner, String version) {
        Alert prompt = alert(owner, Alert.AlertType.INFORMATION, "새 한국어판이 있어요",
            "AhaKey Studio 한국어판 " + version + "을 받을 수 있어요. 다운로드 페이지에서 설치 파일을 받아 실행하세요.");
        ButtonType open = new ButtonType("다운로드 페이지 열기", ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType("나중에", ButtonBar.ButtonData.CANCEL_CLOSE);
        prompt.getButtonTypes().setAll(open, later);
        if (prompt.showAndWait().filter(open::equals).isPresent()) openPage();
    }

    static void openPage() {
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", RELEASES_PAGE).start();
        } catch (Exception e) {
            try { java.awt.Desktop.getDesktop().browse(URI.create(RELEASES_PAGE)); } catch (Exception ignored) { }
        }
    }

    private static void show(Stage owner, Alert.AlertType type, String title, String content) {
        alert(owner, type, title, content).showAndWait();
    }

    private static Alert alert(Stage owner, Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type, content);
        if (owner != null) alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(title);
        return alert;
    }
}
