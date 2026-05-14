package com.configcenter.common.util;

import com.configcenter.common.exception.BusinessException;

public class VersionUtils {

    private VersionUtils() {
    }

    public static String getNextVersion(String currentVersion) {
        if (currentVersion == null || currentVersion.isEmpty()) {
            return "v1";
        }

        if (!currentVersion.startsWith("v")) {
            throw new BusinessException("版本号格式错误: " + currentVersion);
        }

        try {
            int num = Integer.parseInt(currentVersion.substring(1));
            return "v" + (num + 1);
        } catch (NumberFormatException e) {
            throw new BusinessException("版本号格式错误: " + currentVersion, e);
        }
    }

    public static int compareVersion(String v1, String v2) {
        if (v1 == null || v2 == null) {
            throw new BusinessException("版本号不能为空");
        }

        if (!v1.startsWith("v") || !v2.startsWith("v")) {
            throw new BusinessException("版本号格式错误");
        }

        try {
            int num1 = Integer.parseInt(v1.substring(1));
            int num2 = Integer.parseInt(v2.substring(1));
            return Integer.compare(num1, num2);
        } catch (NumberFormatException e) {
            throw new BusinessException("版本号格式错误", e);
        }
    }

    public static boolean isValidVersion(String version) {
        if (version == null || version.isEmpty() || !version.startsWith("v")) {
            return false;
        }
        try {
            Integer.parseInt(version.substring(1));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
