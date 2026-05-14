package com.assetinventory.builder;

import com.assetinventory.entity.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TestDataBuilder {

    private TestDataBuilder() {
    }

    public static String generateId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static Instant now() {
        return Instant.now();
    }

    public static LocalDate today() {
        return LocalDate.now();
    }

    public static AssetBuilder assetBuilder() {
        return new AssetBuilder();
    }

    public static AssetCategoryBuilder categoryBuilder() {
        return new AssetCategoryBuilder();
    }

    public static InventoryTaskBuilder taskBuilder() {
        return new InventoryTaskBuilder();
    }

    public static InventoryRecordBuilder recordBuilder() {
        return new InventoryRecordBuilder();
    }

    public static InventoryDifferenceBuilder differenceBuilder() {
        return new InventoryDifferenceBuilder();
    }

    public static InventoryPersonBuilder personBuilder() {
        return new InventoryPersonBuilder();
    }

    public static InventoryPlanBuilder planBuilder() {
        return new InventoryPlanBuilder();
    }

    public static InventoryStatisticsBuilder statisticsBuilder() {
        return new InventoryStatisticsBuilder();
    }

    public static class AssetBuilder {
        private String assetId = generateId("asset");
        private String assetName = "测试资产";
        private String assetCategory = "equipment";
        private int assetQuantity = 100;
        private String assetLocation = "测试位置";
        private String assetStatus = "uncounted";
        private double assetValue = 10000.0;
        private Instant registeredAt = now();
        private Instant lastCountedAt = null;

        public AssetBuilder assetId(String assetId) {
            this.assetId = assetId;
            return this;
        }

        public AssetBuilder assetName(String assetName) {
            this.assetName = assetName;
            return this;
        }

        public AssetBuilder assetCategory(String assetCategory) {
            this.assetCategory = assetCategory;
            return this;
        }

        public AssetBuilder assetQuantity(int assetQuantity) {
            this.assetQuantity = assetQuantity;
            return this;
        }

        public AssetBuilder assetLocation(String assetLocation) {
            this.assetLocation = assetLocation;
            return this;
        }

        public AssetBuilder assetStatus(String assetStatus) {
            this.assetStatus = assetStatus;
            return this;
        }

        public AssetBuilder assetValue(double assetValue) {
            this.assetValue = assetValue;
            return this;
        }

        public AssetBuilder registeredAt(Instant registeredAt) {
            this.registeredAt = registeredAt;
            return this;
        }

        public AssetBuilder lastCountedAt(Instant lastCountedAt) {
            this.lastCountedAt = lastCountedAt;
            return this;
        }

        public Asset build() {
            Asset asset = new Asset();
            asset.setAssetId(assetId);
            asset.setAssetName(assetName);
            asset.setAssetCategory(assetCategory);
            asset.setAssetQuantity(assetQuantity);
            asset.setAssetLocation(assetLocation);
            asset.setAssetStatus(assetStatus);
            asset.setAssetValue(assetValue);
            asset.setRegisteredAt(registeredAt);
            asset.setLastCountedAt(lastCountedAt);
            return asset;
        }

        public Asset buildUncountedAsset() {
            return assetBuilder()
                    .assetStatus("uncounted")
                    .lastCountedAt(null)
                    .build();
        }

        public Asset buildCountedAsset() {
            return assetBuilder()
                    .assetStatus("counted")
                    .lastCountedAt(now())
                    .build();
        }

        public Asset buildAdjustedAsset() {
            return assetBuilder()
                    .assetStatus("adjusted")
                    .lastCountedAt(now())
                    .build();
        }

        public List<Asset> buildMultiple(int count) {
            List<Asset> assets = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                assets.add(assetBuilder()
                        .assetId(generateId("asset"))
                        .assetName(assetName + (i + 1))
                        .build());
            }
            return assets;
        }

        public List<Asset> buildAssetsByStatus(int count, String status) {
            List<Asset> assets = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                assets.add(assetBuilder()
                        .assetId(generateId("asset"))
                        .assetName(assetName + (i + 1))
                        .assetStatus(status)
                        .lastCountedAt("uncounted".equals(status) ? null : now())
                        .build());
            }
            return assets;
        }
    }

    public static class AssetCategoryBuilder {
        private String categoryId = generateId("category");
        private String categoryCode = "test";
        private String categoryName = "测试类别";
        private String categoryDescription = "测试类别描述";
        private String categoryStatus = "active";
        private Instant createdAt = now();

        public AssetCategoryBuilder categoryId(String categoryId) {
            this.categoryId = categoryId;
            return this;
        }

        public AssetCategoryBuilder categoryCode(String categoryCode) {
            this.categoryCode = categoryCode;
            return this;
        }

        public AssetCategoryBuilder categoryName(String categoryName) {
            this.categoryName = categoryName;
            return this;
        }

        public AssetCategoryBuilder categoryDescription(String categoryDescription) {
            this.categoryDescription = categoryDescription;
            return this;
        }

        public AssetCategoryBuilder categoryStatus(String categoryStatus) {
            this.categoryStatus = categoryStatus;
            return this;
        }

        public AssetCategoryBuilder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public AssetCategory build() {
            AssetCategory category = new AssetCategory();
            category.setCategoryId(categoryId);
            category.setCategoryCode(categoryCode);
            category.setCategoryName(categoryName);
            category.setCategoryDescription(categoryDescription);
            category.setCategoryStatus(categoryStatus);
            category.setCreatedAt(createdAt);
            return category;
        }

        public AssetCategory buildEquipmentCategory() {
            return categoryBuilder()
                    .categoryCode("equipment")
                    .categoryName("设备类")
                    .categoryDescription("电子设备、办公设备等")
                    .build();
        }

        public AssetCategory buildFurnitureCategory() {
            return categoryBuilder()
                    .categoryCode("furniture")
                    .categoryName("家具类")
                    .categoryDescription("办公家具、储物柜等")
                    .build();
        }

        public AssetCategory buildSoftwareCategory() {
            return categoryBuilder()
                    .categoryCode("software")
                    .categoryName("软件类")
                    .categoryDescription("软件许可、授权等")
                    .build();
        }

        public List<AssetCategory> buildDefaultCategories() {
            List<AssetCategory> categories = new ArrayList<>();
            categories.add(buildEquipmentCategory());
            categories.add(buildFurnitureCategory());
            categories.add(buildSoftwareCategory());
            return categories;
        }
    }

    public static class InventoryTaskBuilder {
        private String taskId = generateId("task");
        private String planId = generateId("plan");
        private String taskRange = "测试区域";
        private String taskStatus = "pending";
        private String assignedPerson = null;
        private Instant assignedAt = null;
        private Instant createdAt = now();

        public InventoryTaskBuilder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public InventoryTaskBuilder planId(String planId) {
            this.planId = planId;
            return this;
        }

        public InventoryTaskBuilder taskRange(String taskRange) {
            this.taskRange = taskRange;
            return this;
        }

        public InventoryTaskBuilder taskStatus(String taskStatus) {
            this.taskStatus = taskStatus;
            return this;
        }

        public InventoryTaskBuilder assignedPerson(String assignedPerson) {
            this.assignedPerson = assignedPerson;
            return this;
        }

        public InventoryTaskBuilder assignedAt(Instant assignedAt) {
            this.assignedAt = assignedAt;
            return this;
        }

        public InventoryTaskBuilder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public InventoryTask build() {
            InventoryTask task = new InventoryTask();
            task.setTaskId(taskId);
            task.setPlanId(planId);
            task.setTaskRange(taskRange);
            task.setTaskStatus(taskStatus);
            task.setAssignedPerson(assignedPerson);
            task.setAssignedAt(assignedAt);
            task.setCreatedAt(createdAt);
            return task;
        }

        public InventoryTask buildPendingTask() {
            return taskBuilder()
                    .taskStatus("pending")
                    .assignedPerson(null)
                    .assignedAt(null)
                    .build();
        }

        public InventoryTask buildAssignedTask(String personId) {
            return taskBuilder()
                    .taskStatus("assigned")
                    .assignedPerson(personId)
                    .assignedAt(now())
                    .build();
        }

        public InventoryTask buildCompletedTask(String personId) {
            return taskBuilder()
                    .taskStatus("completed")
                    .assignedPerson(personId)
                    .assignedAt(now().minusSeconds(3600))
                    .build();
        }

        public List<InventoryTask> buildMultiple(int count, String status) {
            List<InventoryTask> tasks = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                tasks.add(taskBuilder()
                        .taskId(generateId("task"))
                        .taskRange(taskRange + (i + 1))
                        .taskStatus(status)
                        .build());
            }
            return tasks;
        }
    }

    public static class InventoryRecordBuilder {
        private String countId = generateId("count");
        private String taskId = generateId("task");
        private String assetId = generateId("asset");
        private String countPerson = generateId("person");
        private int countQuantity = 100;
        private String countLocation = "测试位置";
        private String countStatus = "normal";
        private Instant countTime = now();

        public InventoryRecordBuilder countId(String countId) {
            this.countId = countId;
            return this;
        }

        public InventoryRecordBuilder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public InventoryRecordBuilder assetId(String assetId) {
            this.assetId = assetId;
            return this;
        }

        public InventoryRecordBuilder countPerson(String countPerson) {
            this.countPerson = countPerson;
            return this;
        }

        public InventoryRecordBuilder countQuantity(int countQuantity) {
            this.countQuantity = countQuantity;
            return this;
        }

        public InventoryRecordBuilder countLocation(String countLocation) {
            this.countLocation = countLocation;
            return this;
        }

        public InventoryRecordBuilder countStatus(String countStatus) {
            this.countStatus = countStatus;
            return this;
        }

        public InventoryRecordBuilder countTime(Instant countTime) {
            this.countTime = countTime;
            return this;
        }

        public InventoryRecord build() {
            InventoryRecord record = new InventoryRecord();
            record.setCountId(countId);
            record.setTaskId(taskId);
            record.setAssetId(assetId);
            record.setCountPerson(countPerson);
            record.setCountQuantity(countQuantity);
            record.setCountLocation(countLocation);
            record.setCountStatus(countStatus);
            record.setCountTime(countTime);
            return record;
        }

        public InventoryRecord buildNormalRecord(int systemQuantity, String location) {
            return recordBuilder()
                    .countQuantity(systemQuantity)
                    .countLocation(location)
                    .countStatus("normal")
                    .build();
        }

        public InventoryRecord buildQuantityDiffRecord(int systemQuantity, int actualQuantity, String location) {
            return recordBuilder()
                    .countQuantity(actualQuantity)
                    .countLocation(location)
                    .countStatus("difference")
                    .build();
        }

        public InventoryRecord buildLocationDiffRecord(int quantity, String systemLocation, String actualLocation) {
            return recordBuilder()
                    .countQuantity(quantity)
                    .countLocation(actualLocation)
                    .countStatus("difference")
                    .build();
        }

        public List<InventoryRecord> buildMultiple(int count, String status) {
            List<InventoryRecord> records = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                records.add(recordBuilder()
                        .countId(generateId("count"))
                        .assetId(generateId("asset"))
                        .countStatus(status)
                        .build());
            }
            return records;
        }
    }

    public static class InventoryDifferenceBuilder {
        private String diffId = generateId("diff");
        private String countId = generateId("count");
        private String assetId = generateId("asset");
        private String diffType = "quantity";
        private int diffSystem = 100;
        private int diffActual = 95;
        private int diffValue = 5;
        private String diffStatus = "pending";
        private Instant diffTime = now();

        public InventoryDifferenceBuilder diffId(String diffId) {
            this.diffId = diffId;
            return this;
        }

        public InventoryDifferenceBuilder countId(String countId) {
            this.countId = countId;
            return this;
        }

        public InventoryDifferenceBuilder assetId(String assetId) {
            this.assetId = assetId;
            return this;
        }

        public InventoryDifferenceBuilder diffType(String diffType) {
            this.diffType = diffType;
            return this;
        }

        public InventoryDifferenceBuilder diffSystem(int diffSystem) {
            this.diffSystem = diffSystem;
            return this;
        }

        public InventoryDifferenceBuilder diffActual(int diffActual) {
            this.diffActual = diffActual;
            return this;
        }

        public InventoryDifferenceBuilder diffValue(int diffValue) {
            this.diffValue = diffValue;
            return this;
        }

        public InventoryDifferenceBuilder diffStatus(String diffStatus) {
            this.diffStatus = diffStatus;
            return this;
        }

        public InventoryDifferenceBuilder diffTime(Instant diffTime) {
            this.diffTime = diffTime;
            return this;
        }

        public InventoryDifference build() {
            InventoryDifference diff = new InventoryDifference();
            diff.setDiffId(diffId);
            diff.setCountId(countId);
            diff.setAssetId(assetId);
            diff.setDiffType(diffType);
            diff.setDiffSystem(diffSystem);
            diff.setDiffActual(diffActual);
            diff.setDiffValue(diffValue);
            diff.setDiffStatus(diffStatus);
            diff.setDiffTime(diffTime);
            return diff;
        }

        public InventoryDifference buildQuantityDiff(int systemQty, int actualQty) {
            return differenceBuilder()
                    .diffType("quantity")
                    .diffSystem(systemQty)
                    .diffActual(actualQty)
                    .diffValue(Math.abs(systemQty - actualQty))
                    .diffStatus("pending")
                    .build();
        }

        public InventoryDifference buildLocationDiff() {
            return differenceBuilder()
                    .diffType("location")
                    .diffSystem(100)
                    .diffActual(100)
                    .diffValue(0)
                    .diffStatus("pending")
                    .build();
        }

        public InventoryDifference buildCriticalDiff() {
            return differenceBuilder()
                    .diffType("quantity")
                    .diffSystem(100)
                    .diffActual(40)
                    .diffValue(60)
                    .diffStatus("pending")
                    .build();
        }

        public InventoryDifference buildHighDiff() {
            return differenceBuilder()
                    .diffType("quantity")
                    .diffSystem(100)
                    .diffActual(70)
                    .diffValue(30)
                    .diffStatus("pending")
                    .build();
        }

        public InventoryDifference buildMediumDiff() {
            return differenceBuilder()
                    .diffType("quantity")
                    .diffSystem(100)
                    .diffActual(85)
                    .diffValue(15)
                    .diffStatus("pending")
                    .build();
        }

        public InventoryDifference buildLowDiff() {
            return differenceBuilder()
                    .diffType("quantity")
                    .diffSystem(100)
                    .diffActual(95)
                    .diffValue(5)
                    .diffStatus("pending")
                    .build();
        }

        public InventoryDifference buildConfirmedDiff() {
            return differenceBuilder()
                    .diffStatus("confirmed")
                    .build();
        }

        public InventoryDifference buildRejectedDiff() {
            return differenceBuilder()
                    .diffStatus("rejected")
                    .build();
        }

        public List<InventoryDifference> buildMultipleBySeverity(int critical, int high, int medium, int low) {
            List<InventoryDifference> diffs = new ArrayList<>();
            for (int i = 0; i < critical; i++) {
                diffs.add(buildCriticalDiff());
            }
            for (int i = 0; i < high; i++) {
                diffs.add(buildHighDiff());
            }
            for (int i = 0; i < medium; i++) {
                diffs.add(buildMediumDiff());
            }
            for (int i = 0; i < low; i++) {
                diffs.add(buildLowDiff());
            }
            return diffs;
        }
    }

    public static class InventoryPersonBuilder {
        private String personId = generateId("person");
        private String personName = "测试人员";
        private String personDepartment = "测试部门";
        private String personStatus = "active";
        private int taskCount = 0;
        private Instant createdAt = now();

        public InventoryPersonBuilder personId(String personId) {
            this.personId = personId;
            return this;
        }

        public InventoryPersonBuilder personName(String personName) {
            this.personName = personName;
            return this;
        }

        public InventoryPersonBuilder personDepartment(String personDepartment) {
            this.personDepartment = personDepartment;
            return this;
        }

        public InventoryPersonBuilder personStatus(String personStatus) {
            this.personStatus = personStatus;
            return this;
        }

        public InventoryPersonBuilder taskCount(int taskCount) {
            this.taskCount = taskCount;
            return this;
        }

        public InventoryPersonBuilder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public InventoryPerson build() {
            InventoryPerson person = new InventoryPerson();
            person.setPersonId(personId);
            person.setPersonName(personName);
            person.setPersonDepartment(personDepartment);
            person.setPersonStatus(personStatus);
            person.setTaskCount(taskCount);
            person.setCreatedAt(createdAt);
            return person;
        }

        public InventoryPerson buildActivePerson() {
            return personBuilder()
                    .personStatus("active")
                    .taskCount(0)
                    .build();
        }

        public InventoryPerson buildBusyPerson(int taskCount) {
            return personBuilder()
                    .personStatus("active")
                    .taskCount(taskCount)
                    .build();
        }

        public InventoryPerson buildInactivePerson() {
            return personBuilder()
                    .personStatus("inactive")
                    .taskCount(0)
                    .build();
        }

        public InventoryPerson buildOverloadedPerson() {
            return personBuilder()
                    .personStatus("active")
                    .taskCount(10)
                    .build();
        }

        public List<InventoryPerson> buildActiveTeam(int count) {
            List<InventoryPerson> persons = new ArrayList<>();
            String[] departments = {"资产管理部", "财务部", "IT部门", "行政部"};
            String[] names = {"张三", "李四", "王五", "赵六", "钱七", "孙八"};
            for (int i = 0; i < count; i++) {
                persons.add(personBuilder()
                        .personId(generateId("person"))
                        .personName(names[i % names.length])
                        .personDepartment(departments[i % departments.length])
                        .personStatus("active")
                        .taskCount(i % 3)
                        .build());
            }
            return persons;
        }
    }

    public static class InventoryPlanBuilder {
        private String planId = generateId("plan");
        private String planName = "测试盘点计划";
        private String planRange = "全公司范围";
        private LocalDate planStart = today();
        private LocalDate planEnd = today().plusDays(5);
        private String planStatus = "active";
        private Instant createdAt = now();

        public InventoryPlanBuilder planId(String planId) {
            this.planId = planId;
            return this;
        }

        public InventoryPlanBuilder planName(String planName) {
            this.planName = planName;
            return this;
        }

        public InventoryPlanBuilder planRange(String planRange) {
            this.planRange = planRange;
            return this;
        }

        public InventoryPlanBuilder planStart(LocalDate planStart) {
            this.planStart = planStart;
            return this;
        }

        public InventoryPlanBuilder planEnd(LocalDate planEnd) {
            this.planEnd = planEnd;
            return this;
        }

        public InventoryPlanBuilder planStatus(String planStatus) {
            this.planStatus = planStatus;
            return this;
        }

        public InventoryPlanBuilder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public InventoryPlan build() {
            InventoryPlan plan = new InventoryPlan();
            plan.setPlanId(planId);
            plan.setPlanName(planName);
            plan.setPlanRange(planRange);
            plan.setPlanStart(planStart);
            plan.setPlanEnd(planEnd);
            plan.setPlanStatus(planStatus);
            plan.setCreatedAt(createdAt);
            return plan;
        }

        public InventoryPlan buildActivePlan() {
            return planBuilder()
                    .planStatus("active")
                    .build();
        }

        public InventoryPlan buildClosedPlan() {
            return planBuilder()
                    .planStatus("closed")
                    .build();
        }

        public InventoryPlan buildMonthlyPlan() {
            return planBuilder()
                    .planName(today().getYear() + "年" + today().getMonthValue() + "月月度盘点")
                    .planRange("全公司范围")
                    .planStart(today().withDayOfMonth(1))
                    .planEnd(today().withDayOfMonth(today().lengthOfMonth()))
                    .planStatus("active")
                    .build();
        }
    }

    public static class InventoryStatisticsBuilder {
        private String statId = generateId("stat");
        private String statMonth = today().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        private int taskCount = 0;
        private int countCount = 0;
        private int diffCount = 0;
        private int processedDiffCount = 0;
        private double accuracyRate = 1.0;

        public InventoryStatisticsBuilder statId(String statId) {
            this.statId = statId;
            return this;
        }

        public InventoryStatisticsBuilder statMonth(String statMonth) {
            this.statMonth = statMonth;
            return this;
        }

        public InventoryStatisticsBuilder taskCount(int taskCount) {
            this.taskCount = taskCount;
            return this;
        }

        public InventoryStatisticsBuilder countCount(int countCount) {
            this.countCount = countCount;
            return this;
        }

        public InventoryStatisticsBuilder diffCount(int diffCount) {
            this.diffCount = diffCount;
            return this;
        }

        public InventoryStatisticsBuilder processedDiffCount(int processedDiffCount) {
            this.processedDiffCount = processedDiffCount;
            return this;
        }

        public InventoryStatisticsBuilder accuracyRate(double accuracyRate) {
            this.accuracyRate = accuracyRate;
            return this;
        }

        public InventoryStatistics build() {
            InventoryStatistics stats = new InventoryStatistics();
            stats.setStatId(statId);
            stats.setStatMonth(statMonth);
            stats.setTaskCount(taskCount);
            stats.setCountCount(countCount);
            stats.setDiffCount(diffCount);
            stats.setProcessedDiffCount(processedDiffCount);
            stats.setAccuracyRate(accuracyRate);
            return stats;
        }

        public InventoryStatistics buildPerfectStats() {
            return statisticsBuilder()
                    .taskCount(10)
                    .countCount(100)
                    .diffCount(0)
                    .processedDiffCount(0)
                    .accuracyRate(1.0)
                    .build();
        }

        public InventoryStatistics buildNormalStats() {
            return statisticsBuilder()
                    .taskCount(10)
                    .countCount(100)
                    .diffCount(5)
                    .processedDiffCount(3)
                    .accuracyRate(0.95)
                    .build();
        }

        public InventoryStatistics buildProblemStats() {
            return statisticsBuilder()
                    .taskCount(10)
                    .countCount(100)
                    .diffCount(20)
                    .processedDiffCount(5)
                    .accuracyRate(0.80)
                    .build();
        }
    }
}
