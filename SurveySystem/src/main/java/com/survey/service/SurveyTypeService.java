package com.survey.service;

import com.survey.common.SurveyConstants;
import com.survey.config.SurveyTypeProperties;
import com.survey.dto.TypeCreateRequest;
import com.survey.entity.SurveyType;
import com.survey.exception.SurveyException;
import com.survey.repository.SurveyTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SurveyTypeService {

    private final SurveyTypeRepository surveyTypeRepository;
    private final SurveyTypeProperties typeProperties;
    private final HistoryService historyService;

    @Transactional
    public SurveyType createType(TypeCreateRequest request) {
        log.info("创建问卷类型: {}", request.getTypeCode());

        Optional<SurveyType> existing = surveyTypeRepository.findByTypeCode(request.getTypeCode());
        if (existing.isPresent()) {
            throw new SurveyException(400, "类型编码已存在: " + request.getTypeCode());
        }

        SurveyType type = new SurveyType();
        type.setTypeCode(request.getTypeCode());
        type.setTypeName(request.getTypeName());
        type.setTypeDescription(request.getTypeDescription());
        type.setTypeStatus(SurveyConstants.TYPE_STATUS_ACTIVE);
        type.setTypeCategory(request.getTypeCategory());
        type.setTypeIcon(request.getTypeIcon());
        type.setTypeColor(request.getTypeColor());
        type.setTypeConfig(request.getTypeConfig());
        type.setIsSystem(false);
        type.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : getNextSortOrder());
        type.setCreatedAt(LocalDateTime.now());

        SurveyType saved = surveyTypeRepository.save(type);
        historyService.recordSurveyHistory(request.getTypeCode(), "CREATE_TYPE",
                "创建问卷类型: " + request.getTypeName(), null);
        log.info("问卷类型创建成功: {}", saved.getTypeCode());
        return saved;
    }

    @Transactional
    public SurveyType updateType(String typeCode, TypeCreateRequest request) {
        log.info("更新问卷类型: {}", typeCode);

        SurveyType type = surveyTypeRepository.findByTypeCode(typeCode)
                .orElseThrow(() -> new SurveyException(404, "类型不存在: " + typeCode));

        type.setTypeName(request.getTypeName());
        type.setTypeDescription(request.getTypeDescription());
        if (request.getTypeCategory() != null) {
            type.setTypeCategory(request.getTypeCategory());
        }
        if (request.getTypeIcon() != null) {
            type.setTypeIcon(request.getTypeIcon());
        }
        if (request.getTypeColor() != null) {
            type.setTypeColor(request.getTypeColor());
        }
        if (request.getTypeConfig() != null) {
            type.setTypeConfig(request.getTypeConfig());
        }
        if (request.getSortOrder() != null) {
            type.setSortOrder(request.getSortOrder());
        }
        type.setUpdatedAt(LocalDateTime.now());

        SurveyType saved = surveyTypeRepository.save(type);
        historyService.recordSurveyHistory(typeCode, "UPDATE_TYPE",
                "更新问卷类型: " + request.getTypeName(), null);
        return saved;
    }

    @Transactional
    public void deactivateType(String typeCode) {
        log.info("停用问卷类型: {}", typeCode);

        SurveyType type = surveyTypeRepository.findByTypeCode(typeCode)
                .orElseThrow(() -> new SurveyException(404, "类型不存在: " + typeCode));

        if (Boolean.TRUE.equals(type.getIsSystem())) {
            throw new SurveyException(400, "系统类型不能停用: " + typeCode);
        }

        type.setTypeStatus(SurveyConstants.TYPE_STATUS_INACTIVE);
        type.setUpdatedAt(LocalDateTime.now());
        surveyTypeRepository.save(type);
        historyService.recordSurveyHistory(typeCode, "DEACTIVATE_TYPE",
                "停用问卷类型: " + type.getTypeName(), null);
    }

    @Transactional
    public void activateType(String typeCode) {
        log.info("启用问卷类型: {}", typeCode);

        SurveyType type = surveyTypeRepository.findByTypeCode(typeCode)
                .orElseThrow(() -> new SurveyException(404, "类型不存在: " + typeCode));

        type.setTypeStatus(SurveyConstants.TYPE_STATUS_ACTIVE);
        type.setUpdatedAt(LocalDateTime.now());
        surveyTypeRepository.save(type);
        historyService.recordSurveyHistory(typeCode, "ACTIVATE_TYPE",
                "启用问卷类型: " + type.getTypeName(), null);
    }

    @Transactional
    public void deleteType(String typeCode) {
        log.info("删除问卷类型: {}", typeCode);

        SurveyType type = surveyTypeRepository.findByTypeCode(typeCode)
                .orElseThrow(() -> new SurveyException(404, "类型不存在: " + typeCode));

        if (Boolean.TRUE.equals(type.getIsSystem())) {
            throw new SurveyException(400, "系统类型不能删除: " + typeCode);
        }

        surveyTypeRepository.delete(type);
        historyService.recordSurveyHistory(typeCode, "DELETE_TYPE",
                "删除问卷类型: " + type.getTypeName(), null);
    }

    public List<SurveyType> getAllTypes() {
        return surveyTypeRepository.findAll().stream()
                .sorted(Comparator.comparingInt(SurveyType::getSortOrder))
                .collect(Collectors.toList());
    }

    public List<SurveyType> getActiveTypes() {
        return surveyTypeRepository.findByTypeStatus(SurveyConstants.TYPE_STATUS_ACTIVE).stream()
                .sorted(Comparator.comparingInt(SurveyType::getSortOrder))
                .collect(Collectors.toList());
    }

    public List<SurveyType> getTypesByCategory(String category) {
        return surveyTypeRepository.findByTypeCategory(category).stream()
                .sorted(Comparator.comparingInt(SurveyType::getSortOrder))
                .collect(Collectors.toList());
    }

    public List<SurveyType> getSystemTypes() {
        return surveyTypeRepository.findByIsSystem(true).stream()
                .sorted(Comparator.comparingInt(SurveyType::getSortOrder))
                .collect(Collectors.toList());
    }

    public List<SurveyType> getCustomTypes() {
        return surveyTypeRepository.findByIsSystem(false).stream()
                .sorted(Comparator.comparingInt(SurveyType::getSortOrder))
                .collect(Collectors.toList());
    }

    public SurveyType getType(String typeCode) {
        return surveyTypeRepository.findByTypeCode(typeCode)
                .orElseThrow(() -> new SurveyException(404, "类型不存在: " + typeCode));
    }

    public boolean typeExists(String typeCode) {
        return surveyTypeRepository.findByTypeCode(typeCode).isPresent();
    }

    public boolean isSystemType(String typeCode) {
        Optional<SurveyType> typeOpt = surveyTypeRepository.findByTypeCode(typeCode);
        return typeOpt.isPresent() && Boolean.TRUE.equals(typeOpt.get().getIsSystem());
    }

    private int getNextSortOrder() {
        List<SurveyType> types = surveyTypeRepository.findAll();
        if (types.isEmpty()) {
            return 0;
        }
        return types.stream()
                .mapToInt(SurveyType::getSortOrder)
                .max()
                .orElse(0) + 1;
    }

    @Transactional
    public void initializeFromConfig() {
        if (!typeProperties.isEnableConfigInit()) {
            log.info("配置初始化已禁用，跳过类型初始化");
            return;
        }

        log.info("从配置文件初始化问卷类型...");

        for (SurveyTypeProperties.TypeConfig config : typeProperties.getList()) {
            try {
                Optional<SurveyType> existing = surveyTypeRepository.findByTypeCode(config.getCode());
                if (existing.isEmpty()) {
                    SurveyType type = new SurveyType();
                    type.setTypeCode(config.getCode());
                    type.setTypeName(config.getName());
                    type.setTypeDescription(config.getDescription());
                    type.setTypeStatus(config.getStatus());
                    type.setTypeCategory(config.getCategory());
                    type.setTypeIcon(config.getIcon());
                    type.setTypeColor(config.getColor());
                    type.setTypeConfig(config.getConfig());
                    type.setIsSystem(config.isSystem());
                    type.setSortOrder(config.getSortOrder());
                    type.setCreatedAt(LocalDateTime.now());
                    surveyTypeRepository.save(type);
                    log.info("已创建配置类型: {}", config.getName());
                }
            } catch (Exception e) {
                log.error("初始化类型失败: {}", config.getCode(), e);
            }
        }
    }
}
