package com.assetmanage.testdata;

import com.assetmanage.entity.*;
import com.assetmanage.enums.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TestDataBuilder {

    public static final String TEST_ASSET_ID = "asset_" + UUID.randomUUID().toString().substring(0, 8);
    public static final String TEST_USER_ID_1 = "user_001";
    public static final String TEST_USER_ID_2 = "user_002";
    public static final String TEST_OPERATOR_ID = "admin_001";
    public static final String TEST_CHECK_ID = "check_" + UUID.randomUUID().toString().substring(0, 8);
    public static final String TEST_DIFF_ID = "diff_" + UUID.randomUUID().toString().substring(0, 8);

    private TestDataBuilder() {
    }

    public static Asset buildIdleAsset() {
        return buildAsset(TEST_ASSET_ID, AssetStatus.IDLE);
    }

    public static Asset buildInUseAsset() {
        return buildAsset(TEST_ASSET_ID, AssetStatus.IN_USE);
    }

    public static Asset buildMaintenanceAsset() {
        return buildAsset(TEST_ASSET_ID, AssetStatus.MAINTENANCE);
    }

    public static Asset buildScrappedAsset() {
        return buildAsset(TEST_ASSET_ID, AssetStatus.SCRAPPED);
    }

    public static Asset buildAsset(String assetId, AssetStatus status) {
        Asset asset = new Asset();
        asset.setAssetId(assetId);
        asset.setAssetName("办公电脑");
        asset.setAssetType("computer");
        asset.setAssetCategory("办公设备");
        asset.setAssetModel("Dell-5000");
        asset.setAssetSn("SN" + System.currentTimeMillis());
        asset.setPurchaseDate(LocalDate.of(2026, 1, 1));
        asset.setPurchasePrice(new BigDecimal("5000.00"));
        asset.setCurrentValue(new BigDecimal("5000.00"));
        asset.setDepreciationMethod(DepreciationMethod.STRAIGHT_LINE.getCode());
        asset.setDepreciationRate(new BigDecimal("0.20"));
        asset.setUsefulLife(5);
        asset.setAccumulatedDepreciation(BigDecimal.ZERO);
        asset.setAssetStatus(status.getCode());
        asset.setLocation("办公区A");
        asset.setDepartment("研发部");
        asset.setCreatedAt(LocalDateTime.now());
        asset.setUpdatedAt(LocalDateTime.now());
        return asset;
    }

    public static Asset buildAssetWithStraightLineDepreciation() {
        Asset asset = buildIdleAsset();
        asset.setDepreciationMethod(DepreciationMethod.STRAIGHT_LINE.getCode());
        asset.setDepreciationRate(new BigDecimal("0.20"));
        asset.setUsefulLife(5);
        asset.setPurchasePrice(new BigDecimal("12000.00"));
        asset.setCurrentValue(new BigDecimal("12000.00"));
        return asset;
    }

    public static Asset buildAssetWithAcceleratedDepreciation() {
        Asset asset = buildIdleAsset();
        asset.setDepreciationMethod(DepreciationMethod.ACCELERATED.getCode());
        asset.setDepreciationRate(new BigDecimal("0.30"));
        asset.setPurchasePrice(new BigDecimal("10000.00"));
        asset.setCurrentValue(new BigDecimal("8000.00"));
        asset.setAccumulatedDepreciation(new BigDecimal("2000.00"));
        return asset;
    }

    public static Asset buildAssetWithDoubleDecliningDepreciation() {
        Asset asset = buildIdleAsset();
        asset.setDepreciationMethod(DepreciationMethod.DOUBLE_DECLINING.getCode());
        asset.setUsefulLife(5);
        asset.setPurchasePrice(new BigDecimal("10000.00"));
        asset.setCurrentValue(new BigDecimal("10000.00"));
        return asset;
    }

    public static Asset buildNewAsset() {
        Asset asset = buildIdleAsset();
        asset.setCreatedAt(LocalDateTime.now().minusDays(15));
        asset.setAssetId("asset_new_" + UUID.randomUUID().toString().substring(0, 8));
        return asset;
    }

    public static Asset buildOldAsset() {
        Asset asset = buildIdleAsset();
        asset.setCreatedAt(LocalDateTime.now().minusMonths(6));
        asset.setAssetId("asset_old_" + UUID.randomUUID().toString().substring(0, 8));
        return asset;
    }

    public static Asset buildAssetWithPartialDepreciation() {
        Asset asset = buildIdleAsset();
        asset.setDepreciationMethod(DepreciationMethod.STRAIGHT_LINE.getCode());
        asset.setDepreciationRate(new BigDecimal("0.20"));
        asset.setUsefulLife(5);
        asset.setPurchasePrice(new BigDecimal("12000.00"));
        asset.setAccumulatedDepreciation(new BigDecimal("2400.00"));
        asset.setCurrentValue(new BigDecimal("9600.00"));
        return asset;
    }

    public static UsageRecord buildActiveUsageRecord() {
        UsageRecord record = new UsageRecord();
        record.setUsageId("usage_" + UUID.randomUUID().toString().substring(0, 8));
        record.setAssetId(TEST_ASSET_ID);
        record.setUserId(TEST_USER_ID_1);
        record.setUsageType("borrow");
        record.setUsageStart(LocalDateTime.now().minusDays(3));
        record.setExpectedReturn(LocalDate.now().plusMonths(1));
        record.setUsageStatus(UsageStatus.ACTIVE.getCode());
        record.setCreatedAt(LocalDateTime.now().minusDays(3));
        return record;
    }

    public static UsageRecord buildReturnedUsageRecord() {
        UsageRecord record = buildActiveUsageRecord();
        record.setUsageStatus(UsageStatus.RETURNED.getCode());
        record.setActualReturn(LocalDateTime.now());
        return record;
    }

    public static DepreciationRecord buildDepreciationRecord(String period) {
        DepreciationRecord record = new DepreciationRecord();
        record.setDepreciationId("depreciation_" + UUID.randomUUID().toString().substring(0, 8));
        record.setAssetId(TEST_ASSET_ID);
        record.setDepreciationPeriod(period);
        record.setDepreciationValue(new BigDecimal("83.33"));
        record.setAccumulatedDepreciation(new BigDecimal("83.33"));
        record.setCurrentValue(new BigDecimal("4916.67"));
        return record;
    }

    public static DepreciationRecord buildDepreciationRecordForMonths(String period, int months) {
        BigDecimal monthlyDepreciation = new BigDecimal("83.33");
        BigDecimal accumulated = monthlyDepreciation.multiply(BigDecimal.valueOf(months));
        
        DepreciationRecord record = new DepreciationRecord();
        record.setDepreciationId("depreciation_" + UUID.randomUUID().toString().substring(0, 8));
        record.setAssetId(TEST_ASSET_ID);
        record.setDepreciationPeriod(period);
        record.setDepreciationValue(monthlyDepreciation);
        record.setAccumulatedDepreciation(accumulated);
        record.setCurrentValue(new BigDecimal("5000.00").subtract(accumulated));
        return record;
    }

    public static InventoryCheck buildInProgressInventoryCheck() {
        InventoryCheck check = new InventoryCheck();
        check.setCheckId(TEST_CHECK_ID);
        check.setCheckType("full");
        check.setCheckDepartment("研发部");
        check.setCheckStatus("in_progress");
        check.setTotalAssets(50);
        check.setCheckedAssets(45);
        check.setMatchedAssets(40);
        check.setDiffAssets(5);
        check.setCreatedAt(LocalDateTime.now());
        return check;
    }

    public static InventoryCheck buildCompletedInventoryCheck() {
        InventoryCheck check = buildInProgressInventoryCheck();
        check.setCheckStatus("completed");
        check.setMatchedAssets(50);
        check.setDiffAssets(0);
        check.setCheckedAt(LocalDateTime.now());
        return check;
    }

    public static InventoryDifference buildLocationDifference() {
        InventoryDifference diff = new InventoryDifference();
        diff.setDiffId(TEST_DIFF_ID);
        diff.setCheckId(TEST_CHECK_ID);
        diff.setAssetId(TEST_ASSET_ID);
        diff.setSystemLocation("办公区A");
        diff.setActualLocation("办公区B");
        diff.setDiffType("location_diff");
        diff.setDiffStatus("pending");
        diff.setCreatedAt(LocalDateTime.now());
        return diff;
    }

    public static InventoryDifference buildStatusDifference() {
        InventoryDifference diff = new InventoryDifference();
        diff.setDiffId("diff_status_" + UUID.randomUUID().toString().substring(0, 8));
        diff.setCheckId(TEST_CHECK_ID);
        diff.setAssetId("asset_status_" + UUID.randomUUID().toString().substring(0, 8));
        diff.setSystemLocation("办公区A");
        diff.setActualLocation("办公区A");
        diff.setDiffType("status_diff");
        diff.setDiffStatus("pending");
        diff.setCreatedAt(LocalDateTime.now());
        return diff;
    }

    public static InventoryDifference buildQuantityDifference() {
        InventoryDifference diff = new InventoryDifference();
        diff.setDiffId("diff_quantity_" + UUID.randomUUID().toString().substring(0, 8));
        diff.setCheckId(TEST_CHECK_ID);
        diff.setAssetId("asset_missing_" + UUID.randomUUID().toString().substring(0, 8));
        diff.setSystemLocation("办公区A");
        diff.setActualLocation(null);
        diff.setDiffType("quantity_diff");
        diff.setDiffStatus("pending");
        diff.setCreatedAt(LocalDateTime.now());
        return diff;
    }

    public static InventoryDifference buildHandledDifference() {
        InventoryDifference diff = buildLocationDifference();
        diff.setDiffStatus("handled");
        diff.setHandledAt(LocalDateTime.now());
        return diff;
    }

    public static ScrapRecord buildApprovedScrapRecord() {
        ScrapRecord record = new ScrapRecord();
        record.setScrapId("scrap_" + UUID.randomUUID().toString().substring(0, 8));
        record.setAssetId(TEST_ASSET_ID);
        record.setScrapReason("设备老化无法使用");
        record.setScrapStatus(ScrapStatus.APPROVED.getCode());
        record.setResidualValue(new BigDecimal("500.00"));
        record.setScrapTime(LocalDateTime.now());
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }

    public static MaintenanceRecord buildMaintenanceRecord() {
        MaintenanceRecord record = new MaintenanceRecord();
        record.setMaintenanceId("maint_" + UUID.randomUUID().toString().substring(0, 8));
        record.setAssetId(TEST_ASSET_ID);
        record.setMaintenanceType("regular");
        record.setMaintenanceDate(LocalDate.now());
        record.setMaintenanceContent("设备清洁与检查");
        record.setMaintenanceCost(new BigDecimal("100.00"));
        record.setNextMaintenance(LocalDate.now().plusMonths(1));
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }

    public static AssetStatistic buildAssetStatistic() {
        AssetStatistic stat = new AssetStatistic();
        stat.setStatId("stat_" + UUID.randomUUID().toString().substring(0, 8));
        stat.setStatDate(LocalDate.now());
        stat.setTotalAssets(100);
        stat.setInUseAssets(80);
        stat.setIdleAssets(10);
        stat.setMaintenanceAssets(5);
        stat.setScrapedAssets(5);
        stat.setTotalValue(new BigDecimal("500000.00"));
        stat.setCreatedAt(LocalDateTime.now());
        return stat;
    }

    public static AssetHistory buildAssetHistory(String actionType) {
        AssetHistory history = new AssetHistory();
        history.setHistoryId("history_" + UUID.randomUUID().toString().substring(0, 8));
        history.setAssetId(TEST_ASSET_ID);
        history.setActionType(actionType);
        history.setActionDetails("测试操作: " + actionType);
        history.setOperatorId(TEST_OPERATOR_ID);
        history.setCreatedAt(LocalDateTime.now());
        return history;
    }

    public static List<Asset> buildMultipleIdleAssets(int count) {
        List<Asset> assets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Asset asset = buildIdleAsset();
            asset.setAssetId("asset_" + UUID.randomUUID().toString().substring(0, 8) + "_" + i);
            asset.setAssetName("办公电脑_" + i);
            assets.add(asset);
        }
        return assets;
    }

    public static List<Asset> buildMixedAgeAssets() {
        List<Asset> assets = new ArrayList<>();
        
        Asset newAsset1 = buildNewAsset();
        assets.add(newAsset1);
        
        Asset newAsset2 = buildNewAsset();
        newAsset2.setAssetId("asset_new2_" + UUID.randomUUID().toString().substring(0, 8));
        assets.add(newAsset2);
        
        Asset oldAsset1 = buildOldAsset();
        assets.add(oldAsset1);
        
        return assets;
    }

    public static List<InventoryDifference> buildMultipleDifferences() {
        List<InventoryDifference> diffs = new ArrayList<>();
        diffs.add(buildLocationDifference());
        diffs.add(buildStatusDifference());
        diffs.add(buildQuantityDifference());
        return diffs;
    }

    public static List<DepreciationRecord> buildMonthlyDepreciationRecords(String startPeriod, int months) {
        List<DepreciationRecord> records = new ArrayList<>();
        LocalDate date = parsePeriod(startPeriod);
        
        for (int i = 0; i < months; i++) {
            String period = formatPeriod(date.plusMonths(i));
            records.add(buildDepreciationRecordForMonths(period, i + 1));
        }
        
        return records;
    }

    private static LocalDate parsePeriod(String period) {
        String[] parts = period.split("-");
        int year = Integer.parseInt(parts[0]);
        int month = Integer.parseInt(parts[1]);
        return LocalDate.of(year, month, 1);
    }

    private static String formatPeriod(LocalDate date) {
        return String.format("%04d-%02d", date.getYear(), date.getMonthValue());
    }

    public static class AssetUseRequestBuilder {
        private String assetId = TEST_ASSET_ID;
        private String userId = TEST_USER_ID_1;
        private String usageType = "borrow";
        private LocalDate expectedReturn = LocalDate.now().plusMonths(1);
        private String operatorId = TEST_OPERATOR_ID;

        public AssetUseRequestBuilder assetId(String assetId) {
            this.assetId = assetId;
            return this;
        }

        public AssetUseRequestBuilder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public AssetUseRequestBuilder usageType(String usageType) {
            this.usageType = usageType;
            return this;
        }

        public AssetUseRequestBuilder expectedReturn(LocalDate expectedReturn) {
            this.expectedReturn = expectedReturn;
            return this;
        }

        public AssetUseRequestBuilder operatorId(String operatorId) {
            this.operatorId = operatorId;
            return this;
        }

        public com.assetmanage.dto.AssetUseRequest build() {
            com.assetmanage.dto.AssetUseRequest request = new com.assetmanage.dto.AssetUseRequest();
            request.setAssetId(assetId);
            request.setUserId(userId);
            request.setUsageType(usageType);
            request.setExpectedReturn(expectedReturn);
            request.setOperatorId(operatorId);
            return request;
        }
    }

    public static class AssetRegisterRequestBuilder {
        private String assetName = "办公电脑";
        private String assetType = "computer";
        private String assetCategory = "办公设备";
        private String assetModel = "Dell-5000";
        private String assetSn = "SN" + System.currentTimeMillis();
        private LocalDate purchaseDate = LocalDate.of(2026, 1, 1);
        private BigDecimal purchasePrice = new BigDecimal("5000.00");
        private String depreciationMethod = DepreciationMethod.STRAIGHT_LINE.getCode();
        private BigDecimal depreciationRate = new BigDecimal("0.20");
        private Integer usefulLife = 5;
        private String location = "办公区A";
        private String department = "研发部";

        public com.assetmanage.dto.AssetRegisterRequest build() {
            com.assetmanage.dto.AssetRegisterRequest request = new com.assetmanage.dto.AssetRegisterRequest();
            request.setAssetName(assetName);
            request.setAssetType(assetType);
            request.setAssetCategory(assetCategory);
            request.setAssetModel(assetModel);
            request.setAssetSn(assetSn);
            request.setPurchaseDate(purchaseDate);
            request.setPurchasePrice(purchasePrice);
            request.setDepreciationMethod(depreciationMethod);
            request.setDepreciationRate(depreciationRate);
            request.setUsefulLife(usefulLife);
            request.setLocation(location);
            request.setDepartment(department);
            return request;
        }

        public AssetRegisterRequestBuilder assetName(String assetName) {
            this.assetName = assetName;
            return this;
        }

        public AssetRegisterRequestBuilder purchasePrice(BigDecimal price) {
            this.purchasePrice = price;
            return this;
        }

        public AssetRegisterRequestBuilder depreciationMethod(String method) {
            this.depreciationMethod = method;
            return this;
        }

        public AssetRegisterRequestBuilder depreciationRate(BigDecimal rate) {
            this.depreciationRate = rate;
            return this;
        }

        public AssetRegisterRequestBuilder usefulLife(Integer years) {
            this.usefulLife = years;
            return this;
        }
    }
}
