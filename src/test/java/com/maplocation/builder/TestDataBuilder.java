package com.maplocation.builder;

import com.maplocation.dto.DistanceCalculateRequest;
import com.maplocation.dto.LocationCreateRequest;
import com.maplocation.dto.RoutePlanRequest;
import com.maplocation.dto.SearchRequest;
import com.maplocation.model.Coordinates;
import com.maplocation.model.Location;
import com.maplocation.model.LocationQueryCount;
import com.maplocation.model.Route;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class TestDataBuilder {

    public static final Coordinates BEIJING_CENTER = new Coordinates(39.9042, 116.4074);
    public static final Coordinates BEIJING_SHOPPING = new Coordinates(39.9100, 116.4100);
    public static final Coordinates BEIJING_RESTAURANT = new Coordinates(39.9080, 116.4050);
    public static final Coordinates BEIJING_PARK = new Coordinates(39.9150, 116.4020);
    public static final Coordinates BEIJING_HOTEL = new Coordinates(39.9000, 116.4150);
    public static final Coordinates BEIJING_STATION = new Coordinates(39.9200, 116.4200);

    public static Coordinates createCoordinates(double lat, double lng) {
        return new Coordinates(lat, lng);
    }

    public static Location buildLocation(String id, String name, String type, String category,
                                         Coordinates coordinates, List<String> tags) {
        return Location.builder()
                .locationId(id != null ? id : "location_" + UUID.randomUUID().toString().substring(0, 8))
                .locationName(name)
                .locationType(type)
                .locationAddress("北京市朝阳区测试地址")
                .locationCoordinates(coordinates)
                .locationCategory(category)
                .locationTags(tags)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    public static Location buildShoppingMall() {
        return buildLocation(
                "location_shopping_001",
                "北京朝阳大悦城",
                "shopping",
                "商业",
                BEIJING_SHOPPING,
                Arrays.asList("购物", "商业", "购物中心")
        );
    }

    public static Location buildRestaurant() {
        return buildLocation(
                "location_restaurant_001",
                "全聚德烤鸭店",
                "restaurant",
                "餐饮",
                BEIJING_RESTAURANT,
                Arrays.asList("美食", "餐饮", "北京菜")
        );
    }

    public static Location buildPark() {
        return buildLocation(
                "location_park_001",
                "朝阳公园",
                "park",
                "休闲",
                BEIJING_PARK,
                Arrays.asList("公园", "休闲", "娱乐")
        );
    }

    public static Location buildHotel() {
        return buildLocation(
                "location_hotel_001",
                "北京大酒店",
                "hotel",
                "住宿",
                BEIJING_HOTEL,
                Arrays.asList("酒店", "住宿", "商务")
        );
    }

    public static Location buildStation() {
        return buildLocation(
                "location_station_001",
                "北京站",
                "station",
                "交通",
                BEIJING_STATION,
                Arrays.asList("火车站", "交通", "出行")
        );
    }

    public static List<Location> buildMultipleLocations(int count) {
        List<Location> locations = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double lat = 39.90 + (i % 10) * 0.001;
            double lng = 116.40 + (i / 10) * 0.001;
            String name;
            String type;
            String category;
            List<String> tags;

            switch (i % 5) {
                case 0:
                    name = "购物中心_" + i;
                    type = "shopping";
                    category = "商业";
                    tags = Arrays.asList("购物", "商业");
                    break;
                case 1:
                    name = "餐厅_" + i;
                    type = "restaurant";
                    category = "餐饮";
                    tags = Arrays.asList("美食", "餐饮");
                    break;
                case 2:
                    name = "公园_" + i;
                    type = "park";
                    category = "休闲";
                    tags = Arrays.asList("公园", "休闲");
                    break;
                case 3:
                    name = "酒店_" + i;
                    type = "hotel";
                    category = "住宿";
                    tags = Arrays.asList("酒店", "住宿");
                    break;
                default:
                    name = "车站_" + i;
                    type = "station";
                    category = "交通";
                    tags = Arrays.asList("交通", "出行");
            }

            locations.add(buildLocation(
                    "location_" + i,
                    name,
                    type,
                    category,
                    createCoordinates(lat, lng),
                    tags
            ));
        }
        return locations;
    }

    public static LocationCreateRequest buildLocationCreateRequest(String name, String type, String category,
                                                                    Coordinates coordinates, List<String> tags) {
        return LocationCreateRequest.builder()
                .locationName(name)
                .locationType(type)
                .locationAddress("北京市朝阳区测试地址")
                .locationCoordinates(coordinates)
                .locationCategory(category)
                .locationTags(tags)
                .build();
    }

    public static SearchRequest buildKeywordSearchRequest(String keyword) {
        return SearchRequest.builder()
                .keyword(keyword)
                .searchType("keyword")
                .page(0)
                .size(20)
                .build();
    }

    public static SearchRequest buildRangeSearchRequest(Coordinates center, double radius) {
        return SearchRequest.builder()
                .searchType("range")
                .centerLocation(center)
                .searchRadius(radius)
                .page(0)
                .size(20)
                .build();
    }

    public static RoutePlanRequest buildRouteRequest(Coordinates start, Coordinates end, String routeType) {
        return RoutePlanRequest.builder()
                .startLocation(start)
                .endLocation(end)
                .routeType(routeType)
                .build();
    }

    public static RoutePlanRequest buildDrivingRouteRequest() {
        return buildRouteRequest(BEIJING_CENTER, BEIJING_SHOPPING, "driving");
    }

    public static RoutePlanRequest buildWalkingRouteRequest() {
        return buildRouteRequest(BEIJING_CENTER, BEIJING_RESTAURANT, "walking");
    }

    public static RoutePlanRequest buildTransitRouteRequest() {
        return buildRouteRequest(BEIJING_CENTER, BEIJING_STATION, "transit");
    }

    public static DistanceCalculateRequest buildDistanceRequest(Coordinates from, Coordinates to, String distanceType) {
        return DistanceCalculateRequest.builder()
                .fromLocation(from)
                .toLocation(to)
                .distanceType(distanceType)
                .build();
    }

    public static DistanceCalculateRequest buildDirectDistanceRequest() {
        return buildDistanceRequest(BEIJING_CENTER, BEIJING_SHOPPING, "direct");
    }

    public static List<LocationQueryCount> buildHotLocationCounts(List<String> locationIds) {
        List<LocationQueryCount> counts = new ArrayList<>();
        int queryCount = 1000;
        for (String locationId : locationIds) {
            counts.add(LocationQueryCount.builder()
                    .locationId(locationId)
                    .queryCount(queryCount)
                    .build());
            queryCount -= 100;
        }
        return counts;
    }

    public static Route buildRoute(String routeId, Coordinates start, Coordinates end,
                                   String routeType, double distance, int duration) {
        return Route.builder()
                .routeId(routeId)
                .startLocation(start)
                .endLocation(end)
                .routeType(routeType)
                .routeDistance(distance)
                .routeDuration(duration)
                .routePath(Arrays.asList(start, end))
                .calculatedAt(Instant.now())
                .build();
    }

    public static List<Coordinates> buildComplexWaypoints() {
        return Arrays.asList(
                BEIJING_CENTER,
                BEIJING_SHOPPING,
                BEIJING_RESTAURANT,
                BEIJING_PARK,
                BEIJING_HOTEL
        );
    }
}
