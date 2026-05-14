package com.cms.service;

import com.cms.entity.ContentTypeConfig;
import com.cms.exception.BusinessException;
import com.cms.repository.ContentTypeConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ContentTypeConfigService {

    private static final Logger logger = LoggerFactory.getLogger(ContentTypeConfigService.class);

    @Autowired
    private ContentTypeConfigRepository contentTypeConfigRepository;

    @Transactional
    @CacheEvict(value = "contentTypes", allEntries = true)
    public ContentTypeConfig createConfig(ContentTypeConfig config) {
        if (contentTypeConfigRepository.existsByTypeCode(config.getTypeCode())) {
            throw new BusinessException(400, "内容类型代码已存在: " + config.getTypeCode());
        }

        validateConfig(config);

        config.setTypeId("type_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));

        ContentTypeConfig saved = contentTypeConfigRepository.save(config);

        logger.info("创建内容类型配置: typeCode={}, typeName={}", config.getTypeCode(), config.getTypeName());

        return saved;
    }

    @Transactional
    @CacheEvict(value = "contentTypes", allEntries = true)
    public ContentTypeConfig updateConfig(String typeCode, ContentTypeConfig config) {
        ContentTypeConfig existing = contentTypeConfigRepository.findByTypeCode(typeCode)
            .orElseThrow(() -> new BusinessException(404, "内容类型配置不存在: " + typeCode));

        if (config.getTypeName() != null) {
            existing.setTypeName(config.getTypeName());
        }
        if (config.getTypeDescription() != null) {
            existing.setTypeDescription(config.getTypeDescription());
        }
        if (config.getDefaultTemplateId() != null) {
            existing.setDefaultTemplateId(config.getDefaultTemplateId());
        }
        if (config.getDefaultCategory() != null) {
            existing.setDefaultCategory(config.getDefaultCategory());
        }
        if (config.getAllowedTags() != null) {
            existing.setAllowedTags(config.getAllowedTags());
        }
        if (config.getReviewRequired() != null) {
            existing.setReviewRequired(config.getReviewRequired());
        }
        if (config.getPublishApprovalRequired() != null) {
            existing.setPublishApprovalRequired(config.getPublishApprovalRequired());
        }
        if (config.getDefaultUrgencyLevel() != null) {
            existing.setDefaultUrgencyLevel(config.getDefaultUrgencyLevel());
        }
        if (config.getDefaultImportanceLevel() != null) {
            existing.setDefaultImportanceLevel(config.getDefaultImportanceLevel());
        }
        if (config.getReviewFrequencyMinutes() != null) {
            existing.setReviewFrequencyMinutes(config.getReviewFrequencyMinutes());
        }
        if (config.getWarningOffsetMinutes() != null) {
            existing.setWarningOffsetMinutes(config.getWarningOffsetMinutes());
        }
        if (config.getMaxTitleLength() != null) {
            existing.setMaxTitleLength(config.getMaxTitleLength());
        }
        if (config.getMaxBodyLength() != null) {
            existing.setMaxBodyLength(config.getMaxBodyLength());
        }
        if (config.getSortOrder() != null) {
            existing.setSortOrder(config.getSortOrder());
        }

        validateConfig(existing);

        ContentTypeConfig saved = contentTypeConfigRepository.save(existing);

        logger.info("更新内容类型配置: typeCode={}", typeCode);

        return saved;
    }

    @Transactional
    @CacheEvict(value = "contentTypes", allEntries = true)
    public void deleteConfig(String typeCode) {
        if (!contentTypeConfigRepository.existsByTypeCode(typeCode)) {
            throw new BusinessException(404, "内容类型配置不存在: " + typeCode);
        }

        contentTypeConfigRepository.deleteByTypeCode(typeCode);

        logger.info("删除内容类型配置: typeCode={}", typeCode);
    }

    @Transactional
    @CacheEvict(value = "contentTypes", allEntries = true)
    public ContentTypeConfig activateConfig(String typeCode) {
        ContentTypeConfig config = contentTypeConfigRepository.findByTypeCode(typeCode)
            .orElseThrow(() -> new BusinessException(404, "内容类型配置不存在: " + typeCode));

        config.setActive(true);

        logger.info("激活内容类型配置: typeCode={}", typeCode);

        return contentTypeConfigRepository.save(config);
    }

    @Transactional
    @CacheEvict(value = "contentTypes", allEntries = true)
    public ContentTypeConfig deactivateConfig(String typeCode) {
        ContentTypeConfig config = contentTypeConfigRepository.findByTypeCode(typeCode)
            .orElseThrow(() -> new BusinessException(404, "内容类型配置不存在: " + typeCode));

        config.setActive(false);

        logger.info("停用内容类型配置: typeCode={}", typeCode);

        return contentTypeConfigRepository.save(config);
    }

    @Cacheable(value = "contentTypes", key = "'all_active'")
    public List<ContentTypeConfig> getAllActiveConfigs() {
        return contentTypeConfigRepository.findByIsActiveTrueOrderBySortOrderAsc();
    }

    @Cacheable(value = "contentTypes", key = "'all'")
    public List<ContentTypeConfig> getAllConfigs() {
        return contentTypeConfigRepository.findAllByOrderBySortOrderAsc();
    }

    @Cacheable(value = "contentTypes", key = "#typeCode")
    public ContentTypeConfig getConfigByCode(String typeCode) {
        return contentTypeConfigRepository.findByTypeCode(typeCode)
            .orElseThrow(() -> new BusinessException(404, "内容类型配置不存在: " + typeCode));
    }

    public ContentTypeConfig getActiveConfigByCode(String typeCode) {
        Optional<ContentTypeConfig> configOpt = contentTypeConfigRepository
            .findByTypeCodeAndIsActiveTrue(typeCode);

        return configOpt.orElse(null);
    }

    public boolean isContentTypeValid(String typeCode) {
        if (typeCode == null || typeCode.trim().isEmpty()) {
            return true;
        }

        return contentTypeConfigRepository.existsByTypeCode(typeCode);
    }

    public String getDefaultTemplateId(String typeCode) {
        if (typeCode == null) {
            return null;
        }

        ContentTypeConfig config = getActiveConfigByCode(typeCode);
        return config != null ? config.getDefaultTemplateId() : null;
    }

    public String getDefaultCategory(String typeCode) {
        if (typeCode == null) {
            return null;
        }

        ContentTypeConfig config = getActiveConfigByCode(typeCode);
        return config != null ? config.getDefaultCategory() : null;
    }

    public Integer getMaxTitleLength(String typeCode) {
        if (typeCode == null) {
            return 200;
        }

        ContentTypeConfig config = getActiveConfigByCode(typeCode);
        return config != null && config.getMaxTitleLength() != null 
            ? config.getMaxTitleLength() : 200;
    }

    public boolean isReviewRequired(String typeCode) {
        if (typeCode == null) {
            return true;
        }

        ContentTypeConfig config = getActiveConfigByCode(typeCode);
        return config == null || Boolean.TRUE.equals(config.getReviewRequired());
    }

    public boolean isPublishApprovalRequired(String typeCode) {
        if (typeCode == null) {
            return true;
        }

        ContentTypeConfig config = getActiveConfigByCode(typeCode);
        return config == null || Boolean.TRUE.equals(config.getPublishApprovalRequired());
    }

    @Cacheable(value = "contentTypes", key = "'type_codes'")
    public List<String> getActiveTypeCodes() {
        return contentTypeConfigRepository.findActiveTypeCodes();
    }

    public List<ContentTypeConfig> getConfigsByUrgencyLevel(String urgencyLevel) {
        return contentTypeConfigRepository.findByDefaultUrgencyLevel(urgencyLevel);
    }

    public List<ContentTypeConfig> getConfigsByImportanceLevel(String importanceLevel) {
        return contentTypeConfigRepository.findByDefaultImportanceLevel(importanceLevel);
    }

    private void validateConfig(ContentTypeConfig config) {
        if (config.getTypeCode() == null || config.getTypeCode().trim().isEmpty()) {
            throw new BusinessException(400, "内容类型代码不能为空");
        }

        if (config.getTypeName() == null || config.getTypeName().trim().isEmpty()) {
            throw new BusinessException(400, "内容类型名称不能为空");
        }

        if (config.getMaxTitleLength() != null && config.getMaxTitleLength() <= 0) {
            throw new BusinessException(400, "标题最大长度必须大于0");
        }

        if (config.getMaxBodyLength() != null && config.getMaxBodyLength() <= 0) {
            throw new BusinessException(400, "正文最大长度必须大于0");
        }

        if (config.getReviewFrequencyMinutes() != null && config.getReviewFrequencyMinutes() <= 0) {
            throw new BusinessException(400, "审核提醒频率必须大于0");
        }

        if (config.getWarningOffsetMinutes() != null && config.getWarningOffsetMinutes() <= 0) {
            throw new BusinessException(400, "发布预警偏移时间必须大于0");
        }
    }
}
