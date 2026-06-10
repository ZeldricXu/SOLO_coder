package com.company.dbstudio.update.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

public class GitHubRelease {

    private long id;
    private String name;
    private String body;

    @JsonProperty("tag_name")
    private String tagName;

    @JsonProperty("html_url")
    private String htmlUrl;

    @JsonProperty("draft")
    private boolean draft;

    @JsonProperty("prerelease")
    private boolean prerelease;

    @JsonProperty("published_at")
    private LocalDateTime publishedAt;

    @JsonProperty("assets")
    private List<ReleaseAsset> assets;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getTagName() {
        return tagName;
    }

    public void setTagName(String tagName) {
        this.tagName = tagName;
    }

    public String getHtmlUrl() {
        return htmlUrl;
    }

    public void setHtmlUrl(String htmlUrl) {
        this.htmlUrl = htmlUrl;
    }

    public boolean isDraft() {
        return draft;
    }

    public void setDraft(boolean draft) {
        this.draft = draft;
    }

    public boolean isPrerelease() {
        return prerelease;
    }

    public void setPrerelease(boolean prerelease) {
        this.prerelease = prerelease;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public List<ReleaseAsset> getAssets() {
        return assets;
    }

    public void setAssets(List<ReleaseAsset> assets) {
        this.assets = assets;
    }

    public String getVersionString() {
        if (tagName != null && tagName.startsWith("v")) {
            return tagName.substring(1);
        }
        return tagName;
    }

    public static class ReleaseAsset {
        private long id;
        private String name;
        private String label;

        @JsonProperty("content_type")
        private String contentType;

        private long size;

        @JsonProperty("download_count")
        private long downloadCount;

        @JsonProperty("browser_download_url")
        private String browserDownloadUrl;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }

        public long getDownloadCount() {
            return downloadCount;
        }

        public void setDownloadCount(long downloadCount) {
            this.downloadCount = downloadCount;
        }

        public String getBrowserDownloadUrl() {
            return browserDownloadUrl;
        }

        public void setBrowserDownloadUrl(String browserDownloadUrl) {
            this.browserDownloadUrl = browserDownloadUrl;
        }

        public boolean isForPlatform(String platform) {
            if (name == null) return false;
            String lowerName = name.toLowerCase();
            return switch (platform.toLowerCase()) {
                case "windows" -> lowerName.contains("windows") || lowerName.contains(".msi");
                case "mac", "macos", "osx" -> lowerName.contains("mac") || lowerName.contains(".dmg");
                case "linux", "linux-deb" -> lowerName.contains("linux") || lowerName.contains(".deb");
                case "linux-rpm" -> lowerName.contains(".rpm");
                default -> true;
            };
        }
    }
}
