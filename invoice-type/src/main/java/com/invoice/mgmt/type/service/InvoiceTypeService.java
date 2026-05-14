package com.invoice.mgmt.type.service;

import com.invoice.mgmt.common.entity.InvoiceType;
import com.invoice.mgmt.common.exception.InvoiceException;
import com.invoice.mgmt.common.util.DateTimeUtil;
import com.invoice.mgmt.common.util.IdGenerator;
import com.invoice.mgmt.type.config.InvoiceTypeProperties;
import com.invoice.mgmt.type.mapper.InvoiceTypeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class InvoiceTypeService {
    private static final Logger logger = LoggerFactory.getLogger(InvoiceTypeService.class);

    @Autowired
    private InvoiceTypeMapper invoiceTypeMapper;

    @Autowired
    private InvoiceTypeProperties typeProperties;

    private final Map<String, InvoiceType> typeCache = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    @PostConstruct
    public void init() {
        logger.info("发票类型服务初始化中...");
        if (typeProperties.isEnableConfig()) {
            syncConfigTypes();
        }
        loadTypesToCache();
        initialized = true;
        logger.info("发票类型服务初始化完成, 配置驱动: {}, 缓存类型数: {}",
                typeProperties.isEnableConfig(), typeCache.size());
    }

    private void syncConfigTypes() {
        if (!typeProperties.isAutoSyncToDb() || typeProperties.getConfigs().isEmpty()) {
            return;
        }

        logger.info("开始同步配置文件中的发票类型到数据库...");
        for (InvoiceTypeProperties.InvoiceTypeConfig config : typeProperties.getConfigs()) {
            syncSingleType(config);
        }
        logger.info("发票类型配置同步完成, 同步了 {} 个类型", typeProperties.getConfigs().size());
    }

    private void syncSingleType(InvoiceTypeProperties.InvoiceTypeConfig config) {
        try {
            InvoiceType existing = invoiceTypeMapper.findByCode(config.getCode());
            if (existing == null) {
                InvoiceType newType = InvoiceType.builder()
                        .typeId(IdGenerator.generateTypeId())
                        .typeCode(config.getCode())
                        .typeName(config.getName())
                        .taxRate(config.getTaxRate())
                        .enabled(config.isEnabled())
                        .description(config.getDescription())
                        .createdAt(DateTimeUtil.now())
                        .updatedAt(DateTimeUtil.now())
                        .build();
                invoiceTypeMapper.insert(newType);
                logger.info("新增发票类型: code={}, name={}", config.getCode(), config.getName());
            } else {
                boolean changed = false;
                if (!existing.getTypeName().equals(config.getName())) {
                    existing.setTypeName(config.getName());
                    changed = true;
                }
                if (existing.getTaxRate().compareTo(config.getTaxRate()) != 0) {
                    existing.setTaxRate(config.getTaxRate());
                    changed = true;
                }
                if (existing.getEnabled() != config.isEnabled()) {
                    existing.setEnabled(config.isEnabled());
                    changed = true;
                }
                if (config.getDescription() != null && !config.getDescription().equals(existing.getDescription())) {
                    existing.setDescription(config.getDescription());
                    changed = true;
                }
                if (changed) {
                    existing.setUpdatedAt(DateTimeUtil.now());
                    invoiceTypeMapper.update(existing);
                    logger.info("更新发票类型: code={}, name={}", config.getCode(), config.getName());
                }
            }
        } catch (Exception e) {
            logger.error("同步发票类型失败: code={}", config.getCode(), e);
        }
    }

    private void loadTypesToCache() {
        typeCache.clear();
        List<InvoiceType> allTypes = invoiceTypeMapper.findAll();
        for (InvoiceType type : allTypes) {
            typeCache.put(type.getTypeCode(), type);
        }
    }

    public void refreshCache() {
        loadTypesToCache();
        logger.info("发票类型缓存已刷新, 缓存类型数: {}", typeCache.size());
    }

    @Transactional
    public InvoiceType create(String typeCode, String typeName, BigDecimal taxRate, String description) {
        if (invoiceTypeMapper.findByCode(typeCode) != null) {
            throw new InvoiceException(400, "发票类型编码已存在: " + typeCode);
        }
        validateTaxRate(taxRate);

        InvoiceType invoiceType = InvoiceType.builder()
                .typeId(IdGenerator.generateTypeId())
                .typeCode(typeCode)
                .typeName(typeName)
                .taxRate(taxRate)
                .enabled(true)
                .description(description)
                .createdAt(DateTimeUtil.now())
                .updatedAt(DateTimeUtil.now())
                .build();
        invoiceTypeMapper.insert(invoiceType);

        typeCache.put(typeCode, invoiceType);
        logger.info("创建发票类型: code={}, name={}", typeCode, typeName);
        return invoiceType;
    }

    @Transactional
    public InvoiceType update(String typeId, String typeName, BigDecimal taxRate, String description, Boolean enabled) {
        InvoiceType existing = invoiceTypeMapper.findById(typeId);
        if (existing == null) {
            throw InvoiceException.invalidType();
        }
        if (typeName != null) existing.setTypeName(typeName);
        if (taxRate != null) {
            validateTaxRate(taxRate);
            existing.setTaxRate(taxRate);
        }
        if (description != null) existing.setDescription(description);
        if (enabled != null) existing.setEnabled(enabled);
        existing.setUpdatedAt(DateTimeUtil.now());
        invoiceTypeMapper.update(existing);

        typeCache.put(existing.getTypeCode(), existing);
        logger.info("更新发票类型: code={}", existing.getTypeCode());
        return existing;
    }

    @Transactional
    public void delete(String typeId) {
        InvoiceType existing = invoiceTypeMapper.findById(typeId);
        if (existing == null) {
            throw InvoiceException.invalidType();
        }
        typeCache.remove(existing.getTypeCode());
        invoiceTypeMapper.deleteById(typeId);
        logger.info("删除发票类型: code={}", existing.getTypeCode());
    }

    private void validateTaxRate(BigDecimal taxRate) {
        if (taxRate == null || taxRate.compareTo(BigDecimal.ZERO) < 0 || taxRate.compareTo(BigDecimal.ONE) > 0) {
            throw new InvoiceException(400, "税率必须在0-1之间");
        }
    }

    public InvoiceType getById(String typeId) {
        InvoiceType type = invoiceTypeMapper.findById(typeId);
        if (type == null) {
            throw InvoiceException.invalidType();
        }
        return type;
    }

    public InvoiceType getByCode(String typeCode) {
        if (initialized) {
            InvoiceType type = typeCache.get(typeCode);
            if (type != null) {
                return type;
            }
        }
        InvoiceType type = invoiceTypeMapper.findByCode(typeCode);
        if (type == null) {
            throw InvoiceException.invalidType();
        }
        if (initialized) {
            typeCache.put(typeCode, type);
        }
        return type;
    }

    public List<InvoiceType> getAll() {
        if (initialized && !typeCache.isEmpty()) {
            return typeCache.values().stream()
                    .sorted((a, b) -> a.getTypeCode().compareTo(b.getTypeCode()))
                    .collect(Collectors.toList());
        }
        return invoiceTypeMapper.findAll();
    }

    public List<InvoiceType> getEnabled() {
        if (initialized && !typeCache.isEmpty()) {
            return typeCache.values().stream()
                    .filter(InvoiceType::getEnabled)
                    .sorted((a, b) -> a.getTypeCode().compareTo(b.getTypeCode()))
                    .collect(Collectors.toList());
        }
        return invoiceTypeMapper.findByEnabled(true);
    }

    public boolean isValidType(String typeCode) {
        if (typeCode == null || typeCode.trim().isEmpty()) {
            return false;
        }
        if (initialized) {
            InvoiceType type = typeCache.get(typeCode);
            if (type != null) {
                return Boolean.TRUE.equals(type.getEnabled());
            }
        }
        InvoiceType type = invoiceTypeMapper.findByCode(typeCode);
        if (type != null && initialized) {
            typeCache.put(typeCode, type);
        }
        return type != null && Boolean.TRUE.equals(type.getEnabled());
    }

    public BigDecimal getTaxRate(String typeCode) {
        InvoiceType type = getByCode(typeCode);
        return type.getTaxRate();
    }

    public String getTypeName(String typeCode) {
        InvoiceType type = getByCode(typeCode);
        return type.getTypeName();
    }

    public int getCacheSize() {
        return typeCache.size();
    }

    public boolean isInitialized() {
        return initialized;
    }
}
