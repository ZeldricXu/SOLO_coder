package com.company.dbstudio.update;

import com.company.dbstudio.core.model.Result;
import com.company.dbstudio.core.util.JsonUtils;
import com.company.dbstudio.update.model.GitHubRelease;
import com.company.dbstudio.update.model.VersionInfo;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class GitHubReleaseChecker {

    private static final Logger logger = LoggerFactory.getLogger(GitHubReleaseChecker.class);
    private static final GitHubReleaseChecker INSTANCE = new GitHubReleaseChecker();

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final String USER_AGENT = "DBStudio-Checker";

    private final HttpClient httpClient;
    private final VersionManager versionManager;
    private final AtomicBoolean checkInProgress;
    private GitHubRelease latestRelease;
    private LocalDateTime lastCheckTime;
    private volatile VersionInfo latestVersion;
    private volatile String checkError;

    private GitHubReleaseChecker() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.versionManager = VersionManager.getInstance();
        this.checkInProgress = new AtomicBoolean(false);
    }

    public static GitHubReleaseChecker getInstance() {
        return INSTANCE;
    }

    public Result<GitHubRelease> checkLatestRelease() {
        return checkLatestRelease(false);
    }

    public Result<GitHubRelease> checkLatestRelease(boolean includePreRelease) {
        if (checkInProgress.compareAndSet(false, true)) {
            try {
                String apiUrl = includePreRelease
                        ? versionManager.getGitHubLatestReleaseApiUrl().replace("/latest", "?per_page=1")
                        : versionManager.getGitHubLatestReleaseApiUrl();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", USER_AGENT)
                        .timeout(DEFAULT_TIMEOUT)
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    latestRelease = JsonUtils.fromJson(response.body(), GitHubRelease.class);
                    if (latestRelease != null) {
                        latestVersion = new VersionInfo(latestRelease.getVersionString());
                        lastCheckTime = LocalDateTime.now();
                        checkError = null;
                        logger.info("Found latest release: {} (published {})",
                                latestVersion, latestRelease.getPublishedAt());
                        return Result.success(latestRelease);
                    } else {
                        checkError = "Failed to parse release response";
                        return Result.failure(checkError);
                    }
                } else {
                    checkError = "GitHub API returned status: " + response.statusCode();
                    logger.warn(checkError);
                    return Result.failure(checkError);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                checkError = "Check interrupted";
                return Result.failure(checkError);
            } catch (Exception e) {
                checkError = "Failed to check latest release: " + e.getMessage();
                logger.warn(checkError, e);
                return Result.failure(checkError);
            } finally {
                checkInProgress.set(false);
            }
        } else {
            return Result.failure("Check already in progress");
        }
    }

    public void checkLatestReleaseAsync(boolean includePreRelease, Consumer<Result<GitHubRelease>> callback) {
        Thread.ofVirtual().start(() -> {
            Result<GitHubRelease> result = checkLatestRelease(includePreRelease);
            if (callback != null) {
                Platform.runLater(() -> callback.accept(result));
            }
        });
    }

    public void checkLatestReleaseAsync(Consumer<Result<GitHubRelease>> callback) {
        checkLatestReleaseAsync(false, callback);
    }

    public boolean isUpdateAvailable() {
        if (latestVersion == null) {
            return false;
        }
        VersionInfo current = versionManager.getCurrentVersion();
        return latestVersion.isNewerThan(current) && !latestVersion.isSnapshot();
    }

    public boolean isUpdateAvailable(GitHubRelease release) {
        if (release == null || release.getVersionString() == null) {
            return false;
        }
        VersionInfo releaseVersion = new VersionInfo(release.getVersionString());
        VersionInfo current = versionManager.getCurrentVersion();
        return releaseVersion.isNewerThan(current) && !releaseVersion.isSnapshot() && !release.isDraft();
    }

    public GitHubRelease getLatestRelease() {
        return latestRelease;
    }

    public VersionInfo getLatestVersion() {
        return latestVersion;
    }

    public LocalDateTime getLastCheckTime() {
        return lastCheckTime;
    }

    public String getCheckError() {
        return checkError;
    }

    public boolean isCheckInProgress() {
        return checkInProgress.get();
    }

    public String getDownloadUrlForCurrentPlatform() {
        if (latestRelease == null || latestRelease.getAssets() == null) {
            return latestRelease != null ? latestRelease.getHtmlUrl() : versionManager.getGitHubReleasesUrl();
        }

        String os = System.getProperty("os.name", "").toLowerCase();
        String platform = os.contains("win") ? "windows"
                : os.contains("mac") ? "mac"
                : "linux";

        return latestRelease.getAssets().stream()
                .filter(asset -> asset.isForPlatform(platform))
                .findFirst()
                .map(GitHubRelease.ReleaseAsset::getBrowserDownloadUrl)
                .orElse(latestRelease.getHtmlUrl());
    }

    public void openDownloadPage() {
        String url = getDownloadUrlForCurrentPlatform();
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception e) {
            logger.warn("Failed to open browser to download page: {}", url, e);
        }
    }

    public void resetCache() {
        this.latestRelease = null;
        this.latestVersion = null;
        this.lastCheckTime = null;
        this.checkError = null;
    }
}
