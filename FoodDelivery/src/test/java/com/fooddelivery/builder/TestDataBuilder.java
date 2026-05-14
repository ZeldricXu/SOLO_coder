package com.fooddelivery.builder;

import com.fooddelivery.entity.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TestDataBuilder {

    public static Region buildRegion() {
        Region region = new Region();
        region.setRegionId("region_test_001");
        region.setRegionName("朝阳区");
        region.setRegionDesc("北京市朝阳区");
        region.setCreatedAt(LocalDateTime.now());
        return region;
    }

    public static Region buildRegion(String name) {
        Region region = buildRegion();
        region.setRegionId("region_" + UUID.randomUUID().toString().substring(0, 8));
        region.setRegionName(name);
        return region;
    }

    public static Restaurant buildRestaurant() {
        Restaurant restaurant = new Restaurant();
        restaurant.setRestaurantId("restaurant_test_001");
        restaurant.setRestaurantName("美味中餐厅");
        restaurant.setRestaurantType("chinese");
        restaurant.setRestaurantAddress("朝阳区建国路88号");
        restaurant.setRestaurantRegion("朝阳区");
        restaurant.setRestaurantStatus("open");
        restaurant.setRestaurantRating(4.5);
        restaurant.setRestaurantRatingCount(10);
        restaurant.setRestaurantOrderCount(100);
        restaurant.setCreatedAt(LocalDateTime.now());
        return restaurant;
    }

    public static Restaurant buildRestaurant(String region) {
        Restaurant restaurant = buildRestaurant();
        restaurant.setRestaurantId("restaurant_" + UUID.randomUUID().toString().substring(0, 8));
        restaurant.setRestaurantRegion(region);
        return restaurant;
    }

    public static Restaurant buildRestaurantClosed() {
        Restaurant restaurant = buildRestaurant();
        restaurant.setRestaurantStatus("closed");
        return restaurant;
    }

    public static Dish buildDish(String restaurantId) {
        Dish dish = new Dish();
        dish.setDishId("dish_test_001");
        dish.setRestaurantId(restaurantId);
        dish.setDishName("宫保鸡丁");
        dish.setDishPrice(30.0);
        dish.setDishDesc("经典川菜");
        dish.setDishStatus("active");
        dish.setCreatedAt(LocalDateTime.now());
        return dish;
    }

    public static Dish buildDish(String restaurantId, String name, double price) {
        Dish dish = buildDish(restaurantId);
        dish.setDishId("dish_" + UUID.randomUUID().toString().substring(0, 8));
        dish.setDishName(name);
        dish.setDishPrice(price);
        return dish;
    }

    public static Rider buildRider() {
        Rider rider = new Rider();
        rider.setRiderId("rider_test_001");
        rider.setRiderName("张三");
        rider.setRiderPhone("13800138001");
        rider.setRiderRegion("朝阳区");
        rider.setRiderStatus("available");
        rider.setRiderRating(4.8);
        rider.setRiderRatingCount(50);
        rider.setRiderCount(120);
        rider.setCreatedAt(LocalDateTime.now());
        return rider;
    }

    public static Rider buildRider(String region) {
        Rider rider = buildRider();
        rider.setRiderId("rider_" + UUID.randomUUID().toString().substring(0, 8));
        rider.setRiderRegion(region);
        return rider;
    }

    public static Rider buildRiderUnavailable() {
        Rider rider = buildRider();
        rider.setRiderStatus("busy");
        rider.setRiderCurrentOrder("order_busy_001");
        return rider;
    }

    public static Order buildOrder() {
        Order order = new Order();
        order.setOrderId("order_test_001");
        order.setRestaurantId("restaurant_test_001");
        order.setUserId("user_test_001");
        order.setOrderAmount(60.0);
        order.setDeliveryFee(0.0);
        order.setDeliveryAddress("朝阳区建国路99号");
        order.setDeliveryRegion("朝阳区");
        order.setOrderStatus("pending_confirm");
        order.setPaymentStatus("pending");
        order.setHasReview(false);
        order.setOrderTime(LocalDateTime.now());
        order.setCreatedAt(LocalDateTime.now());
        return order;
    }

    public static Order buildOrder(String status) {
        Order order = buildOrder();
        order.setOrderId("order_" + UUID.randomUUID().toString().substring(0, 8));
        order.setOrderStatus(status);
        if ("confirmed".equals(status) || "delivering".equals(status) || "delivered".equals(status)) {
            order.setConfirmedAt(LocalDateTime.now());
        }
        if ("delivered".equals(status) || "reviewed".equals(status)) {
            order.setDeliveredAt(LocalDateTime.now());
        }
        if ("reviewed".equals(status)) {
            order.setHasReview(true);
        }
        return order;
    }

    public static Order buildOrderDelivered() {
        return buildOrder("delivered");
    }

    public static Order buildOrderReviewed() {
        return buildOrder("reviewed");
    }

    public static Delivery buildDelivery() {
        Delivery delivery = new Delivery();
        delivery.setDeliveryId("delivery_test_001");
        delivery.setOrderId("order_test_001");
        delivery.setRiderId("rider_test_001");
        delivery.setRestaurantId("restaurant_test_001");
        delivery.setDeliveryStatus("pending_pickup");
        delivery.setCurrentLocation("朝阳区");
        delivery.setCreatedAt(LocalDateTime.now());
        return delivery;
    }

    public static Delivery buildDelivery(String status) {
        Delivery delivery = buildDelivery();
        delivery.setDeliveryId("delivery_" + UUID.randomUUID().toString().substring(0, 8));
        delivery.setDeliveryStatus(status);
        if ("picked_up".equals(status) || "delivering".equals(status)) {
            delivery.setPickupTime(LocalDateTime.now());
        }
        if ("delivered".equals(status)) {
            delivery.setPickupTime(LocalDateTime.now().minusMinutes(30));
            delivery.setDeliveryTime(LocalDateTime.now());
        }
        return delivery;
    }

    public static Review buildReview() {
        Review review = new Review();
        review.setReviewId("review_test_001");
        review.setOrderId("order_test_001");
        review.setUserId("user_test_001");
        review.setRestaurantId("restaurant_test_001");
        review.setRiderId("rider_test_001");
        review.setReviewRating(5);
        review.setReviewContent("非常好的体验！");
        review.setReviewTime(LocalDateTime.now());
        return review;
    }

    public static Review buildReview(String orderId, int rating) {
        Review review = buildReview();
        review.setReviewId("review_" + UUID.randomUUID().toString().substring(0, 8));
        review.setOrderId(orderId);
        review.setReviewRating(rating);
        return review;
    }

    public static Notify buildNotify() {
        Notify notify = new Notify();
        notify.setNotifyId("notify_test_001");
        notify.setOrderId("order_test_001");
        notify.setNotifyType("status");
        notify.setNotifyStatus("delivered");
        notify.setNotifyMessage("订单已送达");
        notify.setIsRead(false);
        notify.setNotifyTime(LocalDateTime.now());
        return notify;
    }

    public static Track buildTrack() {
        Track track = new Track();
        track.setTrackId("track_test_001");
        track.setDeliveryId("delivery_test_001");
        track.setTrackStatus("delivering");
        track.setTrackLocation("朝阳区建国路中段");
        track.setTrackTime(LocalDateTime.now());
        return track;
    }

    public static Stat buildStat() {
        Stat stat = new Stat();
        stat.setStatId("stat_test_001");
        stat.setStatMonth("2026-05");
        stat.setOrderCount(1000);
        stat.setDeliveryCount(900);
        stat.setCancelCount(100);
        stat.setAvgDeliveryTime(30.0);
        stat.setTotalDeliveryTime(27000L);
        stat.setTotalAmount(50000.0);
        stat.setReviewCount(800);
        stat.setAvgRating(4.5);
        stat.setUpdatedAt(LocalDateTime.now());
        return stat;
    }

    public static History buildHistory() {
        History history = new History();
        history.setHistoryId("history_test_001");
        history.setHistoryType("order");
        history.setRelatedId("order_test_001");
        history.setAction("create");
        history.setDetail("创建订单");
        history.setCreatedAt(LocalDateTime.now());
        return history;
    }

    public static List<Dish> buildDishList(String restaurantId) {
        List<Dish> dishes = new ArrayList<>();
        dishes.add(buildDish(restaurantId, "宫保鸡丁", 30.0));
        dishes.add(buildDish(restaurantId, "鱼香肉丝", 25.0));
        dishes.add(buildDish(restaurantId, "麻婆豆腐", 20.0));
        return dishes;
    }

    public static List<Rider> buildRiderList(String region, int count) {
        List<Rider> riders = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Rider rider = buildRider(region);
            rider.setRiderName("骑手" + (i + 1));
            rider.setRiderRating(4.0 + Math.random() * 1.0);
            rider.setRiderCount((int) (Math.random() * 200));
            riders.add(rider);
        }
        return riders;
    }

    public static List<Track> buildTrackList(String deliveryId) {
        List<Track> tracks = new ArrayList<>();
        String[] locations = {"餐厅位置", "建国路东段", "建国路中段", "建国路西段", "用户位置"};
        String[] statuses = {"pending_pickup", "picked_up", "delivering", "delivering", "delivered"};
        for (int i = 0; i < 5; i++) {
            Track track = new Track();
            track.setTrackId("track_" + UUID.randomUUID().toString().substring(0, 8));
            track.setDeliveryId(deliveryId);
            track.setTrackStatus(statuses[i]);
            track.setTrackLocation(locations[i]);
            track.setTrackTime(LocalDateTime.now().minusMinutes(30 - i * 5));
            tracks.add(track);
        }
        return tracks;
    }
}
