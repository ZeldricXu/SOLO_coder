package com.recruitment.service;

import com.recruitment.common.enums.InterviewType;
import com.recruitment.model.PositionTypeConfig;
import com.recruitment.repository.PositionTypeConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PositionTypeConfigService {

    private final PositionTypeConfigRepository positionTypeConfigRepository;

    @PostConstruct
    public void initDefaultTypes() {
        log.info("PositionTypeConfig: 初始化默认职位类型配置");

        if (positionTypeConfigRepository.count() == 0) {
            List<PositionTypeConfig> defaultTypes = Arrays.asList(
                    createConfig("TECHNICAL", "技术类", "包括研发、测试、运维等技术岗位", "TECHNICAL,HR,TECHNICAL", 1),
                    createConfig("PRODUCT", "产品类", "产品经理、产品运营等岗位", "TECHNICAL,HR,TECHNICAL", 2),
                    createConfig("DESIGN", "设计类", "UI设计、UX设计等岗位", "TECHNICAL,HR", 3),
                    createConfig("OPERATIONS", "运营类", "内容运营、用户运营等岗位", "HR,TECHNICAL", 4),
                    createConfig("MARKETING", "市场类", "市场营销、品牌推广等岗位", "HR", 5),
                    createConfig("HR", "人力类", "人力资源相关岗位", "HR", 6),
                    createConfig("FINANCE", "财务类", "财务、会计相关岗位", "HR,TECHNICAL", 7),
                    createConfig("MANAGEMENT", "管理类", "管理层岗位", "TECHNICAL,HR,TECHNICAL", 8)
            );

            positionTypeConfigRepository.saveAll(defaultTypes);
            log.info("PositionTypeConfig: 已初始化 {} 个默认职位类型", defaultTypes.size());
        }
    }

    private PositionTypeConfig createConfig(String code, String name, String description,
                                            String stages, Integer sortOrder) {
        return PositionTypeConfig.builder()
                .typeCode(code)
                .typeName(name)
                .description(description)
                .interviewStages(stages)
                .sortOrder(sortOrder)
                .isEnabled(true)
                .build();
    }

    @Transactional
    public PositionTypeConfig addPositionType(String typeCode, String typeName,
                                               String description, String interviewStages,
                                               Integer sortOrder) {
        log.info("PositionTypeConfig: 添加新职位类型, code: {}, name: {}", typeCode, typeName);

        if (positionTypeConfigRepository.existsByTypeCode(typeCode)) {
            throw new RuntimeException("职位类型已存在: " + typeCode);
        }

        PositionTypeConfig config = PositionTypeConfig.builder()
                .typeCode(typeCode)
                .typeName(typeName)
                .description(description)
                .interviewStages(interviewStages)
                .sortOrder(sortOrder != null ? sortOrder : 100)
                .isEnabled(true)
                .build();

        PositionTypeConfig saved = positionTypeConfigRepository.save(config);
        log.info("PositionTypeConfig: 新职位类型添加成功, id: {}", saved.getId());

        return saved;
    }

    @Transactional
    public PositionTypeConfig updatePositionType(String typeCode, String typeName,
                                                  String description, String interviewStages,
                                                  Integer sortOrder, Boolean isEnabled) {
        log.info("PositionTypeConfig: 更新职位类型, code: {}", typeCode);

        PositionTypeConfig config = positionTypeConfigRepository.findByTypeCode(typeCode)
                .orElseThrow(() -> new RuntimeException("职位类型不存在: " + typeCode));

        if (typeName != null) config.setTypeName(typeName);
        if (description != null) config.setDescription(description);
        if (interviewStages != null) config.setInterviewStages(interviewStages);
        if (sortOrder != null) config.setSortOrder(sortOrder);
        if (isEnabled != null) config.setIsEnabled(isEnabled);

        return positionTypeConfigRepository.save(config);
    }

    @Transactional
    public void deletePositionType(String typeCode) {
        log.info("PositionTypeConfig: 删除职位类型, code: {}", typeCode);

        if (!positionTypeConfigRepository.existsByTypeCode(typeCode)) {
            throw new RuntimeException("职位类型不存在: " + typeCode);
        }

        positionTypeConfigRepository.deleteByTypeCode(typeCode);
    }

    public Optional<PositionTypeConfig> getPositionTypeByCode(String typeCode) {
        return positionTypeConfigRepository.findByTypeCode(typeCode);
    }

    public List<PositionTypeConfig> getAllEnabledTypes() {
        return positionTypeConfigRepository.findByIsEnabledTrueOrderBySortOrderAsc();
    }

    public List<PositionTypeConfig> getAllTypes() {
        return positionTypeConfigRepository.findAllByOrderBySortOrderAsc();
    }

    public boolean isValidPositionType(String typeCode) {
        return positionTypeConfigRepository.findByTypeCode(typeCode)
                .map(PositionTypeConfig::getIsEnabled)
                .orElse(false);
    }

    public List<InterviewType> getInterviewStagesForType(String typeCode) {
        return positionTypeConfigRepository.findByTypeCode(typeCode)
                .map(config -> {
                    String stages = config.getInterviewStages();
                    if (stages == null || stages.isEmpty()) {
                        return defaultInterviewStages();
                    }
                    return Arrays.stream(stages.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .map(this::parseInterviewType)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                })
                .orElse(defaultInterviewStages());
    }

    private List<InterviewType> defaultInterviewStages() {
        return Arrays.asList(InterviewType.TECHNICAL, InterviewType.HR);
    }

    private InterviewType parseInterviewType(String type) {
        try {
            return InterviewType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("PositionTypeConfig: 无法解析面试类型: {}", type);
            return null;
        }
    }

    public Map<String, String> getPositionTypeMap() {
        Map<String, String> typeMap = new LinkedHashMap<>();
        for (PositionTypeConfig config : getAllEnabledTypes()) {
            typeMap.put(config.getTypeCode(), config.getTypeName());
        }
        return typeMap;
    }

    @Transactional
    public PositionTypeConfig enablePositionType(String typeCode) {
        return updatePositionType(typeCode, null, null, null, null, true);
    }

    @Transactional
    public PositionTypeConfig disablePositionType(String typeCode) {
        return updatePositionType(typeCode, null, null, null, null, false);
    }
}
