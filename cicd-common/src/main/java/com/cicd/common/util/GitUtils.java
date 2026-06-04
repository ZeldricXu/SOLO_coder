package com.cicd.common.util;

import java.util.regex.Pattern;

public class GitUtils {

    public static boolean matchesBranchPattern(String branch, String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return Pattern.matches(regex, branch);
    }

    public static boolean isPathIncluded(String filePath, String[] includePatterns, String[] ignorePatterns) {
        if (ignorePatterns != null) {
            for (String ignore : ignorePatterns) {
                if (filePath.startsWith(ignore) || filePath.matches(globToRegex(ignore))) {
                    return false;
                }
            }
        }
        if (includePatterns == null || includePatterns.length == 0) {
            return true;
        }
        for (String include : includePatterns) {
            if (filePath.startsWith(include) || filePath.matches(globToRegex(include))) {
                return true;
            }
        }
        return false;
    }

    private static String globToRegex(String glob) {
        return glob
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".")
                .replace("/", "\\/");
    }

    public static String extractRepoName(String repoUrl) {
        if (repoUrl == null) return null;
        String[] parts = repoUrl.split("/");
        String lastPart = parts[parts.length - 1];
        return lastPart.replace(".git", "");
    }
}
