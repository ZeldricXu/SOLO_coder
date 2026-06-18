package com.designsystem.common.util;

import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SemverUtil {

    private static final Pattern CONVENTIONAL_COMMIT_PATTERN = Pattern.compile(
            "^(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)" +
                    "(?:\\(([^)]+)\\))?(!)?:\\s*(.+)$",
            Pattern.MULTILINE
    );

    private static final Pattern BREAKING_CHANGE_PATTERN = Pattern.compile(
            "BREAKING CHANGE:\\s*(.+)$",
            Pattern.MULTILINE
    );

    public enum BumpType {
        MAJOR, MINOR, PATCH, NONE
    }

    public static BumpType determineBumpType(String commitMessage) {
        if (commitMessage == null || commitMessage.isEmpty()) {
            return BumpType.NONE;
        }

        Matcher breakingMatcher = BREAKING_CHANGE_PATTERN.matcher(commitMessage);
        if (breakingMatcher.find()) {
            return BumpType.MAJOR;
        }

        String firstLine = commitMessage.split("\n")[0];
        Matcher commitMatcher = CONVENTIONAL_COMMIT_PATTERN.matcher(firstLine);

        if (!commitMatcher.find()) {
            return BumpType.NONE;
        }

        String type = commitMatcher.group(1);
        String breaking = commitMatcher.group(3);

        if ("!".equals(breaking)) {
            return BumpType.MAJOR;
        }

        return switch (type) {
            case "feat" -> BumpType.MINOR;
            case "fix", "perf" -> BumpType.PATCH;
            default -> BumpType.NONE;
        };
    }

    public static String incrementVersion(String currentVersion, BumpType bumpType) {
        Semver semver;
        try {
            semver = new Semver(currentVersion);
        } catch (SemverException e) {
            semver = new Semver("1.0.0");
        }

        return switch (bumpType) {
            case MAJOR -> semver.nextMajor().toString();
            case MINOR -> semver.nextMinor().toString();
            case PATCH -> semver.nextPatch().toString();
            case NONE -> semver.toString();
        };
    }

    public static String incrementVersion(String currentVersion, String commitMessage) {
        BumpType bumpType = determineBumpType(commitMessage);
        return incrementVersion(currentVersion, bumpType);
    }

    public static String getNextVersionFromChangelogs(String currentVersion, java.util.List<String> commitMessages) {
        BumpType maxBump = BumpType.NONE;

        for (String message : commitMessages) {
            BumpType bump = determineBumpType(message);
            if (bump.ordinal() > maxBump.ordinal()) {
                maxBump = bump;
            }
        }

        return incrementVersion(currentVersion, maxBump);
    }

    public static boolean isValidVersion(String version) {
        try {
            new Semver(version);
            return true;
        } catch (SemverException e) {
            return false;
        }
    }

    public static int compareVersions(String v1, String v2) {
        Semver semver1 = new Semver(v1);
        Semver semver2 = new Semver(v2);
        return semver1.compareTo(semver2);
    }
}
