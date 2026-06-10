package com.company.dbstudio.update;

import com.company.dbstudio.core.model.Result;
import com.company.dbstudio.etl.model.ProgressInfo;
import com.company.dbstudio.update.model.GitHubRelease;
import com.company.dbstudio.update.model.VersionInfo;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.company.dbstudio.core.ApplicationContext.createVirtualThreadScheduledExecutor;

public class UpdateNotifier {

    private static final Logger logger = LoggerFactory.getLogger(UpdateNotifier.class);

    private final VersionManager versionManager;
    private final GitHubReleaseChecker releaseChecker;
    private final HBox statusBarContainer;
    private final Label statusLabel;
    private final Hyperlink updateLink;
    private final Label versionLabel;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledCheck;
    private final AtomicBoolean initialized;
    private volatile boolean autoCheckEnabled;
    private volatile long checkIntervalHours;

    public UpdateNotifier() {
        this.versionManager = VersionManager.getInstance();
        this.releaseChecker = GitHubReleaseChecker.getInstance();
        this.initialized = new AtomicBoolean(false);
        this.autoCheckEnabled = true;
        this.checkIntervalHours = 24;

        this.statusBarContainer = new HBox(10);
        statusBarContainer.setAlignment(Pos.CENTER_LEFT);
        statusBarContainer.setId("update-status-container");

        this.versionLabel = new Label("v" + versionManager.getVersionString());
        versionLabel.setId("update-version-label");
        versionLabel.setStyle("-fx-text-fill: #888888;");
        versionLabel.setTooltip(new Tooltip(versionManager.getFullVersionString()));

        this.statusLabel = new Label();
        statusLabel.setId("update-status-label");
        statusLabel.setManaged(false);
        statusLabel.setVisible(false);

        this.updateLink = new Hyperlink("");
        updateLink.setId("update-link");
        updateLink.setManaged(false);
        updateLink.setVisible(false);
        updateLink.setOnAction(e -> {
            releaseChecker.openDownloadPage();
            hideUpdateNotification();
        });

        statusBarContainer.getChildren().addAll(versionLabel, statusLabel, updateLink);
    }

    public void initialize() {
        if (initialized.compareAndSet(false, true)) {
            scheduler = createVirtualThreadScheduledExecutor("update-checker-%d", 1);
            schedulePeriodicCheck();
            logger.info("Update notifier initialized, auto-check every {} hours", checkIntervalHours);
        }
    }

    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        if (scheduledCheck != null && !scheduledCheck.isDone()) {
            scheduledCheck.cancel(true);
        }
        logger.info("Update notifier shut down");
    }

    public void checkForUpdatesNow() {
        checkForUpdatesNow(false);
    }

    public void checkForUpdatesNow(boolean includePreRelease) {
        showCheckingStatus();
        releaseChecker.checkLatestReleaseAsync(includePreRelease, this::handleCheckResult);
    }

    private void schedulePeriodicCheck() {
        if (autoCheckEnabled && scheduler != null && !scheduler.isShutdown()) {
            if (scheduledCheck != null && !scheduledCheck.isDone()) {
                scheduledCheck.cancel(false);
            }

            scheduledCheck = scheduler.scheduleAtFixedRate(
                    () -> {
                        if (autoCheckEnabled) {
                            logger.debug("Scheduled update check started");
                            releaseChecker.checkLatestReleaseAsync(this::handleCheckResult);
                        }
                    },
                    30,
                    checkIntervalHours * 60,
                    TimeUnit.MINUTES
            );
        }
    }

    private void handleCheckResult(Result<GitHubRelease> result) {
        Platform.runLater(() -> {
            if (result.isSuccess() && result.getData() != null) {
                GitHubRelease release = result.getData();
                if (releaseChecker.isUpdateAvailable(release)) {
                    showUpdateAvailable(release);
                } else {
                    showUpToDate();
                }
            } else {
                showCheckFailed(result.getMessage());
            }
        });
    }

    private void showCheckingStatus() {
        Platform.runLater(() -> {
            statusLabel.setText("检查更新中...");
            statusLabel.setStyle("-fx-text-fill: #888888;");
            statusLabel.setManaged(true);
            statusLabel.setVisible(true);
            updateLink.setManaged(false);
            updateLink.setVisible(false);
        });
    }

    private void showUpdateAvailable(GitHubRelease release) {
        VersionInfo current = versionManager.getCurrentVersion();
        VersionInfo latest = new VersionInfo(release.getVersionString());

        statusLabel.setText("有新版本可用: ");
        statusLabel.setStyle("-fx-text-fill: #4CAF50;");
        statusLabel.setManaged(true);
        statusLabel.setVisible(true);

        updateLink.setText(current + " → " + latest);
        updateLink.setStyle("-fx-text-fill: #2196F3; -fx-underline: true;");
        updateLink.setCursor(Cursor.HAND);
        updateLink.setManaged(true);
        updateLink.setVisible(true);

        String releaseInfo = String.format(
                "新版本: %s\n发布时间: %s\n\n发布说明:\n%s",
                latest,
                release.getPublishedAt() != null
                        ? release.getPublishedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                        : "未知",
                release.getBody() != null ? truncateText(release.getBody(), 500) : "无"
        );
        updateLink.setTooltip(new Tooltip(releaseInfo));

        logger.info("Update available: {} -> {}", current, latest);
    }

    private void showUpToDate() {
        VersionInfo current = versionManager.getCurrentVersion();
        statusLabel.setText("已是最新版本");
        statusLabel.setStyle("-fx-text-fill: #4CAF50;");
        statusLabel.setManaged(true);
        statusLabel.setVisible(true);
        updateLink.setManaged(false);
        updateLink.setVisible(false);

        updateLink.setTooltip(new Tooltip(
                String.format("当前版本: %s\n最后检查: %s",
                        current,
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
        ));

        logger.info("Application is up to date: {}", current);

        clearStatusAfterDelay(5000);
    }

    private void showCheckFailed(String error) {
        statusLabel.setText("更新检查失败");
        statusLabel.setStyle("-fx-text-fill: #f44336;");
        statusLabel.setManaged(true);
        statusLabel.setVisible(true);
        updateLink.setManaged(false);
        updateLink.setVisible(false);
        statusLabel.setTooltip(new Tooltip(error != null ? error : "未知错误"));

        logger.warn("Update check failed: {}", error);

        clearStatusAfterDelay(10000);
    }

    private void hideUpdateNotification() {
        statusLabel.setManaged(false);
        statusLabel.setVisible(false);
        updateLink.setManaged(false);
        updateLink.setVisible(false);
    }

    private void clearStatusAfterDelay(long delayMs) {
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(delayMs);
                Platform.runLater(() -> {
                    if (updateLink.isVisible()) {
                        statusLabel.setManaged(false);
                        statusLabel.setVisible(false);
                    } else {
                        hideUpdateNotification();
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private String truncateText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        text = text.strip();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    public Region getStatusBarNode() {
        return statusBarContainer;
    }

    public void setAutoCheckEnabled(boolean enabled) {
        this.autoCheckEnabled = enabled;
        if (enabled) {
            schedulePeriodicCheck();
        } else if (scheduledCheck != null) {
            scheduledCheck.cancel(false);
        }
        logger.info("Auto check for updates: {}", enabled);
    }

    public boolean isAutoCheckEnabled() {
        return autoCheckEnabled;
    }

    public void setCheckIntervalHours(long hours) {
        if (hours < 1) {
            hours = 1;
        }
        this.checkIntervalHours = hours;
        if (autoCheckEnabled) {
            schedulePeriodicCheck();
        }
        logger.info("Update check interval set to {} hours", hours);
    }

    public long getCheckIntervalHours() {
        return checkIntervalHours;
    }

    public String getCurrentVersionString() {
        return versionManager.getVersionString();
    }

    public VersionInfo getCurrentVersion() {
        return versionManager.getCurrentVersion();
    }

    public String getFullVersionString() {
        return versionManager.getFullVersionString();
    }
}
