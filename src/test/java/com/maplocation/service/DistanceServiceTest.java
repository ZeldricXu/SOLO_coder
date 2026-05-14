package com.maplocation.service;

import com.maplocation.builder.TestDataBuilder;
import com.maplocation.dto.DistanceCalculateRequest;
import com.maplocation.dto.DistanceCalculateResponse;
import com.maplocation.model.Coordinates;
import com.maplocation.model.Location;
import com.maplocation.repository.DistanceRecordRepository;
import com.maplocation.util.GeoUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DistanceServiceTest {

    @Mock
    private DistanceRecordRepository distanceRecordRepository;

    @Mock
    private LocationService locationService;

    @InjectMocks
    private DistanceService distanceService;

    @Test
    @DisplayName("测试直线距离计算 - Haversine公式准确性")
    void testCalculateDirectDistance_Accuracy() {
        Coordinates beijing = new Coordinates(39.9042, 116.4074);
        Coordinates shanghai = new Coordinates(31.2304, 121.4737);

        double calculatedDistance = GeoUtils.calculateDistance(beijing, shanghai);

        double expectedDistanceKm = 1068;
        double calculatedDistanceKm = calculatedDistance / 1000;

        assertTrue(calculatedDistanceKm > 1000 && calculatedDistanceKm < 1150,
                "北京到上海距离应约为1068公里，实际: " + calculatedDistanceKm + "公里");
    }

    @Test
    @DisplayName("测试近距离计算 - 米级精度")
    void testCalculateShortDistance_MeterPrecision() {
        Coordinates start = new Coordinates(39.9042, 116.4074);
        Coordinates end = new Coordinates(39.9043, 116.4075);

        double distance = GeoUtils.calculateDistance(start, end);

        assertTrue(distance > 10 && distance < 20,
                "两点距离应在10-20米之间，实际: " + distance + "米");
    }

    @Test
    @DisplayName("测试相同坐标 - 距离为0")
    void testCalculateDistance_SameCoordinates() {
        Coordinates point = new Coordinates(39.9042, 116.4074);

        double distance = GeoUtils.calculateDistance(point, point);

        assertEquals(0, distance, 0.001);
    }

    @Test
    @DisplayName("测试坐标计算 - 纬度边界值")
    void testCalculateDistance_LatitudeBoundaries() {
        Coordinates northPole = new Coordinates(90.0, 0.0);
        Coordinates equator = new Coordinates(0.0, 0.0);

        double distance = GeoUtils.calculateDistance(northPole, equator);
        double expectedDistance = Math.PI * 6371000 / 2;

        assertEquals(expectedDistance, distance, 1000,
                "北极到赤道距离应约为10000公里，实际: " + distance / 1000 + "公里");
    }

    @Test
    @DisplayName("测试坐标计算 - 经度边界值")
    void testCalculateDistance_LongitudeBoundaries() {
        Coordinates point1 = new Coordinates(0.0, -180.0);
        Coordinates point2 = new Coordinates(0.0, 180.0);

        double distance = GeoUtils.calculateDistance(point1, point2);

        assertEquals(0, distance, 1, "经度180和-180应为同一经线");
    }

    @Test
    @DisplayName("测试不同距离类型 - 直接距离")
    void testCalculateDistance_DirectType() {
        when(distanceRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DistanceCalculateRequest request = TestDataBuilder.buildDistanceRequest(
                TestDataBuilder.BEIJING_CENTER,
                TestDataBuilder.BEIJING_SHOPPING,
                "direct"
        );

        DistanceCalculateResponse response = distanceService.calculateDistance(request);

        assertNotNull(response);
        assertEquals("direct", response.getDistanceType());
        assertEquals("meter", response.getDistanceUnit());
        assertTrue(response.getDistanceValue() > 0);
    }

    @Test
    @DisplayName("测试不同距离类型 - 驾车距离")
    void testCalculateDistance_DrivingType() {
        when(distanceRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DistanceCalculateRequest request = TestDataBuilder.buildDistanceRequest(
                TestDataBuilder.BEIJING_CENTER,
                TestDataBuilder.BEIJING_SHOPPING,
                "driving"
        );

        DistanceCalculateResponse response = distanceService.calculateDistance(request);

        assertNotNull(response);
        assertEquals("driving", response.getDistanceType());
        assertTrue(response.getDistanceValue() > 0);
    }

    @Test
    @DisplayName("测试不同距离类型 - 步行距离")
    void testCalculateDistance_WalkingType() {
        when(distanceRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DistanceCalculateRequest request = TestDataBuilder.buildDistanceRequest(
                TestDataBuilder.BEIJING_CENTER,
                TestDataBuilder.BEIJING_SHOPPING,
                "walking"
        );

        DistanceCalculateResponse response = distanceService.calculateDistance(request);

        assertNotNull(response);
        assertEquals("walking", response.getDistanceType());
        assertTrue(response.getDistanceValue() > 0);
    }

    @Test
    @DisplayName("测试距离类型系数 - 驾车距离大于直接距离")
    void testDistanceTypeFactors_DrivingGreaterThanDirect() {
        Coordinates start = TestDataBuilder.BEIJING_CENTER;
        Coordinates end = TestDataBuilder.BEIJING_SHOPPING;

        when(distanceRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DistanceCalculateRequest directRequest = TestDataBuilder.buildDistanceRequest(start, end, "direct");
        DistanceCalculateRequest drivingRequest = TestDataBuilder.buildDistanceRequest(start, end, "driving");
        DistanceCalculateRequest walkingRequest = TestDataBuilder.buildDistanceRequest(start, end, "walking");

        DistanceCalculateResponse directResponse = distanceService.calculateDistance(directRequest);
        DistanceCalculateResponse drivingResponse = distanceService.calculateDistance(drivingRequest);
        DistanceCalculateResponse walkingResponse = distanceService.calculateDistance(walkingRequest);

        assertTrue(drivingResponse.getDistanceValue() > directResponse.getDistanceValue());
        assertTrue(walkingResponse.getDistanceValue() >= directResponse.getDistanceValue());
    }

    @Test
    @DisplayName("测试距离排序 - 按距离升序")
    void testDistanceSorting_AscendingOrder() {
        Coordinates center = TestDataBuilder.BEIJING_CENTER;

        Coordinates near = new Coordinates(39.9043, 116.4075);
        Coordinates mid = new Coordinates(39.9050, 116.4080);
        Coordinates far = new Coordinates(39.9100, 116.4100);

        List<Coordinates> points = Arrays.asList(far, near, mid);
        points.sort((p1, p2) -> Double.compare(
                GeoUtils.calculateDistance(center, p1),
                GeoUtils.calculateDistance(center, p2)
        ));

        assertEquals(near, points.get(0));
        assertEquals(mid, points.get(1));
        assertEquals(far, points.get(2));
    }

    @Test
    @DisplayName("测试周边范围查询 - 距离过滤正确性")
    void testNearbyDistanceFilter_Correctness() {
        Coordinates center = TestDataBuilder.BEIJING_CENTER;
        double radius = 2000;

        Location withinRadius = TestDataBuilder.buildLocation(
                "within", "范围内位置", "shopping", "商业",
                new Coordinates(39.9050, 116.4080),
                Arrays.asList("测试")
        );

        Location outsideRadius = TestDataBuilder.buildLocation(
                "outside", "范围外位置", "shopping", "商业",
                new Coordinates(40.0, 117.0),
                Arrays.asList("测试")
        );

        List<Location> allLocations = Arrays.asList(withinRadius, outsideRadius);

        List<Location> filtered = allLocations.stream()
                .filter(l -> l.getLocationCoordinates() != null)
                .filter(l -> GeoUtils.isWithinRadius(center, l.getLocationCoordinates(), radius))
                .toList();

        assertEquals(1, filtered.size());
        assertEquals("within", filtered.get(0).getLocationId());
    }

    @Test
    @DisplayName("测试坐标有效性验证 - 有效坐标")
    void testValidCoordinates() {
        Coordinates valid1 = new Coordinates(0, 0);
        Coordinates valid2 = new Coordinates(90, 180);
        Coordinates valid3 = new Coordinates(-90, -180);
        Coordinates valid4 = new Coordinates(39.9042, 116.4074);

        assertTrue(GeoUtils.isValidCoordinates(valid1));
        assertTrue(GeoUtils.isValidCoordinates(valid2));
        assertTrue(GeoUtils.isValidCoordinates(valid3));
        assertTrue(GeoUtils.isValidCoordinates(valid4));
    }

    @Test
    @DisplayName("测试坐标有效性验证 - 无效坐标")
    void testInvalidCoordinates() {
        Coordinates invalidLatHigh = new Coordinates(91.0, 0);
        Coordinates invalidLatLow = new Coordinates(-91.0, 0);
        Coordinates invalidLngHigh = new Coordinates(0, 181.0);
        Coordinates invalidLngLow = new Coordinates(0, -181.0);
        Coordinates nullCoords = null;

        assertFalse(GeoUtils.isValidCoordinates(invalidLatHigh));
        assertFalse(GeoUtils.isValidCoordinates(invalidLatLow));
        assertFalse(GeoUtils.isValidCoordinates(invalidLngHigh));
        assertFalse(GeoUtils.isValidCoordinates(invalidLngLow));
        assertFalse(GeoUtils.isValidCoordinates(nullCoords));
    }

    @Test
    @DisplayName("测试基于位置ID的距离计算")
    void testCalculateDistanceBetweenLocations_ById() {
        Location loc1 = TestDataBuilder.buildShoppingMall();
        Location loc2 = TestDataBuilder.buildRestaurant();

        when(locationService.getLocationById("location_shopping_001")).thenReturn(Optional.of(loc1));
        when(locationService.getLocationById("location_restaurant_001")).thenReturn(Optional.of(loc2));

        double distance = distanceService.calculateDistanceBetweenLocations(
                "location_shopping_001",
                "location_restaurant_001"
        );

        assertTrue(distance > 0);
    }

    @Test
    @DisplayName("测试位置不存在时抛异常")
    void testCalculateDistanceBetweenLocations_NotFound() {
        when(locationService.getLocationById("non_existent_1")).thenReturn(Optional.empty());
        when(locationService.getLocationById("non_existent_2")).thenReturn(Optional.of(
                TestDataBuilder.buildPark()
        ));

        assertThrows(RuntimeException.class, () ->
                distanceService.calculateDistanceBetweenLocations("non_existent_1", "non_existent_2"));
    }

    @Test
    @DisplayName("测试距离记录持久化")
    void testDistanceRecord_Persisted() {
        when(distanceRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DistanceCalculateRequest request = TestDataBuilder.buildDirectDistanceRequest();

        distanceService.calculateDistance(request);

        verify(distanceRecordRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("测试距离ID生成")
    void testDistanceId_Generated() {
        when(distanceRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DistanceCalculateRequest request = TestDataBuilder.buildDirectDistanceRequest();

        DistanceCalculateResponse response = distanceService.calculateDistance(request);

        assertNotNull(response.getDistanceId());
        assertTrue(response.getDistanceId().startsWith("distance_"));
    }

    @Test
    @DisplayName("测试默认距离类型 - 不指定时使用直接距离")
    void testDefaultDistanceType_Direct() {
        when(distanceRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DistanceCalculateRequest request = DistanceCalculateRequest.builder()
                .fromLocation(TestDataBuilder.BEIJING_CENTER)
                .toLocation(TestDataBuilder.BEIJING_SHOPPING)
                .distanceType(null)
                .build();

        DistanceCalculateResponse response = distanceService.calculateDistance(request);

        assertEquals("direct", response.getDistanceType());
    }

    @Test
    @DisplayName("测试多位置距离排序")
    void testMultipleLocationsDistanceSorting() {
        Coordinates center = TestDataBuilder.BEIJING_CENTER;

        List<Location> locations = TestDataBuilder.buildMultipleLocations(10);

        List<Location> sortedLocations = new ArrayList<>(locations);
        sortedLocations.sort((l1, l2) -> Double.compare(
                GeoUtils.calculateDistance(center, l1.getLocationCoordinates()),
                GeoUtils.calculateDistance(center, l2.getLocationCoordinates())
        ));

        for (int i = 0; i < sortedLocations.size() - 1; i++) {
            double dist1 = GeoUtils.calculateDistance(center, sortedLocations.get(i).getLocationCoordinates());
            double dist2 = GeoUtils.calculateDistance(center, sortedLocations.get(i + 1).getLocationCoordinates());
            assertTrue(dist1 <= dist2, "位置应按距离升序排列");
        }
    }

    @Test
    @DisplayName("测试距离计算边界 - 空坐标处理")
    void testDistanceCalculation_NullCoordinates() {
        double distance = GeoUtils.calculateDistance(null, TestDataBuilder.BEIJING_CENTER);
        assertEquals(0, distance, 0.001);

        distance = GeoUtils.calculateDistance(TestDataBuilder.BEIJING_CENTER, null);
        assertEquals(0, distance, 0.001);

        distance = GeoUtils.calculateDistance(null, null);
        assertEquals(0, distance, 0.001);
    }

    @Test
    @DisplayName("测试范围判断边界值 - 刚好在边界上")
    void testWithinRadius_OnBoundary() {
        Coordinates center = TestDataBuilder.BEIJING_CENTER;
        double radius = 1000;

        double angle = 0;
        double earthRadius = 6371000;
        double latOffset = (radius / earthRadius) * (180 / Math.PI);

        Coordinates onBoundary = new Coordinates(
                center.getLat() + latOffset,
                center.getLng()
        );

        assertTrue(GeoUtils.isWithinRadius(center, onBoundary, radius + 100));
    }
}
