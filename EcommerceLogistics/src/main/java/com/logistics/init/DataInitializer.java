package com.logistics.init;

import com.logistics.service.DeliveryTypeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Profile("!test")
public class DataInitializer implements CommandLineRunner {

    private final DeliveryTypeService deliveryTypeService;

    @Override
    public void run(String... args) {
        log.info("开始初始化默认配送类型配置...");
        deliveryTypeService.initializeDefaultTypes();
        log.info("默认配送类型配置初始化完成");
    }
}
