package com.restaurant.mgmt.config;

import com.restaurant.mgmt.model.*;
import com.restaurant.mgmt.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class DataInitializer implements CommandLineRunner {

    @Autowired
    private DishService dishService;

    @Autowired
    private TableService tableService;

    @Autowired
    private StockService stockService;

    @Autowired
    private EmployeeService employeeService;

    @Override
    public void run(String... args) {
        initStocks();
        initDishes();
        initTables();
        initEmployees();
        System.out.println("RestaurantMgmt 数据初始化完成");
    }

    private void initStocks() {
        String[] ingredients = {
            "ingredient_001,牛肉,kg,100,20,肉类",
            "ingredient_002,鸡肉,kg,80,15,肉类",
            "ingredient_003,猪肉,kg,60,10,肉类",
            "ingredient_004,大米,kg,200,50,主食",
            "ingredient_005,面粉,kg,150,30,主食",
            "ingredient_006,鸡蛋,个,500,100,蛋类",
            "ingredient_007,青菜,kg,50,10,蔬菜",
            "ingredient_008,西红柿,kg,40,8,蔬菜",
            "ingredient_009,土豆,kg,60,15,蔬菜",
            "ingredient_010,食用油,L,30,10,调料"
        };

        for (String item : ingredients) {
            String[] parts = item.split(",");
            Stock stock = new Stock();
            stock.setIngredientId(parts[0]);
            stock.setIngredientName(parts[1]);
            stock.setStockUnit(parts[2]);
            stock.setStockQuantity(Double.parseDouble(parts[3]));
            stock.setWarningThreshold(Double.parseDouble(parts[4]));
            stock.setCategory(parts[5]);
            try {
                stockService.createStock(stock);
            } catch (Exception ignored) {
            }
        }
    }

    private void initDishes() {
        List<DishIngredient> kungPaoIngredients = Arrays.asList(
            new DishIngredient("ingredient_001", "牛肉", 0.3, "kg"),
            new DishIngredient("ingredient_008", "西红柿", 0.1, "kg")
        );

        Dish dish1 = new Dish();
        dish1.setDishName("宫保鸡丁");
        dish1.setDishType("signature");
        dish1.setDishPrice(58.0);
        dish1.setDishCategory("main");
        dish1.setDishStatus("available");
        dish1.setDescription("经典川菜，鸡肉嫩滑，花生酥脆");
        dish1.setIngredients(Arrays.asList(
            new DishIngredient("ingredient_002", "鸡肉", 0.3, "kg"),
            new DishIngredient("ingredient_006", "鸡蛋", 1.0, "个")
        ));
        try {
            dishService.createDish(dish1);
        } catch (Exception ignored) {
        }

        Dish dish2 = new Dish();
        dish2.setDishName("红烧牛肉");
        dish2.setDishType("signature");
        dish2.setDishPrice(68.0);
        dish2.setDishCategory("main");
        dish2.setDishStatus("available");
        dish2.setDescription("精选牛腩，慢火炖煮，入口即化");
        dish2.setIngredients(kungPaoIngredients);
        try {
            dishService.createDish(dish2);
        } catch (Exception ignored) {
        }

        Dish dish3 = new Dish();
        dish3.setDishName("蛋炒饭");
        dish3.setDishType("regular");
        dish3.setDishPrice(28.0);
        dish3.setDishCategory("staple");
        dish3.setDishStatus("available");
        dish3.setDescription("简单美味，粒粒分明");
        dish3.setIngredients(Arrays.asList(
            new DishIngredient("ingredient_004", "大米", 0.2, "kg"),
            new DishIngredient("ingredient_006", "鸡蛋", 2.0, "个")
        ));
        try {
            dishService.createDish(dish3);
        } catch (Exception ignored) {
        }

        Dish dish4 = new Dish();
        dish4.setDishName("炒时蔬");
        dish4.setDishType("regular");
        dish4.setDishPrice(22.0);
        dish4.setDishCategory("vegetable");
        dish4.setDishStatus("available");
        dish4.setDescription("新鲜时令蔬菜，清爽可口");
        dish4.setIngredients(Arrays.asList(
            new DishIngredient("ingredient_007", "青菜", 0.3, "kg")
        ));
        try {
            dishService.createDish(dish4);
        } catch (Exception ignored) {
        }

        Dish dish5 = new Dish();
        dish5.setDishName("番茄蛋汤");
        dish5.setDishType("regular");
        dish5.setDishPrice(18.0);
        dish5.setDishCategory("soup");
        dish5.setDishStatus("available");
        dish5.setDescription("家常美味，营养丰富");
        dish5.setIngredients(Arrays.asList(
            new DishIngredient("ingredient_008", "西红柿", 0.2, "kg"),
            new DishIngredient("ingredient_006", "鸡蛋", 2.0, "个")
        ));
        try {
            dishService.createDish(dish5);
        } catch (Exception ignored) {
        }
    }

    private void initTables() {
        String[][] tables = {
            {"A01", "standard", "4"},
            {"A02", "standard", "4"},
            {"A03", "standard", "4"},
            {"B01", "large", "6"},
            {"B02", "large", "8"},
            {"C01", "vip", "10"},
            {"D01", "small", "2"},
            {"D02", "small", "2"}
        };

        for (String[] t : tables) {
            RestaurantTable table = new RestaurantTable();
            table.setTableNumber(t[0]);
            table.setTableType(t[1]);
            table.setTableCapacity(Integer.parseInt(t[2]));
            table.setTableStatus("available");
            try {
                tableService.createTable(table);
            } catch (Exception ignored) {
            }
        }
    }

    private void initEmployees() {
        String[][] employees = {
            {"张三", "manager", "管理部"},
            {"李四", "chef", "厨房部"},
            {"王五", "chef", "厨房部"},
            {"赵六", "waiter", "服务部"},
            {"钱七", "waiter", "服务部"},
            {"孙八", "cashier", "财务部"}
        };

        for (String[] e : employees) {
            Employee emp = new Employee();
            emp.setEmployeeName(e[0]);
            emp.setPosition(e[1]);
            emp.setDepartment(e[2]);
            emp.setStatus("active");
            try {
                employeeService.createEmployee(emp);
            } catch (Exception ignored) {
            }
        }
    }
}
