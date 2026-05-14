package com.assetmanage.service;

import com.assetmanage.dto.DepreciationData;
import com.assetmanage.entity.Asset;
import com.assetmanage.entity.DepreciationRecord;
import com.assetmanage.enums.DepreciationMethod;
import com.assetmanage.exception.BusinessException;
import com.assetmanage.repository.DepreciationRecordRepository;
import com.assetmanage.testdata.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DepreciationServiceTest {

    @Mock
    private DepreciationRecordRepository depreciationRepository;

    @Mock
    private AssetService assetService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private DepreciationService depreciationService;

    private static final DateTimeFormatter PERIOD_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private String currentPeriod;

    @BeforeEach
    void setUp() {
        currentPeriod = LocalDate.now().format(PERIOD_FORMATTER);
    }

    @Test
    @DisplayName("测试直线法折旧计算准确性")
    void testStraightLineDepreciationCalculation() {
        Asset asset = TestDataBuilder.buildAssetWithStraightLineDepreciation();

        BigDecimal monthlyDepreciation = depreciationService.calculateDepreciationValue(asset);

        BigDecimal annualDepreciation = new BigDecimal("12000.00").multiply(new BigDecimal("0.20"));
        BigDecimal expectedMonthly = annualDepreciation.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
        
        assertEquals(expectedMonthly, monthlyDepreciation, 
                "直线法月折旧额应为原值 * 折旧率 / 12");
        assertEquals(new BigDecimal("200.00"), monthlyDepreciation, 
                "12000 * 20% / 12 = 200");
    }

    @Test
    @DisplayName("测试加速折旧法计算准确性")
    void testAcceleratedDepreciationCalculation() {
        Asset asset = TestDataBuilder.buildAssetWithAcceleratedDepreciation();

        BigDecimal monthlyDepreciation = depreciationService.calculateDepreciationValue(asset);

        BigDecimal monthlyRate = new BigDecimal("0.30").divide(BigDecimal.valueOf(12), 4, RoundingMode.HALF_UP);
        BigDecimal expected = new BigDecimal("8000.00").multiply(monthlyRate).setScale(2, RoundingMode.HALF_UP);

        assertEquals(expected, monthlyDepreciation, 
                "加速折旧法月折旧额应为净值 * 月折旧率");
        assertEquals(new BigDecimal("200.00"), monthlyDepreciation);
    }

    @Test
    @DisplayName("测试双倍余额递减法计算准确性")
    void testDoubleDecliningDepreciationCalculation() {
        Asset asset = TestDataBuilder.buildAssetWithDoubleDecliningDepreciation();

        BigDecimal monthlyDepreciation = depreciationService.calculateDepreciationValue(asset);

        BigDecimal straightLineRate = BigDecimal.ONE.divide(BigDecimal.valueOf(5), 6, RoundingMode.HALF_UP);
        BigDecimal doubleRate = straightLineRate.multiply(new BigDecimal("2"));
        BigDecimal monthlyRate = doubleRate.divide(BigDecimal.valueOf(12), 6, RoundingMode.HALF_UP);
        BigDecimal expected = new BigDecimal("10000.00").multiply(monthlyRate).setScale(2, RoundingMode.HALF_UP);

        assertEquals(expected, monthlyDepreciation, 
                "双倍余额递减法月折旧额应为净值 * (2/使用年限/12)");
    }

    @Test
    @DisplayName("测试增量计算折旧 - 仅计算新增资产")
    void testIncrementalDepreciationOnlyNewAssets() {
        Asset newAsset = TestDataBuilder.buildNewAsset();
        Asset oldAsset = TestDataBuilder.buildOldAsset();

        List<Asset> mixedAssets = Arrays.asList(newAsset, oldAsset);
        when(assetService.getActiveAssets()).thenReturn(mixedAssets);

        when(depreciationRepository.findByAssetIdAndPeriod(eq(newAsset.getAssetId()), eq(currentPeriod)))
                .thenReturn(Optional.empty());
        when(depreciationRepository.findByAssetIdAndPeriod(eq(oldAsset.getAssetId()), eq(currentPeriod)))
                .thenReturn(Optional.empty());
        when(depreciationRepository.save(any(DepreciationRecord.class))).thenReturn(new DepreciationRecord());

        doNothing().when(assetService).updateAssetValue(anyString(), any(BigDecimal.class), any(BigDecimal.class));
        doNothing().when(analysisService).updateStatistics();

        depreciationService.calculateDepreciation(false);

        verify(depreciationRepository, times(1)).save(any(DepreciationRecord.class));
        verify(assetService, times(1)).updateAssetValue(anyString(), any(BigDecimal.class), any(BigDecimal.class));
    }

    @Test
    @DisplayName("测试全量折旧计算 - 计算所有资产")
    void testFullDepreciationAllAssets() {
        Asset newAsset = TestDataBuilder.buildNewAsset();
        Asset oldAsset = TestDataBuilder.buildOldAsset();

        List<Asset> allAssets = Arrays.asList(newAsset, oldAsset);
        when(assetService.getActiveAssets()).thenReturn(allAssets);

        when(depreciationRepository.findByAssetIdAndPeriod(anyString(), eq(currentPeriod)))
                .thenReturn(Optional.empty());
        when(depreciationRepository.save(any(DepreciationRecord.class))).thenReturn(new DepreciationRecord());

        doNothing().when(assetService).updateAssetValue(anyString(), any(BigDecimal.class), any(BigDecimal.class));
        doNothing().when(analysisService).updateStatistics();

        depreciationService.calculateDepreciation(true);

        verify(depreciationRepository, times(2)).save(any(DepreciationRecord.class));
        verify(assetService, times(2)).updateAssetValue(anyString(), any(BigDecimal.class), any(BigDecimal.class));
    }

    @Test
    @DisplayName("测试已有资产定期更新累计折旧")
    void testExistingAssetAccumulatedDepreciationUpdate() {
        Asset asset = TestDataBuilder.buildAssetWithPartialDepreciation();

        when(depreciationRepository.findByAssetIdAndPeriod(eq(asset.getAssetId()), eq(currentPeriod)))
                .thenReturn(Optional.empty());
        when(depreciationRepository.save(any(DepreciationRecord.class))).thenAnswer(invocation -> 
                invocation.getArgument(0));

        doNothing().when(assetService).updateAssetValue(anyString(), any(BigDecimal.class), any(BigDecimal.class));

        depreciationService.calculateAssetDepreciation(asset);

        verify(depreciationRepository).save(argThat(record -> {
            BigDecimal expectedAccumulated = new BigDecimal("2400.00").add(new BigDecimal("200.00"));
            assertEquals(expectedAccumulated, record.getAccumulatedDepreciation());
            
            BigDecimal expectedNetValue = new BigDecimal("12000.00").subtract(expectedAccumulated);
            assertEquals(expectedNetValue, record.getCurrentValue());
            return true;
        }));
    }

    @Test
    @DisplayName("测试当期折旧已计算时跳过重复计算")
    void testSkipCalculationWhenPeriodAlreadyCalculated() {
        Asset asset = TestDataBuilder.buildIdleAsset();
        DepreciationRecord existingRecord = TestDataBuilder.buildDepreciationRecord(currentPeriod);

        when(depreciationRepository.findByAssetIdAndPeriod(eq(asset.getAssetId()), eq(currentPeriod)))
                .thenReturn(Optional.of(existingRecord));

        depreciationService.calculateAssetDepreciation(asset);

        verify(depreciationRepository, never()).save(any(DepreciationRecord.class));
        verify(assetService, never()).updateAssetValue(anyString(), any(BigDecimal.class), any(BigDecimal.class));
    }

    @Test
    @DisplayName("测试折旧后净值不为负数")
    void testNetValueNeverNegative() {
        Asset asset = TestDataBuilder.buildIdleAsset();
        asset.setPurchasePrice(new BigDecimal("100.00"));
        asset.setAccumulatedDepreciation(new BigDecimal("150.00"));
        asset.setCurrentValue(new BigDecimal("-50.00"));

        when(depreciationRepository.findByAssetIdAndPeriod(eq(asset.getAssetId()), eq(currentPeriod)))
                .thenReturn(Optional.empty());
        when(depreciationRepository.save(any(DepreciationRecord.class))).thenAnswer(invocation -> 
                invocation.getArgument(0));

        doNothing().when(assetService).updateAssetValue(anyString(), any(BigDecimal.class), any(BigDecimal.class));

        depreciationService.calculateAssetDepreciation(asset);

        verify(depreciationRepository).save(argThat(record -> {
            assertTrue(record.getCurrentValue().compareTo(BigDecimal.ZERO) >= 0,
                    "资产净值不能为负数");
            return true;
        }));
    }

    @Test
    @DisplayName("测试不支持的折旧方法抛出异常")
    void testUnsupportedDepreciationMethodThrowsException() {
        Asset asset = TestDataBuilder.buildIdleAsset();
        asset.setDepreciationMethod("unsupported_method");

        assertThrows(BusinessException.class, 
                () -> depreciationService.calculateDepreciationValue(asset));
    }

    @Test
    @DisplayName("测试折旧报表生成的完整性 - 单期查询")
    void testDepreciationReportSinglePeriod() {
        String assetId = TestDataBuilder.TEST_ASSET_ID;
        Asset asset = TestDataBuilder.buildIdleAsset();
        List<DepreciationRecord> records = Collections.singletonList(
                TestDataBuilder.buildDepreciationRecord(currentPeriod)
        );

        when(assetService.getAssetById(assetId)).thenReturn(asset);
        when(depreciationRepository.findByAssetIdOrderByCalculatedAtDesc(assetId)).thenReturn(records);

        DepreciationData data = depreciationService.getDepreciationByAssetAndPeriod(assetId, null, null);

        assertNotNull(data);
        assertNotNull(data.getDepreciation());
        assertEquals(1, data.getDepreciation().size());
        assertEquals(currentPeriod, data.getDepreciation().get(0).getPeriod());
    }

    @Test
    @DisplayName("测试折旧报表生成的完整性 - 多期范围查询")
    void testDepreciationReportPeriodRange() {
        String assetId = TestDataBuilder.TEST_ASSET_ID;
        String startPeriod = "2026-01";
        String endPeriod = "2026-03";
        Asset asset = TestDataBuilder.buildIdleAsset();
        List<DepreciationRecord> records = TestDataBuilder.buildMonthlyDepreciationRecords(startPeriod, 3);

        when(assetService.getAssetById(assetId)).thenReturn(asset);
        when(depreciationRepository.findByAssetIdAndPeriodBetween(assetId, startPeriod, endPeriod))
                .thenReturn(records);

        DepreciationData data = depreciationService.getDepreciationByAssetAndPeriod(assetId, startPeriod, endPeriod);

        assertNotNull(data);
        assertEquals(3, data.getDepreciation().size());
    }

    @Test
    @DisplayName("测试期间折旧总额计算")
    void testTotalDepreciationForPeriod() {
        String period = currentPeriod;
        DepreciationRecord record1 = TestDataBuilder.buildDepreciationRecord(period);
        DepreciationRecord record2 = TestDataBuilder.buildDepreciationRecord(period);
        record2.setDepreciationValue(new BigDecimal("100.00"));

        when(depreciationRepository.findByDepreciationPeriod(period)).thenReturn(Arrays.asList(record1, record2));

        BigDecimal total = depreciationService.getTotalDepreciationForPeriod(period);

        BigDecimal expected = record1.getDepreciationValue().add(record2.getDepreciationValue());
        assertEquals(expected, total);
    }

    @Test
    @DisplayName("测试获取资产折旧记录列表")
    void testGetDepreciationRecordsByAsset() {
        String assetId = TestDataBuilder.TEST_ASSET_ID;
        List<DepreciationRecord> records = TestDataBuilder.buildMonthlyDepreciationRecords("2026-01", 6);

        when(depreciationRepository.findByAssetIdOrderByCalculatedAtDesc(assetId)).thenReturn(records);

        List<DepreciationRecord> result = depreciationService.getDepreciationRecordsByAsset(assetId);

        assertEquals(6, result.size());
    }

    @Test
    @DisplayName("测试获取所有折旧记录")
    void testGetAllDepreciationRecords() {
        List<DepreciationRecord> records = Arrays.asList(
                TestDataBuilder.buildDepreciationRecord("2026-01"),
                TestDataBuilder.buildDepreciationRecord("2026-02")
        );

        when(depreciationRepository.findAll()).thenReturn(records);

        List<DepreciationRecord> result = depreciationService.getAllDepreciationRecords();

        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("测试折旧计算时记录历史")
    void testDepreciationRecordsHistory() {
        Asset asset = TestDataBuilder.buildIdleAsset();

        when(depreciationRepository.findByAssetIdAndPeriod(eq(asset.getAssetId()), eq(currentPeriod)))
                .thenReturn(Optional.empty());
        when(depreciationRepository.save(any(DepreciationRecord.class))).thenReturn(new DepreciationRecord());
        doNothing().when(assetService).updateAssetValue(anyString(), any(BigDecimal.class), any(BigDecimal.class));

        depreciationService.calculateAssetDepreciation(asset);

        verify(historyService).recordHistory(eq(asset.getAssetId()), eq("depreciation"), anyString(), isNull());
    }
}
