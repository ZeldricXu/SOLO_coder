package com.assetinventory.config;

import com.assetinventory.entity.AssetCategory;
import com.assetinventory.entity.InventoryPerson;
import com.assetinventory.entity.InventoryPlan;
import com.assetinventory.service.AssetService;
import com.assetinventory.service.CategoryService;
import com.assetinventory.service.PersonService;
import com.assetinventory.service.PlanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class DataInitializer implements CommandLineRunner {

    private final CategoryService categoryService;
    private final PersonService personService;
    private final PlanService planService;
    private final AssetService assetService;

    @Autowired
    public DataInitializer(CategoryService categoryService,
                           PersonService personService,
                           PlanService planService,
                           AssetService assetService) {
        this.categoryService = categoryService;
        this.personService = personService;
        this.planService = planService;
        this.assetService = assetService;
    }

    @Override
    public void run(String... args) {
        initCategories();
        initPersons();
        initPlans();
        initAssets();
    }

    private void initCategories() {
        if (categoryService.getAllCategories().isEmpty()) {
            categoryService.createCategory("equipment", "设备类", "电子设备、办公设备等");
            categoryService.createCategory("furniture", "家具类", "办公家具、储物柜等");
            categoryService.createCategory("software", "软件类", "软件许可、授权等");
        }
    }

    private void initPersons() {
        if (personService.getAllPersons().isEmpty()) {
            personService.createPerson("张三", "资产管理部");
            personService.createPerson("李四", "财务部");
            personService.createPerson("王五", "IT部门");
        }
    }

    private void initPlans() {
        if (planService.getAllPlans().isEmpty()) {
            planService.createPlan(
                    "2026年5月月度盘点",
                    "全公司范围",
                    LocalDate.now(),
                    LocalDate.now().plusDays(5)
            );
        }
    }

    private void initAssets() {
        if (assetService.getAllAssets().isEmpty()) {
            assetService.createAsset("办公电脑", "equipment", 100, "A栋办公楼", 500000.0);
            assetService.createAsset("办公桌椅", "furniture", 200, "B栋办公楼", 200000.0);
            assetService.createAsset("办公软件许可", "software", 150, "总部机房", 150000.0);
        }
    }
}
