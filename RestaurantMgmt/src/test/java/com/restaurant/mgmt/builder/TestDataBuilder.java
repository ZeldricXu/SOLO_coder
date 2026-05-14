package com.restaurant.mgmt.builder;

import com.restaurant.mgmt.model.Dish;
import com.restaurant.mgmt.model.DishIngredient;
import com.restaurant.mgmt.model.Order;
import com.restaurant.mgmt.model.OrderItem;
import com.restaurant.mgmt.model.RestaurantTable;
import com.restaurant.mgmt.model.Stock;
import com.restaurant.mgmt.model.StockWarning;
import com.restaurant.mgmt.model.Employee;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TestDataBuilder {

    public static final String SMALL_ORDER_ID = "order_test_small_001";
    public static final String MEDIUM_ORDER_ID = "order_test_medium_001";
    public static final String LARGE_ORDER_ID = "order_test_large_001";

    public static final double SMALL_ORDER_AMOUNT = 58.0;
    public static final double MEDIUM_ORDER_AMOUNT = 280.0;
    public static final double LARGE_ORDER_AMOUNT = 850.0;

    public static final String DISH_SIGNATURE_ID = "dish_signature_001";
    public static final String DISH_APPETIZER_ID = "dish_appetizer_001";
    public static final String DISH_DRINK_ID = "dish_drink_001";

    public static final String TABLE_A01_ID = "table_test_a01";
    public static final String TABLE_A02_ID = "table_test_a02";
    public static final String TABLE_B01_ID = "table_test_b01";
    public static final String TABLE_B02_ID = "table_test_b02";

    public static final String INGREDIENT_CRITICAL_1 = "ingredient_critical_001";
    public static final String INGREDIENT_CRITICAL_2 = "ingredient_critical_002";
    public static final String INGREDIENT_NORMAL_1 = "ingredient_normal_001";
    public static final String INGREDIENT_NORMAL_2 = "ingredient_normal_002";

    public static final double WARNING_THRESHOLD = 10.0;

    private TestDataBuilder() {
    }

    public static Order buildSmallOrder() {
        Order order = new Order();
        order.setOrderId(SMALL_ORDER_ID);
        order.setTableId(TABLE_A01_ID);
        order.setTableNumber("A01");
        order.setOrderAmount(SMALL_ORDER_AMOUNT);
        order.setOrderStatus("pending_payment");
        order.setPaymentMethod("wechat");
        order.setCreatedAt(LocalDateTime.now().minusMinutes(4));
        order.setOrderItems(buildOrderItemsForSmallOrder());
        return order;
    }

    public static Order buildMediumOrder() {
        Order order = new Order();
        order.setOrderId(MEDIUM_ORDER_ID);
        order.setTableId(TABLE_A02_ID);
        order.setTableNumber("A02");
        order.setOrderAmount(MEDIUM_ORDER_AMOUNT);
        order.setOrderStatus("pending_payment");
        order.setPaymentMethod("alipay");
        order.setCreatedAt(LocalDateTime.now().minusMinutes(8));
        order.setOrderItems(buildOrderItemsForMediumOrder());
        return order;
    }

    public static Order buildLargeOrder() {
        Order order = new Order();
        order.setOrderId(LARGE_ORDER_ID);
        order.setTableId(TABLE_B01_ID);
        order.setTableNumber("B01");
        order.setOrderAmount(LARGE_ORDER_AMOUNT);
        order.setOrderStatus("pending_payment");
        order.setPaymentMethod("card");
        order.setCreatedAt(LocalDateTime.now().minusMinutes(18));
        order.setOrderItems(buildOrderItemsForLargeOrder());
        return order;
    }

    public static Order buildOrderWithStatus(String status) {
        Order order = buildSmallOrder();
        order.setOrderStatus(status);
        if ("confirmed".equals(status)) {
            order.setConfirmedAt(LocalDateTime.now());
        } else if ("cancelled".equals(status)) {
            order.setCancelledAt(LocalDateTime.now());
            order.setCancelReason("测试取消");
        } else if ("completed".equals(status)) {
            order.setConfirmedAt(LocalDateTime.now().minusMinutes(30));
            order.setCompletedAt(LocalDateTime.now());
        }
        return order;
    }

    public static Order buildOrderCreatedAt(int minutesAgo) {
        Order order = buildSmallOrder();
        order.setCreatedAt(LocalDateTime.now().minusMinutes(minutesAgo));
        return order;
    }

    private static List<OrderItem> buildOrderItemsForSmallOrder() {
        List<OrderItem> items = new ArrayList<>();
        OrderItem item = new OrderItem();
        item.setDishId(DISH_SIGNATURE_ID);
        item.setDishName("招牌菜品");
        item.setQuantity(1);
        item.setPrice(58.0);
        item.setSubtotal(58.0);
        items.add(item);
        return items;
    }

    private static List<OrderItem> buildOrderItemsForMediumOrder() {
        List<OrderItem> items = new ArrayList<>();
        
        OrderItem item1 = new OrderItem();
        item1.setDishId(DISH_SIGNATURE_ID);
        item1.setDishName("招牌菜品");
        item1.setQuantity(3);
        item1.setPrice(58.0);
        item1.setSubtotal(174.0);
        items.add(item1);

        OrderItem item2 = new OrderItem();
        item2.setDishId(DISH_APPETIZER_ID);
        item2.setDishName("开胃小菜");
        item2.setQuantity(2);
        item2.setPrice(53.0);
        item2.setSubtotal(106.0);
        items.add(item2);

        return items;
    }

    private static List<OrderItem> buildOrderItemsForLargeOrder() {
        List<OrderItem> items = new ArrayList<>();
        
        OrderItem item1 = new OrderItem();
        item1.setDishId(DISH_SIGNATURE_ID);
        item1.setDishName("招牌菜品");
        item1.setQuantity(10);
        item1.setPrice(58.0);
        item1.setSubtotal(580.0);
        items.add(item1);

        OrderItem item2 = new OrderItem();
        item2.setDishId(DISH_APPETIZER_ID);
        item2.setDishName("开胃小菜");
        item2.setQuantity(5);
        item2.setPrice(53.0);
        item2.setSubtotal(265.0);
        items.add(item2);

        OrderItem item3 = new OrderItem();
        item3.setDishId(DISH_DRINK_ID);
        item3.setDishName("特调饮品");
        item3.setQuantity(1);
        item3.setPrice(5.0);
        item3.setSubtotal(5.0);
        items.add(item3);

        return items;
    }

    public static RestaurantTable buildAvailableTable(String tableId, String tableNumber) {
        RestaurantTable table = new RestaurantTable();
        table.setTableId(tableId);
        table.setTableNumber(tableNumber);
        table.setTableType("standard");
        table.setTableCapacity(4);
        table.setTableStatus("available");
        table.setLocation("大厅");
        return table;
    }

    public static RestaurantTable buildAvailableTableA01() {
        return buildAvailableTable(TABLE_A01_ID, "A01");
    }

    public static RestaurantTable buildAvailableTableA02() {
        return buildAvailableTable(TABLE_A02_ID, "A02");
    }

    public static RestaurantTable buildReservedTable() {
        RestaurantTable table = buildAvailableTable(TABLE_B01_ID, "B01");
        table.setTableStatus("reserved");
        table.setReserveTime(LocalDateTime.now().plusMinutes(30));
        table.setReservedBy("customer_test");
        return table;
    }

    public static RestaurantTable buildOccupiedTable() {
        RestaurantTable table = buildAvailableTable(TABLE_B02_ID, "B02");
        table.setTableStatus("occupied");
        table.setCurrentOrderId("order_occupied_001");
        return table;
    }

    public static RestaurantTable buildVipTable() {
        RestaurantTable table = new RestaurantTable();
        table.setTableId("table_vip_001");
        table.setTableNumber("V01");
        table.setTableType("vip");
        table.setTableCapacity(8);
        table.setTableStatus("available");
        table.setLocation("VIP区");
        return table;
    }

    public static Stock buildCriticalIngredientStock() {
        Stock stock = new Stock();
        stock.setStockId("stock_critical_001");
        stock.setIngredientId(INGREDIENT_CRITICAL_1);
        stock.setIngredientName("新鲜牛肉");
        stock.setCategory("肉类");
        stock.setStockQuantity(50.0);
        stock.setStockUnit("kg");
        stock.setWarningThreshold(WARNING_THRESHOLD);
        stock.setSupplier("优质供应商A");
        stock.setUnitPrice(85.0);
        stock.setCreatedAt(LocalDateTime.now().minusDays(7));
        stock.setUpdatedAt(LocalDateTime.now());
        return stock;
    }

    public static Stock buildCriticalSeafoodStock() {
        Stock stock = new Stock();
        stock.setStockId("stock_critical_002");
        stock.setIngredientId(INGREDIENT_CRITICAL_2);
        stock.setIngredientName("新鲜虾仁");
        stock.setCategory("海鲜");
        stock.setStockQuantity(30.0);
        stock.setStockUnit("kg");
        stock.setWarningThreshold(WARNING_THRESHOLD);
        stock.setSupplier("海鲜供应商B");
        stock.setUnitPrice(120.0);
        stock.setCreatedAt(LocalDateTime.now().minusDays(3));
        stock.setUpdatedAt(LocalDateTime.now());
        return stock;
    }

    public static Stock buildNormalIngredientStock() {
        Stock stock = new Stock();
        stock.setStockId("stock_normal_001");
        stock.setIngredientId(INGREDIENT_NORMAL_1);
        stock.setIngredientName("食用油");
        stock.setCategory("调料");
        stock.setStockQuantity(100.0);
        stock.setStockUnit("L");
        stock.setWarningThreshold(20.0);
        stock.setSupplier("调料供应商C");
        stock.setUnitPrice(15.0);
        stock.setCreatedAt(LocalDateTime.now().minusDays(14));
        stock.setUpdatedAt(LocalDateTime.now());
        return stock;
    }

    public static Stock buildVegetableStock() {
        Stock stock = new Stock();
        stock.setStockId("stock_normal_002");
        stock.setIngredientId(INGREDIENT_NORMAL_2);
        stock.setIngredientName("新鲜蔬菜");
        stock.setCategory("蔬菜");
        stock.setStockQuantity(80.0);
        stock.setStockUnit("kg");
        stock.setWarningThreshold(15.0);
        stock.setSupplier("农场直供");
        stock.setUnitPrice(8.0);
        stock.setCreatedAt(LocalDateTime.now().minusDays(1));
        stock.setUpdatedAt(LocalDateTime.now());
        return stock;
    }

    public static Stock buildLowStockIngredient() {
        Stock stock = buildCriticalIngredientStock();
        stock.setStockQuantity(8.0);
        stock.setUpdatedAt(LocalDateTime.now().minusHours(1));
        return stock;
    }

    public static Stock buildCriticalLowStockIngredient() {
        Stock stock = buildCriticalIngredientStock();
        stock.setStockQuantity(2.0);
        stock.setUpdatedAt(LocalDateTime.now().minusHours(2));
        return stock;
    }

    public static Stock buildMediumLowStockIngredient() {
        Stock stock = buildCriticalIngredientStock();
        stock.setStockQuantity(5.0);
        stock.setUpdatedAt(LocalDateTime.now().minusHours(1));
        return stock;
    }

    public static StockWarning buildHighLevelStockWarning() {
        StockWarning warning = new StockWarning();
        warning.setWarningId("warning_high_001");
        warning.setIngredientId(INGREDIENT_CRITICAL_1);
        warning.setIngredientName("新鲜牛肉");
        warning.setWarningType("low_stock");
        warning.setWarningLevel("high");
        warning.setCurrentQuantity(2.0);
        warning.setWarningThreshold(WARNING_THRESHOLD);
        warning.setTriggeredAt(LocalDateTime.now().minusHours(2));
        warning.setHandled(false);
        return warning;
    }

    public static StockWarning buildMediumLevelStockWarning() {
        StockWarning warning = new StockWarning();
        warning.setWarningId("warning_medium_001");
        warning.setIngredientId(INGREDIENT_CRITICAL_1);
        warning.setIngredientName("新鲜牛肉");
        warning.setWarningType("low_stock");
        warning.setWarningLevel("medium");
        warning.setCurrentQuantity(5.0);
        warning.setWarningThreshold(WARNING_THRESHOLD);
        warning.setTriggeredAt(LocalDateTime.now().minusHours(1));
        warning.setHandled(false);
        return warning;
    }

    public static Dish buildSignatureDish() {
        Dish dish = new Dish();
        dish.setDishId(DISH_SIGNATURE_ID);
        dish.setDishName("招牌菜品");
        dish.setDishType("signature");
        dish.setDishPrice(58.0);
        dish.setDishCategory("main");
        dish.setDishStatus("available");
        dish.setDescription("本店招牌，精选食材精心烹制");
        dish.setSpicyLevel(2);
        dish.setPreparationTime(20);
        dish.setIngredients(buildDishIngredientsForSignature());
        dish.setCreatedAt(LocalDateTime.now().minusDays(30));
        dish.setUpdatedAt(LocalDateTime.now());
        return dish;
    }

    public static Dish buildAppetizerDish() {
        Dish dish = new Dish();
        dish.setDishId(DISH_APPETIZER_ID);
        dish.setDishName("开胃小菜");
        dish.setDishType("regular");
        dish.setDishPrice(53.0);
        dish.setDishCategory("appetizer");
        dish.setDishStatus("available");
        dish.setDescription("开胃爽口，夏日必备");
        dish.setSpicyLevel(1);
        dish.setPreparationTime(10);
        dish.setCreatedAt(LocalDateTime.now().minusDays(20));
        dish.setUpdatedAt(LocalDateTime.now());
        return dish;
    }

    public static Dish buildDrinkDish() {
        Dish dish = new Dish();
        dish.setDishId(DISH_DRINK_ID);
        dish.setDishName("特调饮品");
        dish.setDishType("regular");
        dish.setDishPrice(5.0);
        dish.setDishCategory("drink");
        dish.setDishStatus("available");
        dish.setDescription("清热解暑");
        dish.setSpicyLevel(0);
        dish.setPreparationTime(5);
        dish.setCreatedAt(LocalDateTime.now().minusDays(10));
        dish.setUpdatedAt(LocalDateTime.now());
        return dish;
    }

    public static Dish buildSoldOutDish() {
        Dish dish = buildSignatureDish();
        dish.setDishStatus("sold_out");
        dish.setUpdatedAt(LocalDateTime.now());
        return dish;
    }

    public static Dish buildOfflineDish() {
        Dish dish = buildSignatureDish();
        dish.setDishStatus("offline");
        dish.setUpdatedAt(LocalDateTime.now());
        return dish;
    }

    private static List<DishIngredient> buildDishIngredientsForSignature() {
        List<DishIngredient> ingredients = new ArrayList<>();
        
        DishIngredient ing1 = new DishIngredient();
        ing1.setIngredientId(INGREDIENT_CRITICAL_1);
        ing1.setIngredientName("新鲜牛肉");
        ing1.setQuantity(0.3);
        ing1.setUnit("kg");
        ingredients.add(ing1);

        DishIngredient ing2 = new DishIngredient();
        ing2.setIngredientId(INGREDIENT_NORMAL_1);
        ing2.setIngredientName("食用油");
        ing2.setQuantity(0.05);
        ing2.setUnit("L");
        ingredients.add(ing2);

        DishIngredient ing3 = new DishIngredient();
        ing3.setIngredientId(INGREDIENT_NORMAL_2);
        ing3.setIngredientName("新鲜蔬菜");
        ing3.setQuantity(0.1);
        ing3.setUnit("kg");
        ingredients.add(ing3);

        return ingredients;
    }

    public static List<Dish> buildMultipleDishes() {
        return Arrays.asList(
            buildSignatureDish(),
            buildAppetizerDish(),
            buildDrinkDish()
        );
    }

    public static List<Stock> buildMultipleStocks() {
        return Arrays.asList(
            buildCriticalIngredientStock(),
            buildCriticalSeafoodStock(),
            buildNormalIngredientStock(),
            buildVegetableStock()
        );
    }

    public static List<RestaurantTable> buildMultipleTables() {
        return Arrays.asList(
            buildAvailableTableA01(),
            buildAvailableTableA02(),
            buildReservedTable(),
            buildOccupiedTable()
        );
    }

    public static Employee buildWaiterEmployee() {
        Employee employee = new Employee();
        employee.setEmployeeId("emp_waiter_001");
        employee.setName("张三");
        employee.setPosition("服务员");
        employee.setDepartment("前厅部");
        employee.setPhone("13800138001");
        employee.setEmail("zhangsan@restaurant.com");
        employee.setStatus("active");
        employee.setHireDate(LocalDateTime.now().minusYears(1));
        return employee;
    }

    public static Employee buildChefEmployee() {
        Employee employee = new Employee();
        employee.setEmployeeId("emp_chef_001");
        employee.setName("李四");
        employee.setPosition("主厨");
        employee.setDepartment("后厨部");
        employee.setPhone("13800138002");
        employee.setEmail("lisi@restaurant.com");
        employee.setStatus("active");
        employee.setHireDate(LocalDateTime.now().minusYears(3));
        return employee;
    }
}
