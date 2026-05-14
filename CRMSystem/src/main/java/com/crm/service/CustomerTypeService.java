package com.crm.service;

import com.crm.config.CustomerTypeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CustomerTypeService {

    @Autowired
    private CustomerTypeProperties typeProperties;

    public List<CustomerTypeProperties.CustomerType> getAllTypes() {
        return typeProperties.getTypes().stream()
                .sorted(Comparator.comparingInt(CustomerTypeProperties.CustomerType::getPriority))
                .collect(Collectors.toList());
    }

    public List<CustomerTypeProperties.CustomerType> getEnabledTypes() {
        return typeProperties.getTypes().stream()
                .filter(CustomerTypeProperties.CustomerType::isEnabled)
                .sorted(Comparator.comparingInt(CustomerTypeProperties.CustomerType::getPriority))
                .collect(Collectors.toList());
    }

    public Optional<CustomerTypeProperties.CustomerType> getTypeByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return typeProperties.getTypes().stream()
                .filter(type -> code.equalsIgnoreCase(type.getCode()))
                .findFirst();
    }

    public boolean isValidType(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        return typeProperties.getTypes().stream()
                .anyMatch(type -> type.isEnabled() && code.equalsIgnoreCase(type.getCode()));
    }

    public String getTypeName(String code) {
        return getTypeByCode(code)
                .map(CustomerTypeProperties.CustomerType::getName)
                .orElse(code);
    }

    public String getDefaultTypeCode() {
        return typeProperties.getTypes().stream()
                .filter(CustomerTypeProperties.CustomerType::isEnabled)
                .sorted(Comparator.comparingInt(CustomerTypeProperties.CustomerType::getPriority))
                .map(CustomerTypeProperties.CustomerType::getCode)
                .findFirst()
                .orElse("INDIVIDUAL");
    }

    public List<String> getAllTypeCodes() {
        return typeProperties.getTypes().stream()
                .map(CustomerTypeProperties.CustomerType::getCode)
                .collect(Collectors.toList());
    }

    public List<String> getEnabledTypeCodes() {
        return typeProperties.getTypes().stream()
                .filter(CustomerTypeProperties.CustomerType::isEnabled)
                .map(CustomerTypeProperties.CustomerType::getCode)
                .collect(Collectors.toList());
    }

    public boolean isHighValueType(String code) {
        if (code == null) {
            return false;
        }
        String upperCode = code.toUpperCase();
        return "VIP".equals(upperCode) || "ENTERPRISE".equals(upperCode) || "GOVERNMENT".equals(upperCode);
    }

    public boolean isMediumValueType(String code) {
        if (code == null) {
            return false;
        }
        String upperCode = code.toUpperCase();
        return "PARTNER".equals(upperCode);
    }
}
