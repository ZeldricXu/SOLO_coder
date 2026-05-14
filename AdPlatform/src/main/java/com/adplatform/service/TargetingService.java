package com.adplatform.service;

import com.adplatform.config.AdPlatformConfig;
import com.adplatform.entity.AdHistory;
import com.adplatform.entity.AdTarget;
import com.adplatform.exception.BusinessException;
import com.adplatform.repository.AdHistoryRepository;
import com.adplatform.repository.AdInfoRepository;
import com.adplatform.repository.AdTargetRepository;
import com.adplatform.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class TargetingService {
    private static final Logger logger = LoggerFactory.getLogger(TargetingService.class);
    
    private final AdTargetRepository adTargetRepository;
    private final AdInfoRepository adInfoRepository;
    private final AdHistoryRepository adHistoryRepository;
    private final AdPlatformConfig config;

    public TargetingService(AdTargetRepository adTargetRepository,
                           AdInfoRepository adInfoRepository,
                           AdHistoryRepository adHistoryRepository,
                           AdPlatformConfig config) {
        this.adTargetRepository = adTargetRepository;
        this.adInfoRepository = adInfoRepository;
        this.adHistoryRepository = adHistoryRepository;
        this.config = config;
    }

    @Transactional
    public AdTarget createTargeting(String adId, String targetType, Map<String, Object> targetConditions) {
        if (!adInfoRepository.existsById(adId)) {
            throw new BusinessException(404, "广告不存在");
        }

        if (targetType == null || targetType.isEmpty()) {
            throw new BusinessException(400, "定向类型不能为空");
        }

        if (targetConditions == null || targetConditions.isEmpty()) {
            throw new BusinessException(400, "定向条件不能为空");
        }

        validateTargetConditions(targetType, targetConditions);

        AdTarget adTarget = AdTarget.builder()
                .targetId(IdGenerator.generateId("target"))
                .adId(adId)
                .targetType(targetType)
                .targetConditions(targetConditions)
                .build();
        
        adTargetRepository.save(adTarget);
        logger.info("定向配置创建成功: adId={}, targetId={}, type={}", 
                adId, adTarget.getTargetId(), targetType);

        recordHistory(adId, adTarget);
        return adTarget;
    }

    public Optional<AdTarget> getTargetingByAdId(String adId) {
        return adTargetRepository.findTopByAdIdOrderByCreatedAtDesc(adId);
    }

    public List<AdTarget> getAllTargetingByAdId(String adId) {
        return adTargetRepository.findByAdId(adId);
    }

    public Set<String> getSupportedTargetTypes() {
        return config.getTargeting().getTypes().keySet();
    }

    public AdPlatformConfig.TargetTypeConfig getTargetTypeConfig(String targetType) {
        return config.getTargeting().getTypes().get(targetType);
    }

    private void validateTargetConditions(String targetType, Map<String, Object> conditions) {
        Map<String, AdPlatformConfig.TargetTypeConfig> typeConfigs = config.getTargeting().getTypes();
        AdPlatformConfig.TargetTypeConfig typeConfig = typeConfigs.get(targetType);
        
        if (typeConfig == null) {
            throw new BusinessException(400, "不支持的定向类型: " + targetType + 
                    "。支持的类型: " + typeConfigs.keySet());
        }

        validateRequiredFields(targetType, conditions, typeConfig);
        validateOptionalFields(conditions, typeConfig);
    }

    private void validateRequiredFields(String targetType, 
                                        Map<String, Object> conditions,
                                        AdPlatformConfig.TargetTypeConfig typeConfig) {
        List<String> requiredFields = typeConfig.getRequiredFields();
        if (requiredFields == null || requiredFields.isEmpty()) {
            return;
        }

        for (String field : requiredFields) {
            if (!conditions.containsKey(field)) {
                throw new BusinessException(400, 
                        "定向类型[" + targetType + "]缺少必需参数: " + field +
                        "。必需参数列表: " + requiredFields);
            }
            
            Object value = conditions.get(field);
            if (value == null) {
                throw new BusinessException(400, 
                        "定向类型[" + targetType + "]的参数[" + field + "]不能为null");
            }
            
            validateFieldValue(field, value);
        }
    }

    private void validateOptionalFields(Map<String, Object> conditions,
                                        AdPlatformConfig.TargetTypeConfig typeConfig) {
        List<String> optionalFields = typeConfig.getOptionalFields();
        if (optionalFields == null || optionalFields.isEmpty()) {
            return;
        }

        for (String field : optionalFields) {
            if (conditions.containsKey(field)) {
                Object value = conditions.get(field);
                if (value != null) {
                    validateFieldValue(field, value);
                }
            }
        }
    }

    private void validateFieldValue(String field, Object value) {
        if (field.equals("age")) {
            if (!(value instanceof String) && !(value instanceof Number)) {
                throw new BusinessException(400, "年龄条件[" + field + "]格式错误，应为字符串或数字");
            }
        } else if (field.equals("gender")) {
            if (!(value instanceof String)) {
                throw new BusinessException(400, "性别条件[" + field + "]格式错误，应为字符串");
            }
            String gender = ((String) value).toLowerCase();
            if (!gender.equals("male") && !gender.equals("female") && 
                !gender.equals("all") && !gender.equals("男") && 
                !gender.equals("女") && !gender.equals("不限")) {
                throw new BusinessException(400, 
                        "性别条件值不合法: " + value + 
                        "。支持的值: male/female/all 或 男/女/不限");
            }
        } else if (field.equals("location")) {
            if (!(value instanceof String) && !(value instanceof List)) {
                throw new BusinessException(400, "位置条件[" + field + "]格式错误，应为字符串或字符串数组");
            }
        } else if (field.equals("behaviors") || field.equals("interests") || 
                   field.equals("cities") || field.equals("provinces")) {
            if (!(value instanceof List) && !(value instanceof String[])) {
                throw new BusinessException(400, "条件[" + field + "]格式错误，应为数组");
            }
        }
    }

    private void recordHistory(String adId, AdTarget target) {
        Map<String, Object> historyData = new HashMap<>();
        historyData.put("adId", adId);
        historyData.put("targetId", target.getTargetId());
        historyData.put("targetType", target.getTargetType());
        historyData.put("targetConditions", target.getTargetConditions());
        
        AdHistory history = AdHistory.builder()
                .historyId(IdGenerator.generateId("history"))
                .adId(adId)
                .historyType("TARGETING_CREATED")
                .historyData(historyData)
                .build();
        adHistoryRepository.save(history);
    }
}
