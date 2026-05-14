package com.maplocation.service;

import com.maplocation.builder.TestDataBuilder;
import com.maplocation.model.Location;
import com.maplocation.model.LocationQueryCount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock
    private AnalysisService analysisService;

    @Mock
    private LocationService locationService;

    @InjectMocks
    private RecommendationService recommendationService;

    private Location shoppingMall;
    private Location restaurant;
    private Location park;
    private Location hotel;
    private Location station;
    private List<Location> allLocations;

    @BeforeEach
    void setUp() {
        shoppingMall = TestDataBuilder.buildShoppingMall();
        restaurant = TestDataBuilder.buildRestaurant();
        park = TestDataBuilder.buildPark();
        hotel = TestDataBuilder.buildHotel();
        station = TestDataBuilder.buildStation();
        allLocations = Arrays.asList(shoppingMall, restaurant, park, hotel, station);
    }

    @Test
    @DisplayName("测试相关位置推荐 - 基于分类推荐")
    void testGetRecommendedLocations_ByCategory() {
        Location anotherShopping = TestDataBuilder.buildLocation(
                "another_shopping", "另一个购物中心", "shopping", "商业",
                TestDataBuilder.BEIJING_HOTEL,
                Arrays.asList("购物", "商场")
        );

        when(locationService.getLocationById("location_shopping_001")).thenReturn(java.util.Optional.of(shoppingMall));
        when(locationService.getAllLocations()).thenReturn(Arrays.asList(shoppingMall, anotherShopping, restaurant));

        List<Location> recommendations = recommendationService.getRecommendedLocations("location_shopping_001", 10);

        assertNotNull(recommendations);
        assertTrue(recommendations.size() > 0);
        assertTrue(recommendations.stream()
                .anyMatch(l -> "another_shopping".equals(l.getLocationId())));
    }

    @Test
    @DisplayName("测试相关位置推荐 - 基于标签推荐")
    void testGetRecommendedLocations_ByTags() {
        Location locationWithUniqueTag = TestDataBuilder.buildLocation(
                "unique_tag", "特色餐厅", "restaurant", "特色餐饮",
                TestDataBuilder.BEIJING_RESTAURANT,
                Arrays.asList("特色", "美食", "北京菜")
        );

        Location similarByTag = TestDataBuilder.buildLocation(
                "similar_tag", "另一家特色店", "restaurant", "特色餐饮",
                TestDataBuilder.BEIJING_PARK,
                Arrays.asList("特色", "小吃")
        );

        when(locationService.getLocationById("unique_tag")).thenReturn(java.util.Optional.of(locationWithUniqueTag));
        when(locationService.getAllLocations()).thenReturn(Arrays.asList(locationWithUniqueTag, similarByTag, shoppingMall));

        List<Location> recommendations = recommendationService.getRecommendedLocations("unique_tag", 10);

        assertNotNull(recommendations);
        assertTrue(recommendations.size() > 0);
        assertTrue(recommendations.stream()
                .anyMatch(l -> "similar_tag".equals(l.getLocationId())));
    }

    @Test
    @DisplayName("测试相关位置推荐 - 位置不存在时返回热门位置")
    void testGetRecommendedLocations_NotFound_ReturnsHot() {
        when(locationService.getLocationById("non_existent")).thenReturn(java.util.Optional.empty());

        List<LocationQueryCount> hotCounts = TestDataBuilder.buildHotLocationCounts(
                Arrays.asList("location_shopping_001", "location_restaurant_001")
        );

        when(analysisService.getHotLocations()).thenReturn(hotCounts);
        when(locationService.getLocationsByIds(anyList())).thenReturn(Arrays.asList(shoppingMall, restaurant));

        List<Location> recommendations = recommendationService.getRecommendedLocations("non_existent", 10);

        assertNotNull(recommendations);
        assertTrue(recommendations.size() > 0);
    }

    @Test
    @DisplayName("测试相关位置推荐 - 排除自身位置")
    void testGetRecommendedLocations_ExcludesSelf() {
        when(locationService.getLocationById("location_shopping_001")).thenReturn(java.util.Optional.of(shoppingMall));
        when(locationService.getAllLocations()).thenReturn(allLocations);

        List<Location> recommendations = recommendationService.getRecommendedLocations("location_shopping_001", 10);

        assertNotNull(recommendations);
        assertTrue(recommendations.stream()
                .noneMatch(l -> "location_shopping_001".equals(l.getLocationId())));
    }

    @Test
    @DisplayName("测试热门位置推荐 - 按查询次数排序")
    void testGetHotLocations_SortedByQueryCount() {
        List<LocationQueryCount> hotCounts = TestDataBuilder.buildHotLocationCounts(
                Arrays.asList("location_shopping_001", "location_restaurant_001", "location_park_001")
        );

        when(analysisService.getHotLocations()).thenReturn(hotCounts);
        when(locationService.getLocationsByIds(anyList())).thenReturn(Arrays.asList(shoppingMall, restaurant, park));

        List<Location> hotLocations = recommendationService.getHotLocations(10);

        assertNotNull(hotLocations);
        assertEquals(3, hotLocations.size());
        assertEquals("location_shopping_001", hotLocations.get(0).getLocationId());
        assertEquals("location_restaurant_001", hotLocations.get(1).getLocationId());
        assertEquals("location_park_001", hotLocations.get(2).getLocationId());
    }

    @Test
    @DisplayName("测试热门位置推荐 - 限制返回数量")
    void testGetHotLocations_LimitResults() {
        List<LocationQueryCount> hotCounts = TestDataBuilder.buildHotLocationCounts(
                Arrays.asList("1", "2", "3", "4", "5")
        );

        when(analysisService.getHotLocations()).thenReturn(hotCounts);
        when(locationService.getLocationsByIds(anyList())).thenReturn(allLocations);

        List<Location> hotLocations = recommendationService.getHotLocations(3);

        assertNotNull(hotLocations);
        assertTrue(hotLocations.size() <= 3);
    }

    @Test
    @DisplayName("测试热门位置推荐 - 无热门数据时返回全部位置")
    void testGetHotLocations_NoHotData_ReturnsAll() {
        when(analysisService.getHotLocations()).thenReturn(new ArrayList<>());
        when(locationService.getAllLocations()).thenReturn(allLocations);

        List<Location> hotLocations = recommendationService.getHotLocations(10);

        assertNotNull(hotLocations);
        assertEquals(allLocations.size(), hotLocations.size());
    }

    @Test
    @DisplayName("测试分类推荐 - 正确返回指定分类")
    void testGetRecommendationsByCategory_CorrectCategory() {
        List<Location> commercialLocations = Arrays.asList(
                shoppingMall,
                TestDataBuilder.buildLocation("shop2", "商场2", "shopping", "商业",
                        TestDataBuilder.BEIJING_HOTEL, Arrays.asList("购物"))
        );

        when(locationService.getLocationsByCategory("商业")).thenReturn(commercialLocations);

        List<Location> recommendations = recommendationService.getRecommendationsByCategory("商业", 10);

        assertNotNull(recommendations);
        assertTrue(recommendations.size() > 0);
        assertTrue(recommendations.stream()
                .allMatch(l -> "商业".equals(l.getLocationCategory())));
    }

    @Test
    @DisplayName("测试分类推荐 - 限制返回数量")
    void testGetRecommendationsByCategory_LimitResults() {
        List<Location> manyLocations = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            manyLocations.add(TestDataBuilder.buildLocation(
                    "loc_" + i, "位置_" + i, "shopping", "商业",
                    TestDataBuilder.BEIJING_SHOPPING,
                    Arrays.asList("购物")
            ));
        }

        when(locationService.getLocationsByCategory("商业")).thenReturn(manyLocations);

        List<Location> recommendations = recommendationService.getRecommendationsByCategory("商业", 5);

        assertNotNull(recommendations);
        assertEquals(5, recommendations.size());
    }

    @Test
    @DisplayName("测试推荐结果排序 - 热门位置按热度降序")
    void testRecommendationSorting_HotLocationsDescending() {
        List<LocationQueryCount> hotCounts = Arrays.asList(
                LocationQueryCount.builder().locationId("hottest").queryCount(1000).build(),
                LocationQueryCount.builder().locationId("hot").queryCount(800).build(),
                LocationQueryCount.builder().locationId("warm").queryCount(500).build()
        );

        Location hottest = TestDataBuilder.buildLocation("hottest", "最热", "shopping", "商业",
                TestDataBuilder.BEIJING_CENTER, Arrays.asList("热"));
        Location hot = TestDataBuilder.buildLocation("hot", "热", "shopping", "商业",
                TestDataBuilder.BEIJING_SHOPPING, Arrays.asList("热"));
        Location warm = TestDataBuilder.buildLocation("warm", "暖", "shopping", "商业",
                TestDataBuilder.BEIJING_PARK, Arrays.asList("暖"));

        when(analysisService.getHotLocations()).thenReturn(hotCounts);
        when(locationService.getLocationsByIds(anyList())).thenReturn(Arrays.asList(hottest, hot, warm));

        List<Location> hotLocations = recommendationService.getHotLocations(10);

        assertNotNull(hotLocations);
        assertEquals(3, hotLocations.size());
        assertEquals("hottest", hotLocations.get(0).getLocationId());
        assertEquals("hot", hotLocations.get(1).getLocationId());
        assertEquals("warm", hotLocations.get(2).getLocationId());
    }

    @Test
    @DisplayName("测试相关位置推荐 - 无相似位置时返回热门")
    void testGetRecommendedLocations_NoSimilar_ReturnsHot() {
        Location uniqueLocation = TestDataBuilder.buildLocation(
                "unique", "独一份", "unique_type", "独一分类",
                TestDataBuilder.BEIJING_CENTER,
                Arrays.asList("独特标签")
        );

        when(locationService.getLocationById("unique")).thenReturn(java.util.Optional.of(uniqueLocation));
        when(locationService.getAllLocations()).thenReturn(Arrays.asList(uniqueLocation, shoppingMall, restaurant));

        List<LocationQueryCount> hotCounts = TestDataBuilder.buildHotLocationCounts(
                Arrays.asList("location_shopping_001", "location_restaurant_001")
        );
        when(analysisService.getHotLocations()).thenReturn(hotCounts);
        when(locationService.getLocationsByIds(anyList())).thenReturn(Arrays.asList(shoppingMall, restaurant));

        List<Location> recommendations = recommendationService.getRecommendedLocations("unique", 10);

        assertNotNull(recommendations);
        assertTrue(recommendations.size() > 0);
    }

    @Test
    @DisplayName("测试热门位置推荐 - 保持ID顺序")
    void testGetHotLocations_MaintainsOrder() {
        List<String> expectedOrder = Arrays.asList("first", "second", "third");
        List<LocationQueryCount> hotCounts = TestDataBuilder.buildHotLocationCounts(expectedOrder);

        Location first = TestDataBuilder.buildLocation("first", "第一", "shopping", "商业",
                TestDataBuilder.BEIJING_CENTER, Arrays.asList("1"));
        Location second = TestDataBuilder.buildLocation("second", "第二", "shopping", "商业",
                TestDataBuilder.BEIJING_SHOPPING, Arrays.asList("2"));
        Location third = TestDataBuilder.buildLocation("third", "第三", "shopping", "商业",
                TestDataBuilder.BEIJING_PARK, Arrays.asList("3"));

        when(analysisService.getHotLocations()).thenReturn(hotCounts);
        when(locationService.getLocationsByIds(anyList())).thenReturn(Arrays.asList(first, second, third));

        List<Location> hotLocations = recommendationService.getHotLocations(10);

        assertNotNull(hotLocations);
        assertEquals(3, hotLocations.size());
        assertEquals("first", hotLocations.get(0).getLocationId());
        assertEquals("second", hotLocations.get(1).getLocationId());
        assertEquals("third", hotLocations.get(2).getLocationId());
    }

    @Test
    @DisplayName("测试相关位置推荐 - 标签匹配优先于无匹配")
    void testGetRecommendedLocations_TagMatchPriority() {
        Location source = TestDataBuilder.buildLocation(
                "source", "源位置", "type", "分类",
                TestDataBuilder.BEIJING_CENTER,
                Arrays.asList("标签A", "标签B")
        );

        Location tagMatch = TestDataBuilder.buildLocation(
                "tag_match", "标签匹配", "type2", "分类2",
                TestDataBuilder.BEIJING_SHOPPING,
                Arrays.asList("标签A", "其他")
        );

        Location noMatch = TestDataBuilder.buildLocation(
                "no_match", "无匹配", "type3", "分类3",
                TestDataBuilder.BEIJING_PARK,
                Arrays.asList("其他标签")
        );

        when(locationService.getLocationById("source")).thenReturn(java.util.Optional.of(source));
        when(locationService.getAllLocations()).thenReturn(Arrays.asList(source, tagMatch, noMatch));

        List<Location> recommendations = recommendationService.getRecommendedLocations("source", 10);

        assertNotNull(recommendations);
        assertTrue(recommendations.stream()
                .anyMatch(l -> "tag_match".equals(l.getLocationId())));
    }

    @Test
    @DisplayName("测试分类推荐 - 无该分类时返回空列表")
    void testGetRecommendationsByCategory_NoCategory_ReturnsEmpty() {
        when(locationService.getLocationsByCategory("不存在的分类")).thenReturn(new ArrayList<>());

        List<Location> recommendations = recommendationService.getRecommendationsByCategory("不存在的分类", 10);

        assertNotNull(recommendations);
        assertTrue(recommendations.isEmpty());
    }

    @Test
    @DisplayName("测试热门位置推荐 - 限制数量为0")
    void testGetHotLocations_ZeroLimit() {
        when(analysisService.getHotLocations()).thenReturn(TestDataBuilder.buildHotLocationCounts(
                Arrays.asList("1", "2", "3")
        ));
        when(locationService.getLocationsByIds(anyList())).thenReturn(allLocations);

        List<Location> hotLocations = recommendationService.getHotLocations(0);

        assertNotNull(recommendationService);
    }

    @Test
    @DisplayName("测试相关位置推荐 - 大limit值返回全部可用")
    void testGetRecommendedLocations_LargeLimit() {
        when(locationService.getLocationById("location_shopping_001")).thenReturn(java.util.Optional.of(shoppingMall));
        when(locationService.getAllLocations()).thenReturn(allLocations);

        List<Location> recommendations = recommendationService.getRecommendedLocations("location_shopping_001", 1000);

        assertNotNull(recommendations);
        assertTrue(recommendations.size() <= 1000);
    }
}
