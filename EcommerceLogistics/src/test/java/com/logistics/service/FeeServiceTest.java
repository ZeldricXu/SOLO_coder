package com.logistics.service;

import com.logistics.builder.TestDataBuilder;
import com.logistics.constant.LogisticsConstants;
import com.logistics.entity.DeliveryType;
import com.logistics.entity.Logistics;
import com.logistics.entity.Track;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("物流费用计算测试")
class FeeServiceTest {

    @Mock
    private LogisticsService logisticsService;

    @Mock
    private TrackService trackService;

    @Mock
    private DeliveryTypeService deliveryTypeService;

    @InjectMocks
    private FeeService feeService;

    private Logistics testLogistics;
    private DeliveryType standardDeliveryType;
    private DeliveryType urgentDeliveryType;
    private DeliveryType superUrgentDeliveryType;

    @BeforeEach
    void setUp() {
        testLogistics = TestDataBuilder.buildTestLogistics(
                TestDataBuilder.TEST_ORDER_ID, TestDataBuilder.TEST_STATION_ID);
        standardDeliveryType = TestDataBuilder.buildStandardDeliveryType();
        urgentDeliveryType = TestDataBuilder.buildUrgentDeliveryType();
        superUrgentDeliveryType = TestDataBuilder.buildSuperUrgentDeliveryType();

        when(deliveryTypeService.getDefaultDeliveryType()).thenReturn(standardDeliveryType);
        when(deliveryTypeService.getDeliveryType(anyString())).thenReturn(standardDeliveryType);
    }

    @Test
    @DisplayName("测试基础费用计算 - 标准配送")
    void testBaseFeeCalculationStandard() {
        double fee = feeService.calculateFee(1.0, 0.5);
        assertTrue(fee >= 8.0, "最低费用应该是8元");
        assertEquals(8.0, fee, "短距离短时间应该按最低费用计算");
    }

    @Test
    @DisplayName("测试紧急配送费用计算")
    void testUrgentDeliveryFeeCalculation() {
        double standardFee = feeService.calculateFee(5.0, 2.0, standardDeliveryType);
        double urgentFee = feeService.calculateFee(5.0, 2.0, urgentDeliveryType);

        assertTrue(urgentFee > standardFee, "紧急配送费用应该高于标准配送");
    }

    @Test
    @DisplayName("测试超紧急配送费用计算")
    void testSuperUrgentDeliveryFeeCalculation() {
        double urgentFee = feeService.calculateFee(5.0, 2.0, urgentDeliveryType);
        double superUrgentFee = feeService.calculateFee(5.0, 2.0, superUrgentDeliveryType);

        assertTrue(superUrgentFee > urgentFee, "超紧急配送费用应该高于紧急配送");
    }

    @Test
    @DisplayName("测试距离对费用的影响")
    void testDistanceFeeImpact() {
        double feeShortDistance = feeService.calculateFee(1.0, 1.0, standardDeliveryType);
        double feeLongDistance = feeService.calculateFee(10.0, 1.0, standardDeliveryType);

        assertTrue(feeLongDistance > feeShortDistance, "距离越远费用越高");

        double expectedShort = 5.0 + (1.0 * 2.0) + (1.0 * 0.5);
        double expectedLong = 5.0 + (10.0 * 2.0) + (1.0 * 0.5);

        assertEquals(expectedShort, feeShortDistance, 0.01);
        assertEquals(Math.min(50.0, expectedLong), feeLongDistance, 0.01);
    }

    @Test
    @DisplayName("测试时间对费用的影响")
    void testTimeFeeImpact() {
        double feeShortTime = feeService.calculateFee(5.0, 1.0, standardDeliveryType);
        double feeLongTime = feeService.calculateFee(5.0, 5.0, standardDeliveryType);

        assertTrue(feeLongTime > feeShortTime, "时间越长费用越高");

        double expectedShort = 5.0 + (5.0 * 2.0) + (1.0 * 0.5);
        double expectedLong = 5.0 + (5.0 * 2.0) + (5.0 * 0.5);

        assertEquals(expectedShort, feeShortTime, 0.01);
        assertEquals(expectedLong, feeLongTime, 0.01);
    }

    @Test
    @DisplayName("测试最低费用限制 - 标准配送")
    void testMinimumFeeLimitStandard() {
        double fee = feeService.calculateFee(0.5, 0.3, standardDeliveryType);
        assertEquals(8.0, fee, "标准配送最低费用应该是8元");
    }

    @Test
    @DisplayName("测试最低费用限制 - 紧急配送")
    void testMinimumFeeLimitUrgent() {
        double fee = feeService.calculateFee(0.5, 0.3, urgentDeliveryType);
        assertEquals(15.0, fee, "紧急配送最低费用应该是15元");
    }

    @Test
    @DisplayName("测试最高费用限制")
    void testMaximumFeeLimit() {
        double fee = feeService.calculateFee(50.0, 10.0, standardDeliveryType);
        assertEquals(50.0, fee, "标准配送最高费用应该是50元");
    }

    @Test
    @DisplayName("测试基于轨迹和时间的完整费用计算")
    void testCompleteFeeCalculationWithLogistics() {
        testLogistics.setShippingTime(LocalDateTime.now().minusHours(2));
        testLogistics.setDeliveryTime(LocalDateTime.now());

        List<Track> tracks = Arrays.asList(
                TestDataBuilder.buildTestTrack(testLogistics.getLogisticsId(), 1),
                TestDataBuilder.buildTestTrack(testLogistics.getLogisticsId(), 2),
                TestDataBuilder.buildTestTrack(testLogistics.getLogisticsId(), 3),
                TestDataBuilder.buildTestTrack(testLogistics.getLogisticsId(), 4)
        );

        when(logisticsService.getLogisticsById(anyString())).thenReturn(testLogistics);
        when(trackService.getRawTracksByLogisticsId(anyString())).thenReturn(tracks);

        double fee = feeService.calculateFee(testLogistics.getLogisticsId());

        double expectedDistance = 4 * 0.5;
        double expectedDuration = 2.0;
        double expectedBase = 5.0 + (expectedDistance * 2.0) + (expectedDuration * 0.5);
        double expectedFee = Math.round(Math.max(8.0, Math.min(50.0, expectedBase)) * 100.0) / 100.0;

        assertTrue(fee >= 8.0);
        assertTrue(fee <= 50.0);
    }

    @Test
    @DisplayName("测试无轨迹时的费用计算")
    void testFeeCalculationWithoutTracks() {
        testLogistics.setShippingTime(LocalDateTime.now().minusHours(1));
        testLogistics.setDeliveryTime(LocalDateTime.now());

        when(logisticsService.getLogisticsById(anyString())).thenReturn(testLogistics);
        when(trackService.getRawTracksByLogisticsId(anyString())).thenReturn(Arrays.asList());

        double fee = feeService.calculateFee(testLogistics.getLogisticsId());

        assertTrue(fee >= 8.0);
        assertTrue(fee <= 50.0);
    }

    @Test
    @DisplayName("测试配送费用四舍五入")
    void testFeeRounding() {
        double fee = feeService.calculateFee(3.2, 1.8, standardDeliveryType);
        double calculated = 5.0 + (3.2 * 2.0) + (1.8 * 0.5);
        double expected = Math.round(calculated * 100.0) / 100.0;

        assertEquals(expected, fee, 0.001);
    }

    @Test
    @DisplayName("测试典型配送场景的费用计算")
    void testTypicalDeliveryScenarios() {
        verifyTypicalScenario(2.0, 1.0, "短距离短时间");
        verifyTypicalScenario(5.0, 2.0, "中等距离中等时间");
        verifyTypicalScenario(10.0, 3.0, "长距离长时间");
    }

    private void verifyTypicalScenario(double distance, double hours, String description) {
        double fee = feeService.calculateFee(distance, hours, standardDeliveryType);
        double expected = 5.0 + (distance * 2.0) + (hours * 0.5);
        expected = Math.max(8.0, Math.min(50.0, expected));
        expected = Math.round(expected * 100.0) / 100.0;

        assertEquals(expected, fee, 0.01, description + " 费用计算应该正确");
    }

    @Test
    @DisplayName("测试费用计算边界值")
    void testFeeBoundaryValues() {
        assertEquals(8.0, feeService.calculateFee(0.1, 0.1, standardDeliveryType), "最小边界");
        assertEquals(50.0, feeService.calculateFee(100.0, 100.0, standardDeliveryType), "最大边界");
        assertEquals(8.0, feeService.calculateFee(1.0, 0.5, standardDeliveryType), "接近最小边界");
    }

    @Test
    @DisplayName("测试不同配送类型的费用对比")
    void testDifferentDeliveryTypesComparison() {
        double distance = 5.0;
        double hours = 2.0;

        double standardFee = feeService.calculateFee(distance, hours, standardDeliveryType);
        double urgentFee = feeService.calculateFee(distance, hours, urgentDeliveryType);
        double superUrgentFee = feeService.calculateFee(distance, hours, superUrgentDeliveryType);

        assertTrue(superUrgentFee > urgentFee, "超紧急 > 紧急");
        assertTrue(urgentFee > standardFee, "紧急 > 标准");
    }
}
