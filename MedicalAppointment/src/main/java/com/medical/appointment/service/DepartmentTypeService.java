package com.medical.appointment.service;

import com.medical.appointment.config.DepartmentTypeConfig;
import com.medical.appointment.config.DepartmentTypeConfig.DepartmentTypeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class DepartmentTypeService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentTypeService.class);

    private final DepartmentTypeConfig config;

    public DepartmentTypeService(DepartmentTypeConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        log.info("初始化科室类型服务，共加载 {} 个科室类型", config.getAllTypes().size());
        config.getAllTypes().forEach(type -> 
            log.info("加载科室类型: {} - {}", type.getCode(), type.getName()));
    }

    public List<DepartmentTypeInfo> getAllEnabledTypes() {
        return config.getAllTypes().stream()
                .filter(DepartmentTypeInfo::isEnabled)
                .sorted(Comparator.comparingInt(DepartmentTypeInfo::getPriority))
                .collect(Collectors.toList());
    }

    public List<DepartmentTypeInfo> getAllTypes() {
        return config.getAllTypes().stream()
                .sorted(Comparator.comparingInt(DepartmentTypeInfo::getPriority))
                .collect(Collectors.toList());
    }

    public Optional<DepartmentTypeInfo> getTypeByCode(String code) {
        return Optional.ofNullable(config.getTypeInfo(code));
    }

    public String getTypeNameByCode(String code) {
        DepartmentTypeInfo info = config.getTypeInfo(code);
        return info != null ? info.getName() : code;
    }

    public boolean isTypeEnabled(String code) {
        DepartmentTypeInfo info = config.getTypeInfo(code);
        return info != null && info.isEnabled();
    }

    public boolean isValidTypeCode(String code) {
        return config.isValidType(code);
    }

    public Map<String, String> getTypeCodeToNameMap() {
        return config.getAllTypes().stream()
                .collect(Collectors.toMap(
                        DepartmentTypeInfo::getCode,
                        DepartmentTypeInfo::getName
                ));
    }

    public List<String> getEnabledTypeCodes() {
        return getAllEnabledTypes().stream()
                .map(DepartmentTypeInfo::getCode)
                .collect(Collectors.toList());
    }

    public void validateTypeCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("科室类型代码不能为空");
        }
        if (!isValidTypeCode(code)) {
            throw new IllegalArgumentException("无效的科室类型: " + code + "，有效类型: " + 
                    String.join(", ", config.getAllTypeCodes()));
        }
        if (!isTypeEnabled(code)) {
            throw new IllegalArgumentException("科室类型已禁用: " + code);
        }
    }
}
