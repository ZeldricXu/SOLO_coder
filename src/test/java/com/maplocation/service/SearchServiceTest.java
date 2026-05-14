package com.maplocation.service;

import com.maplocation.builder.TestDataBuilder;
import com.maplocation.dto.SearchRequest;
import com.maplocation.dto.SearchResponse;
import com.maplocation.model.Location;
import com.maplocation.repository.LocationRepository;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private SearchService searchService;

    private List<Location> testLocations;

    @BeforeEach
    void setUp() {
        testLocations = Arrays.asList(
                TestDataBuilder.buildShoppingMall(),
                TestDataBuilder.buildRestaurant(),
                TestDataBuilder.buildPark(),
                TestDataBuilder.buildHotel(),
                TestDataBuilder.buildStation()
        );
    }

    @Test
    @DisplayName("测试关键字检索 - 完全匹配")
    void testKeywordSearch_ExactMatch() {
        Location shoppingMall = TestDataBuilder.buildShoppingMall();
        when(locationRepository.searchByKeyword("大悦城")).thenReturn(Arrays.asList(shoppingMall));

        SearchRequest request = TestDataBuilder.buildKeywordSearchRequest("大悦城");
        SearchResponse response = searchService.searchLocations(request);

        assertNotNull(response);
        assertEquals(1, response.getLocations().size());
        assertEquals("北京朝阳大悦城", response.getLocations().get(0).getLocationName());
        verify(analysisService, times(1)).incrementQueryCount();
        verify(historyService, times(1)).recordSearchHistory(any(), anyList());
    }

    @Test
    @DisplayName("测试关键字检索 - 部分匹配名称")
    void testKeywordSearch_PartialMatchName() {
        when(locationRepository.searchByKeyword("北京")).thenReturn(new ArrayList<>());
        when(locationRepository.findAll()).thenReturn(testLocations);

        SearchRequest request = TestDataBuilder.buildKeywordSearchRequest("北京");
        SearchResponse response = searchService.searchLocations(request);

        assertNotNull(response);
        assertTrue(response.getLocations().size() > 0);
        assertTrue(response.getLocations().stream()
                .anyMatch(l -> l.getLocationName().contains("北京")));
    }

    @Test
    @DisplayName("测试关键字检索 - 按分类匹配")
    void testKeywordSearch_CategoryMatch() {
        Location park = TestDataBuilder.buildPark();
        when(locationRepository.searchByKeyword("休闲")).thenReturn(new ArrayList<>());
        when(locationRepository.findAll()).thenReturn(testLocations);

        SearchRequest request = TestDataBuilder.buildKeywordSearchRequest("休闲");
        SearchResponse response = searchService.searchLocations(request);

        assertNotNull(response);
        assertTrue(response.getLocations().size() > 0);
        assertTrue(response.getLocations().stream()
                .anyMatch(l -> "休闲".equals(l.getLocationCategory())));
    }

    @Test
    @DisplayName("测试关键字检索 - 无结果返回空列表")
    void testKeywordSearch_NoResults() {
        when(locationRepository.searchByKeyword("不存在的关键字")).thenReturn(new ArrayList<>());
        when(locationRepository.findAll()).thenReturn(testLocations);

        SearchRequest request = TestDataBuilder.buildKeywordSearchRequest("不存在的关键字");
        SearchResponse response = searchService.searchLocations(request);

        assertNotNull(response);
        assertEquals(0, response.getTotalCount());
        assertEquals(0, response.getLocations().size());
    }

    @Test
    @DisplayName("测试检索结果排序 - 按匹配分数降序")
    void testSearchResults_SortedByMatchScore() {
        List<Location> locationsWithDifferentScores = Arrays.asList(
                TestDataBuilder.buildLocation("l1", "购物中心购物中心", "shopping", "商业",
                        TestDataBuilder.BEIJING_SHOPPING, Arrays.asList("购物", "购物中心")),
                TestDataBuilder.buildLocation("l2", "普通商店", "shopping", "商业",
                        TestDataBuilder.BEIJING_HOTEL, Arrays.asList("购物"))
        );

        when(locationRepository.searchByKeyword("购物")).thenReturn(locationsWithDifferentScores);

        SearchRequest request = TestDataBuilder.buildKeywordSearchRequest("购物");
        SearchResponse response = searchService.searchLocations(request);

        assertNotNull(response);
        assertEquals(2, response.getLocations().size());
        assertTrue(response.getLocations().get(0).getLocationName().contains("购物中心"));
    }

    @Test
    @DisplayName("测试范围检索 - 正确过滤范围内位置")
    void testRangeSearch_FilterWithinRadius() {
        Location nearLocation = TestDataBuilder.buildLocation("near", "附近位置", "shopping", "商业",
                new com.maplocation.model.Coordinates(39.9045, 116.4075), Arrays.asList("测试"));
        Location farLocation = TestDataBuilder.buildLocation("far", "远处位置", "shopping", "商业",
                new com.maplocation.model.Coordinates(40.0, 117.0), Arrays.asList("测试"));

        when(locationRepository.findAll()).thenReturn(Arrays.asList(nearLocation, farLocation));

        SearchRequest request = TestDataBuilder.buildRangeSearchRequest(TestDataBuilder.BEIJING_CENTER, 5000.0);
        SearchResponse response = searchService.searchLocations(request);

        assertNotNull(response);
        assertEquals(1, response.getLocations().size());
        assertEquals("near", response.getLocations().get(0).getLocationId());
    }

    @Test
    @DisplayName("测试范围检索 - 按距离升序排序")
    void testRangeSearch_SortedByDistance() {
        Location loc1 = TestDataBuilder.buildLocation("l1", "位置1", "shopping", "商业",
                new com.maplocation.model.Coordinates(39.9043, 116.4075), Arrays.asList("测试"));
        Location loc2 = TestDataBuilder.buildLocation("l2", "位置2", "shopping", "商业",
                new com.maplocation.model.Coordinates(39.9046, 116.4078), Arrays.asList("测试"));
        Location loc3 = TestDataBuilder.buildLocation("l3", "位置3", "shopping", "商业",
                new com.maplocation.model.Coordinates(39.9050, 116.4080), Arrays.asList("测试"));

        when(locationRepository.findAll()).thenReturn(Arrays.asList(loc3, loc1, loc2));

        SearchRequest request = TestDataBuilder.buildRangeSearchRequest(TestDataBuilder.BEIJING_CENTER, 10000.0);
        SearchResponse response = searchService.searchLocations(request);

        assertNotNull(response);
        assertEquals(3, response.getLocations().size());
        assertEquals("l1", response.getLocations().get(0).getLocationId());
        assertEquals("l2", response.getLocations().get(1).getLocationId());
        assertEquals("l3", response.getLocations().get(2).getLocationId());
    }

    @Test
    @DisplayName("测试分类过滤 - 仅返回指定分类")
    void testSearch_WithCategoryFilter() {
        when(locationRepository.findAll()).thenReturn(testLocations);

        SearchRequest request = SearchRequest.builder()
                .searchType("keyword")
                .category("商业")
                .build();

        SearchResponse response = searchService.searchLocations(request);

        assertNotNull(response);
        assertTrue(response.getLocations().stream()
                .allMatch(l -> "商业".equals(l.getLocationCategory())));
    }

    @Test
    @DisplayName("测试类型过滤 - 仅返回指定类型")
    void testSearch_WithTypeFilter() {
        when(locationRepository.findAll()).thenReturn(testLocations);

        SearchRequest request = SearchRequest.builder()
                .searchType("keyword")
                .locationType("restaurant")
                .build();

        SearchResponse response = searchService.searchLocations(request);

        assertNotNull(response);
        assertTrue(response.getLocations().stream()
                .allMatch(l -> "restaurant".equals(l.getLocationType())));
    }

    @Test
    @DisplayName("测试分页 - 第一页正确返回")
    void testSearch_Pagination_FirstPage() {
        List<Location> manyLocations = TestDataBuilder.buildMultipleLocations(25);
        when(locationRepository.findAll()).thenReturn(manyLocations);

        SearchRequest request = SearchRequest.builder()
                .searchType("keyword")
                .page(0)
                .size(10)
                .build();

        SearchResponse response = searchService.searchLocations(request);

        assertNotNull(response);
        assertEquals(25, response.getTotalCount());
        assertEquals(10, response.getLocations().size());
        assertEquals(0, response.getPage());
        assertEquals(10, response.getSize());
    }

    @Test
    @DisplayName("测试分页 - 最后一页正确返回")
    void testSearch_Pagination_LastPage() {
        List<Location> manyLocations = TestDataBuilder.buildMultipleLocations(25);
        when(locationRepository.findAll()).thenReturn(manyLocations);

        SearchRequest request = SearchRequest.builder()
                .searchType("keyword")
                .page(2)
                .size(10)
                .build();

        SearchResponse response = searchService.searchLocations(request);

        assertNotNull(response);
        assertEquals(5, response.getLocations().size());
    }

    @Test
    @DisplayName("测试大规模数据检索性能")
    void testSearch_LargeDataSetPerformance() {
        int largeCount = 1000;
        List<Location> largeData = TestDataBuilder.buildMultipleLocations(largeCount);
        when(locationRepository.findAll()).thenReturn(largeData);

        SearchRequest request = SearchRequest.builder()
                .searchType("keyword")
                .page(0)
                .size(20)
                .build();

        long startTime = System.currentTimeMillis();
        SearchResponse response = searchService.searchLocations(request);
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        assertNotNull(response);
        assertEquals(largeCount, response.getTotalCount());
        assertTrue(duration < 1000, "检索操作应在1秒内完成，实际耗时: " + duration + "ms");
    }

    @Test
    @DisplayName("测试索引更新 - 新添加位置可被检索")
    void testIndexUpdate_NewLocationSearchable() {
        Location newLocation = TestDataBuilder.buildLocation(
                "new_loc", "新开业商场", "shopping", "商业",
                TestDataBuilder.BEIJING_SHOPPING, Arrays.asList("新开业", "商场"));

        when(locationRepository.searchByKeyword("新开业")).thenReturn(Arrays.asList(newLocation));

        SearchRequest request = TestDataBuilder.buildKeywordSearchRequest("新开业");
        SearchResponse response = searchService.searchLocations(request);

        assertNotNull(response);
        assertEquals(1, response.getLocations().size());
        assertEquals("新开业商场", response.getLocations().get(0).getLocationName());
    }

    @Test
    @DisplayName("测试索引时效性 - 更新后位置信息可检索")
    void testIndexTimeliness_UpdatedLocationSearchable() {
        Location updatedLocation = TestDataBuilder.buildLocation(
                "updated_loc", "更新后的商场", "shopping", "商业",
                TestDataBuilder.BEIJING_SHOPPING, Arrays.asList("更新", "商场"));

        when(locationRepository.searchByKeyword("更新后")).thenReturn(Arrays.asList(updatedLocation));

        SearchRequest request = TestDataBuilder.buildKeywordSearchRequest("更新后");
        SearchResponse response = searchService.searchLocations(request);

        assertNotNull(response);
        assertEquals(1, response.getLocations().size());
        assertTrue(response.getLocations().get(0).getLocationName().contains("更新后"));
    }

    @Test
    @DisplayName("测试检索异常处理 - 异常时返回空结果")
    void testSearch_ExceptionHandling() {
        when(locationRepository.findAll()).thenThrow(new RuntimeException("数据库连接失败"));

        SearchRequest request = SearchRequest.builder()
                .searchType("keyword")
                .build();

        SearchResponse response = searchService.searchLocations(request);

        assertNotNull(response);
        assertEquals(0, response.getTotalCount());
        assertEquals(0, response.getLocations().size());
    }

    @Test
    @DisplayName("测试空关键字检索 - 返回全部数据")
    void testSearch_EmptyKeyword() {
        when(locationRepository.findAll()).thenReturn(testLocations);

        SearchRequest request = TestDataBuilder.buildKeywordSearchRequest("");
        SearchResponse response = searchService.searchLocations(request);

        assertNotNull(response);
        assertEquals(testLocations.size(), response.getTotalCount());
    }

    @Test
    @DisplayName("测试范围检索 - 缺少中心坐标返回空")
    void testRangeSearch_MissingCenter() {
        SearchRequest request = SearchRequest.builder()
                .searchType("range")
                .searchRadius(1000.0)
                .build();

        SearchResponse response = searchService.searchLocations(request);

        assertNotNull(response);
        assertEquals(0, response.getTotalCount());
    }

    @Test
    @DisplayName("测试范围检索 - 缺少半径返回空")
    void testRangeSearch_MissingRadius() {
        SearchRequest request = SearchRequest.builder()
                .searchType("range")
                .centerLocation(TestDataBuilder.BEIJING_CENTER)
                .build();

        SearchResponse response = searchService.searchLocations(request);

        assertNotNull(response);
        assertEquals(0, response.getTotalCount());
    }
}
