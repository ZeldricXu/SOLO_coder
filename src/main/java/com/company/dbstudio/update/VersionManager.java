package com.company.dbstudio.update;

import com.company.dbstudio.update.model.VersionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

public class VersionManager {

    private static final Logger logger = LoggerFactory.getLogger(VersionManager.class);
    private static final VersionManager INSTANCE = new VersionManager();

    private final VersionInfo currentVersion;
    private final String vendor;
    private final String appName;
    private final String buildTimestamp;
    private final String gitHubRepo;

    private VersionManager() {
        Manifest manifest = loadManifest();
        Attributes attributes = manifest != null ? manifest.getMainAttributes() : new Attributes();

        String version = attributes.getValue("Implementation-Version");
        if (version == null || version.isEmpty()) {
            version = getClass().getPackage().getImplementationVersion();
        }
        if (version == null || version.isEmpty()) {
            version = System.getProperty("dbstudio.version", "1.0.0");
        }

        this.currentVersion = new VersionInfo(version);
        this.vendor = getAttribute(attributes, "Implementation-Vendor", "Company DBA Team");
        this.appName = getAttribute(attributes, "Implementation-Title", "DBStudio");
        this.buildTimestamp = getAttribute(attributes, "Build-Timestamp", "unknown");
        this.gitHubRepo = getAttribute(attributes, "GitHub-Repo", "company/dbstudio");

        logger.info("{} version {} (built {})", appName, currentVersion, buildTimestamp);
    }

    public static VersionManager getInstance() {
        return INSTANCE;
    }

    private String getAttribute(Attributes attributes, String name, String defaultValue) {
        String value = attributes.getValue(name);
        if (value == null || value.isEmpty()) {
            value = System.getProperty("dbstudio." + name.toLowerCase(), defaultValue);
        }
        return value;
    }

    private Manifest loadManifest() {
        try {
            String className = getClass().getSimpleName() + ".class";
            String classPath = getClass().getResource(className).toString();
            if (!classPath.startsWith("jar:")) {
                logger.debug("Not running from JAR, skipping manifest load");
                return null;
            }

            String manifestPath = classPath.substring(0, classPath.lastIndexOf("!") + 1) + "/META-INF/MANIFEST.MF";
            URL manifestUrl = new URL(manifestPath);
            try (var is = manifestUrl.openStream()) {
                return new Manifest(is);
            }
        } catch (IOException e) {
            logger.warn("Failed to load MANIFEST.MF, using fallback values", e);
            return null;
        }
    }

    public VersionInfo getCurrentVersion() {
        return currentVersion;
    }

    public String getVersionString() {
        return currentVersion.getVersion();
    }

    public String getVendor() {
        return vendor;
    }

    public String getAppName() {
        return appName;
    }

    public String getBuildTimestamp() {
        return buildTimestamp;
    }

    public String getGitHubRepo() {
        return gitHubRepo;
    }

    public String getGitHubReleasesUrl() {
        return "https://github.com/" + gitHubRepo + "/releases";
    }

    public String getGitHubLatestReleaseApiUrl() {
        return "https://api.github.com/repos/" + gitHubRepo + "/releases/latest";
    }

    public String getFullVersionString() {
        return appName + " v" + currentVersion + " (built " + buildTimestamp + ")";
    }

    public boolean isSnapshot() {
        return currentVersion.isSnapshot();
    }

    public int getMajorVersion() {
        return currentVersion.getMajor();
    }

    public int getMinorVersion() {
        return currentVersion.getMinor();
    }

    public int getPatchVersion() {
        return currentVersion.getPatch();
    }

    @Override
    public String toString() {
        return getFullVersionString();
    }
}
