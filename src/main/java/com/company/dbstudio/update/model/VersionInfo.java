package com.company.dbstudio.update.model;

import java.time.LocalDateTime;
import java.util.Objects;

public class VersionInfo implements Comparable<VersionInfo> {

    private final String version;
    private final int major;
    private final int minor;
    private final int patch;
    private final String preRelease;
    private final boolean snapshot;

    public VersionInfo(String version) {
        this.version = version;
        this.snapshot = version.contains("SNAPSHOT");

        String cleanVersion = version
                .replace("-SNAPSHOT", "")
                .replace("v", "")
                .trim();

        String preReleasePart = null;
        int dashIndex = cleanVersion.indexOf('-');
        if (dashIndex > 0) {
            preReleasePart = cleanVersion.substring(dashIndex + 1);
            cleanVersion = cleanVersion.substring(0, dashIndex);
        }
        this.preRelease = preReleasePart;

        String[] parts = cleanVersion.split("\\.");
        if (parts.length >= 1) {
            this.major = parseVersionPart(parts[0]);
        } else {
            this.major = 0;
        }
        if (parts.length >= 2) {
            this.minor = parseVersionPart(parts[1]);
        } else {
            this.minor = 0;
        }
        if (parts.length >= 3) {
            this.patch = parseVersionPart(parts[2]);
        } else {
            this.patch = 0;
        }
    }

    private int parseVersionPart(String part) {
        try {
            return Integer.parseInt(part.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public boolean isNewerThan(VersionInfo other) {
        if (other == null) {
            return true;
        }
        return this.compareTo(other) > 0;
    }

    public boolean isOlderThan(VersionInfo other) {
        if (other == null) {
            return false;
        }
        return this.compareTo(other) < 0;
    }

    @Override
    public int compareTo(VersionInfo other) {
        if (other == null) {
            return 1;
        }

        int majorCompare = Integer.compare(this.major, other.major);
        if (majorCompare != 0) {
            return majorCompare;
        }

        int minorCompare = Integer.compare(this.minor, other.minor);
        if (minorCompare != 0) {
            return minorCompare;
        }

        int patchCompare = Integer.compare(this.patch, other.patch);
        if (patchCompare != 0) {
            return patchCompare;
        }

        if (this.preRelease == null && other.preRelease != null) {
            return 1;
        }
        if (this.preRelease != null && other.preRelease == null) {
            return -1;
        }
        if (this.preRelease != null && other.preRelease != null) {
            return this.preRelease.compareTo(other.preRelease);
        }

        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VersionInfo that = (VersionInfo) o;
        return major == that.major &&
               minor == that.minor &&
               patch == that.patch &&
               snapshot == that.snapshot &&
               Objects.equals(version, that.version) &&
               Objects.equals(preRelease, that.preRelease);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, major, minor, patch, preRelease, snapshot);
    }

    public String getVersion() {
        return version;
    }

    public int getMajor() {
        return major;
    }

    public int getMinor() {
        return minor;
    }

    public int getPatch() {
        return patch;
    }

    public String getPreRelease() {
        return preRelease;
    }

    public boolean isSnapshot() {
        return snapshot;
    }

    @Override
    public String toString() {
        return version;
    }

    public static VersionInfo parse(String version) {
        return new VersionInfo(version);
    }
}
