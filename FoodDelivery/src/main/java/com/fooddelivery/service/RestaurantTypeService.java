package com.fooddelivery.service;

import com.fooddelivery.config.RestaurantTypeConfigProperties;
import com.fooddelivery.config.RestaurantTypeConfigProperties.RestaurantTypeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class RestaurantTypeService {

    @Autowired
    private RestaurantTypeConfigProperties typeConfig;

    @PostConstruct
    public void init() {
        log.info("餐厅类型服务已初始化，当前配置类型数量: {}", typeConfig.getTypes().size());
        log.info("启用的餐厅类型: {}", typeConfig.getAllEnabledTypes().stream()
                .map(RestaurantTypeConfig::getCode)
                .toList());
    }

    public List<RestaurantTypeConfig> getAllTypes() {
        return new ArrayList<>(typeConfig.getTypes().values());
    }

    public List<RestaurantTypeConfig> getAllEnabledTypes() {
        return typeConfig.getAllEnabledTypes();
    }

    public RestaurantTypeConfig getType(String typeCode) {
        return typeConfig.getTypeConfig(typeCode);
    }

    public boolean isValidType(String typeCode) {
        return typeConfig.isValidType(typeCode);
    }

    public String getTypeName(String typeCode) {
        return typeConfig.getTypeName(typeCode);
    }

    public List<String> getAllTypeCodes() {
        return typeConfig.getAllTypeCodes();
    }

    public List<String> getEnabledTypeCodes() {
        return getAllEnabledTypes().stream()
                .map(RestaurantTypeConfig::getCode)
                .toList();
    }

    public RestaurantTypeConfig getDefaultType() {
        return typeConfig.getTypeConfig(typeConfig.getDefaultType());
    }

    public String normalizeType(String typeCode) {
        if (typeCode == null || typeCode.isEmpty()) {
            return typeConfig.getDefaultType();
        }
        if (isValidType(typeCode)) {
            return typeCode;
        }
        return typeConfig.getDefaultType();
    }
}
