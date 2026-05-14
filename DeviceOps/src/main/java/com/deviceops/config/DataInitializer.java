package com.deviceops.config;

import com.deviceops.entity.Operator;
import com.deviceops.service.operator.OperatorService;
import com.deviceops.service.type.DeviceTypeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    @Autowired
    private DeviceTypeService deviceTypeService;

    @Autowired
    private OperatorService operatorService;

    @Override
    public void run(String... args) {
        initializeDeviceTypes();
        initializeOperators();
    }

    private void initializeDeviceTypes() {
        deviceTypeService.initializeDefaultTypes();
    }

    private void initializeOperators() {
        if (operatorService.count() == 0) {
            operatorService.createOperator("张三", "hardware");
            operatorService.createOperator("李四", "software");
            operatorService.createOperator("王五", "network");
            operatorService.createOperator("赵六", "hardware");
            operatorService.createOperator("钱七", "software");
        }
    }
}
