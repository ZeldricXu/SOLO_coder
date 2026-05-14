package com.assetinventory.service;

import com.assetinventory.builder.TestDataBuilder;
import com.assetinventory.entity.Asset;
import com.assetinventory.entity.AssetCategory;
import com.assetinventory.exception.InventoryException;
import com.assetinventory.repository.AssetRepository;
import com.assetinventory.repository.AssetCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("资产管理模块单元测试")
class AssetServiceTest {

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private AssetCategoryRepository categoryRepository;

    @InjectMocks
    private AssetService assetService;

    @InjectMocks
    private CategoryService categoryService;

    private Asset testAsset;
    private AssetCategory testCategory;

    @BeforeEach
    void setUp() {
        testAsset = TestDataBuilder.assetBuilder().buildUncountedAsset();
        testCategory = TestDataBuilder.categoryBuilder().buildEquipmentCategory();
    }

    @Test
    @DisplayName("测试创建资产 - 成功创建未盘点状态的资产")
    void testCreateAsset_Success() {
        when(assetRepository.save(any(Asset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Asset created = assetService.createAsset(
                testAsset.getAssetName(),
                testAsset.getAssetCategory(),
                testAsset.getAssetQuantity(),
                testAsset.getAssetLocation(),
                testAsset.getAssetValue()
        );

        assertNotNull(created);
        assertEquals("uncounted", created.getAssetStatus());
        assertNull(created.getLastCountedAt());
        verify(assetRepository, times(1)).save(any(Asset.class));
    }

    @Test
    @DisplayName("测试资产状态流转 - 未盘点 -> 已盘点")
    void testAssetStatusTransition_UncountedToCounted() {
        Asset uncountedAsset = TestDataBuilder.assetBuilder().buildUncountedAsset();
        when(assetRepository.findByAssetId(anyString())).thenReturn(Optional.of(uncountedAsset));
        when(assetRepository.save(any(Asset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Asset updated = assetService.updateAssetStatus(uncountedAsset.getAssetId(), "counted");

        assertEquals("counted", updated.getAssetStatus());
        verify(assetRepository, times(1)).findByAssetId(uncountedAsset.getAssetId());
        verify(assetRepository, times(1)).save(any(Asset.class));
    }

    @Test
    @DisplayName("测试资产状态流转 - 已盘点 -> 已调整")
    void testAssetStatusTransition_CountedToAdjusted() {
        Asset countedAsset = TestDataBuilder.assetBuilder().buildCountedAsset();
        when(assetRepository.findByAssetId(anyString())).thenReturn(Optional.of(countedAsset));
        when(assetRepository.save(any(Asset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Asset updated = assetService.updateAssetStatus(countedAsset.getAssetId(), "adjusted");

        assertEquals("adjusted", updated.getAssetStatus());
    }

    @Test
    @DisplayName("测试资产状态流转 - 完整生命周期")
    void testAssetStatusTransition_FullLifecycle() {
        String assetId = testAsset.getAssetId();
        Asset asset = TestDataBuilder.assetBuilder()
                .assetId(assetId)
                .buildUncountedAsset();

        when(assetRepository.findByAssetId(assetId)).thenReturn(Optional.of(asset));
        when(assetRepository.save(any(Asset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Asset afterCreate = asset;
        assertEquals("uncounted", afterCreate.getAssetStatus());

        Asset afterCount = assetService.updateAssetStatus(assetId, "counted");
        assertEquals("counted", afterCount.getAssetStatus());

        Asset afterAdjust = assetService.updateAssetStatus(assetId, "adjusted");
        assertEquals("adjusted", afterAdjust.getAssetStatus());

        verify(assetRepository, times(2)).save(any(Asset.class));
    }

    @Test
    @DisplayName("测试更新资产盘点时间")
    void testUpdateLastCountedAt() {
        Instant beforeTime = Instant.now().minusSeconds(3600);
        testAsset.setLastCountedAt(beforeTime);
        when(assetRepository.findByAssetId(anyString())).thenReturn(Optional.of(testAsset));
        when(assetRepository.save(any(Asset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Instant newTime = Instant.now();
        Asset updated = assetService.updateLastCountedAt(testAsset.getAssetId(), newTime);

        assertEquals(newTime, updated.getLastCountedAt());
        assertNotEquals(beforeTime, updated.getLastCountedAt());
    }

    @Test
    @DisplayName("测试更新资产数量 - 差异调整")
    void testUpdateAssetQuantity_DifferenceAdjustment() {
        int originalQuantity = 100;
        int newQuantity = 95;
        testAsset.setAssetQuantity(originalQuantity);
        when(assetRepository.findByAssetId(anyString())).thenReturn(Optional.of(testAsset));
        when(assetRepository.save(any(Asset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Asset updated = assetService.updateAssetQuantity(testAsset.getAssetId(), newQuantity);

        assertEquals(newQuantity, updated.getAssetQuantity());
        assertNotEquals(originalQuantity, updated.getAssetQuantity());
    }

    @Test
    @DisplayName("测试更新资产位置 - 位置差异调整")
    void testUpdateAssetLocation_LocationAdjustment() {
        String originalLocation = "A栋1楼";
        String newLocation = "B栋2楼";
        testAsset.setAssetLocation(originalLocation);
        when(assetRepository.findByAssetId(anyString())).thenReturn(Optional.of(testAsset));
        when(assetRepository.save(any(Asset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Asset updated = assetService.updateAssetLocation(testAsset.getAssetId(), newLocation);

        assertEquals(newLocation, updated.getAssetLocation());
        assertNotEquals(originalLocation, updated.getAssetLocation());
    }

    @Test
    @DisplayName("测试获取资产不存在 - 抛出异常")
    void testGetAssetByIdOrThrow_NotFound() {
        when(assetRepository.findByAssetId(anyString())).thenReturn(Optional.empty());

        InventoryException exception = assertThrows(InventoryException.class,
                () -> assetService.getAssetByIdOrThrow("nonexistent_asset"));

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("资产不存在"));
    }

    @Test
    @DisplayName("测试获取所有资产")
    void testGetAllAssets() {
        List<Asset> assets = TestDataBuilder.assetBuilder().buildMultiple(5);
        when(assetRepository.findAll()).thenReturn(assets);

        List<Asset> result = assetService.getAllAssets();

        assertEquals(5, result.size());
        verify(assetRepository, times(1)).findAll();
    }

    @Test
    @DisplayName("测试按状态获取资产 - 未盘点资产")
    void testGetAssetsByStatus_Uncounted() {
        List<Asset> uncountedAssets = TestDataBuilder.assetBuilder().buildAssetsByStatus(3, "uncounted");
        when(assetRepository.findByAssetStatus("uncounted")).thenReturn(uncountedAssets);

        List<Asset> result = assetService.getAssetsByStatus("uncounted");

        assertEquals(3, result.size());
        result.forEach(asset -> assertEquals("uncounted", asset.getAssetStatus()));
    }

    @Test
    @DisplayName("测试按状态获取资产 - 已盘点资产")
    void testGetAssetsByStatus_Counted() {
        List<Asset> countedAssets = TestDataBuilder.assetBuilder().buildAssetsByStatus(2, "counted");
        when(assetRepository.findByAssetStatus("counted")).thenReturn(countedAssets);

        List<Asset> result = assetService.getAssetsByStatus("counted");

        assertEquals(2, result.size());
        result.forEach(asset -> {
            assertEquals("counted", asset.getAssetStatus());
            assertNotNull(asset.getLastCountedAt());
        });
    }

    @Test
    @DisplayName("测试按类别获取资产")
    void testGetAssetsByCategory() {
        List<Asset> equipmentAssets = TestDataBuilder.assetBuilder()
                .assetCategory("equipment")
                .buildMultiple(4);
        when(assetRepository.findByAssetCategory("equipment")).thenReturn(equipmentAssets);

        List<Asset> result = assetService.getAssetsByCategory("equipment");

        assertEquals(4, result.size());
        result.forEach(asset -> assertEquals("equipment", asset.getAssetCategory()));
    }

    @Test
    @DisplayName("测试资产类别动态加载 - 获取活跃类别")
    void testGetActiveCategories() {
        List<AssetCategory> activeCategories = TestDataBuilder.categoryBuilder().buildDefaultCategories();
        when(categoryRepository.findByCategoryStatus("active")).thenReturn(activeCategories);

        List<AssetCategory> result = categoryService.getActiveCategories();

        assertEquals(3, result.size());
        result.forEach(category -> assertEquals("active", category.getCategoryStatus()));
    }

    @Test
    @DisplayName("测试创建资产类别 - 代码重复")
    void testCreateCategory_DuplicateCode() {
        when(categoryRepository.findByCategoryCode(anyString())).thenReturn(Optional.of(testCategory));

        InventoryException exception = assertThrows(InventoryException.class,
                () -> categoryService.createCategory(
                        testCategory.getCategoryCode(),
                        testCategory.getCategoryName(),
                        testCategory.getCategoryDescription()
                ));

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("已存在"));
        verify(categoryRepository, never()).save(any(AssetCategory.class));
    }

    @Test
    @DisplayName("测试创建资产类别 - 成功")
    void testCreateCategory_Success() {
        when(categoryRepository.findByCategoryCode(anyString())).thenReturn(Optional.empty());
        when(categoryRepository.save(any(AssetCategory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AssetCategory created = categoryService.createCategory(
                "new_category",
                "新类别",
                "新类别描述"
        );

        assertNotNull(created);
        assertEquals("active", created.getCategoryStatus());
        verify(categoryRepository, times(1)).save(any(AssetCategory.class));
    }

    @Test
    @DisplayName("测试资产状态统计 - 不同状态资产数量")
    void testAssetStatusStatistics() {
        List<Asset> uncounted = TestDataBuilder.assetBuilder().buildAssetsByStatus(10, "uncounted");
        List<Asset> counted = TestDataBuilder.assetBuilder().buildAssetsByStatus(7, "counted");
        List<Asset> adjusted = TestDataBuilder.assetBuilder().buildAssetsByStatus(3, "adjusted");

        when(assetRepository.findByAssetStatus("uncounted")).thenReturn(uncounted);
        when(assetRepository.findByAssetStatus("counted")).thenReturn(counted);
        when(assetRepository.findByAssetStatus("adjusted")).thenReturn(adjusted);

        int totalUncounted = assetService.getAssetsByStatus("uncounted").size();
        int totalCounted = assetService.getAssetsByStatus("counted").size();
        int totalAdjusted = assetService.getAssetsByStatus("adjusted").size();

        assertEquals(10, totalUncounted);
        assertEquals(7, totalCounted);
        assertEquals(3, totalAdjusted);
        assertEquals(20, totalUncounted + totalCounted + totalAdjusted);
    }

    @Test
    @DisplayName("测试资产更新非空校验")
    void testAssetUpdate_NullHandling() {
        when(assetRepository.findByAssetId(anyString())).thenReturn(Optional.of(testAsset));
        when(assetRepository.save(any(Asset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Asset updatedStatus = assetService.updateAssetStatus(testAsset.getAssetId(), "counted");
        assertNotNull(updatedStatus.getAssetStatus());

        Asset updatedQty = assetService.updateAssetQuantity(testAsset.getAssetId(), 50);
        assertTrue(updatedQty.getAssetQuantity() >= 0);
    }
}
