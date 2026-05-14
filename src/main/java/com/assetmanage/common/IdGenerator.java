package com.assetmanage.common;

import cn.hutool.core.util.IdUtil;

public class IdGenerator {

    private static final String ASSET_PREFIX = "asset_";
    private static final String USAGE_PREFIX = "usage_";
    private static final String MAINTENANCE_PREFIX = "maint_";
    private static final String DEPRECIATION_PREFIX = "depreciation_";
    private static final String CHECK_PREFIX = "check_";
    private static final String DIFF_PREFIX = "diff_";
    private static final String SCRAP_PREFIX = "scrap_";
    private static final String STAT_PREFIX = "stat_";
    private static final String HISTORY_PREFIX = "history_";

    private IdGenerator() {
    }

    public static String generateAssetId() {
        return ASSET_PREFIX + IdUtil.getSnowflakeNextIdStr();
    }

    public static String generateUsageId() {
        return USAGE_PREFIX + IdUtil.getSnowflakeNextIdStr();
    }

    public static String generateMaintenanceId() {
        return MAINTENANCE_PREFIX + IdUtil.getSnowflakeNextIdStr();
    }

    public static String generateDepreciationId() {
        return DEPRECIATION_PREFIX + IdUtil.getSnowflakeNextIdStr();
    }

    public static String generateCheckId() {
        return CHECK_PREFIX + IdUtil.getSnowflakeNextIdStr();
    }

    public static String generateDiffId() {
        return DIFF_PREFIX + IdUtil.getSnowflakeNextIdStr();
    }

    public static String generateScrapId() {
        return SCRAP_PREFIX + IdUtil.getSnowflakeNextIdStr();
    }

    public static String generateStatId() {
        return STAT_PREFIX + IdUtil.getSnowflakeNextIdStr();
    }

    public static String generateHistoryId() {
        return HISTORY_PREFIX + IdUtil.getSnowflakeNextIdStr();
    }
}
