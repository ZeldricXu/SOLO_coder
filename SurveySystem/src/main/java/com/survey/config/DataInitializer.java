package com.survey.config;

import com.survey.service.SurveyTypeService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer {

    private final SurveyTypeService surveyTypeService;
    private final SurveyTypeProperties typeProperties;

    @PostConstruct
    public void init() {
        log.info("开始初始化数据...");
        initializeSurveyTypes();
        log.info("数据初始化完成");
    }

    private void initializeSurveyTypes() {
        log.info("配置的类型数量: {}", typeProperties.getList().size());
        if (!typeProperties.getList().isEmpty()) {
            surveyTypeService.initializeFromConfig();
        }
    }
}
