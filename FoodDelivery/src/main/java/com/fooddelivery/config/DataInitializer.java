package com.fooddelivery.config;

import com.fooddelivery.entity.*;
import com.fooddelivery.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    @Autowired
    private RegionService regionService;

    @Autowired
    private RestaurantService restaurantService;

    @Autowired
    private RiderService riderService;

    @Override
    public void run(String... args) {
        Region region1 = new Region();
        region1.setRegionName("朝阳区");
        region1.setRegionDesc("北京市朝阳区");
        regionService.createRegion(region1);

        Region region2 = new Region();
        region2.setRegionName("海淀区");
        region2.setRegionDesc("北京市海淀区");
        regionService.createRegion(region2);

        Restaurant restaurant1 = new Restaurant();
        restaurant1.setRestaurantName("美味中餐厅");
        restaurant1.setRestaurantType("chinese");
        restaurant1.setRestaurantAddress("朝阳区建国路88号");
        restaurant1.setRestaurantRegion("朝阳区");
        restaurant1.setRestaurantStatus("open");
        restaurant1.setRestaurantRating(4.5);
        Restaurant savedRestaurant1 = restaurantService.createRestaurant(restaurant1);

        Restaurant restaurant2 = new Restaurant();
        restaurant2.setRestaurantName("西餐厅");
        restaurant2.setRestaurantType("western");
        restaurant2.setRestaurantAddress("海淀区中关村大街1号");
        restaurant2.setRestaurantRegion("海淀区");
        restaurant2.setRestaurantStatus("open");
        restaurant2.setRestaurantRating(4.2);
        Restaurant savedRestaurant2 = restaurantService.createRestaurant(restaurant2);

        Dish dish1 = new Dish();
        dish1.setDishName("宫保鸡丁");
        dish1.setDishPrice(30.0);
        dish1.setDishDesc("经典川菜");
        dish1.setDishStatus("active");
        restaurantService.createDish(savedRestaurant1.getRestaurantId(), dish1);

        Dish dish2 = new Dish();
        dish2.setDishName("鱼香肉丝");
        dish2.setDishPrice(25.0);
        dish2.setDishDesc("家常菜");
        dish2.setDishStatus("active");
        restaurantService.createDish(savedRestaurant1.getRestaurantId(), dish2);

        Dish dish3 = new Dish();
        dish3.setDishName("麻婆豆腐");
        dish3.setDishPrice(20.0);
        dish3.setDishDesc("川菜名菜");
        dish3.setDishStatus("active");
        restaurantService.createDish(savedRestaurant1.getRestaurantId(), dish3);

        Dish dish4 = new Dish();
        dish4.setDishName("牛排");
        dish4.setDishPrice(88.0);
        dish4.setDishDesc("菲力牛排");
        dish4.setDishStatus("active");
        restaurantService.createDish(savedRestaurant2.getRestaurantId(), dish4);

        Rider rider1 = new Rider();
        rider1.setRiderName("张三");
        rider1.setRiderPhone("13800138001");
        rider1.setRiderRegion("朝阳区");
        rider1.setRiderStatus("available");
        rider1.setRiderRating(4.8);
        rider1.setRiderCount(120);
        riderService.createRider(rider1);

        Rider rider2 = new Rider();
        rider2.setRiderName("李四");
        rider2.setRiderPhone("13800138002");
        rider2.setRiderRegion("朝阳区");
        rider2.setRiderStatus("available");
        rider2.setRiderRating(4.6);
        rider2.setRiderCount(90);
        riderService.createRider(rider2);

        Rider rider3 = new Rider();
        rider3.setRiderName("王五");
        rider3.setRiderPhone("13800138003");
        rider3.setRiderRegion("海淀区");
        rider3.setRiderStatus("available");
        rider3.setRiderRating(4.9);
        rider3.setRiderCount(150);
        riderService.createRider(rider3);
    }
}
