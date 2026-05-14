package com.cms.config;

import com.cms.entity.ContentTypeConfig;
import com.cms.service.ContentTypeConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class ContentTypeInitializer implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger logger = LoggerFactory.getLogger(ContentTypeInitializer.class);

    @Autowired
    private ContentTypeConfigService contentTypeConfigService;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        initializeDefaultContentTypes();
    }

    private void initializeDefaultContentTypes() {
        try {
            List<ContentTypeConfig> existing = contentTypeConfigService.getAllActiveConfigs();
            if (existing != null && !existing.isEmpty()) {
                logger.info("发现{}个已存在的内容类型配置，跳过初始化", existing.size());
                return;
            }

            logger.info("开始初始化默认内容类型配置...");

            createContentTypeConfig("article", "文章", "标准文章内容",
                true, true, "normal", "normal", 200, 60, 120, 0);

            createContentTypeConfig("news", "新闻", "新闻资讯内容",
                true, true, "high", "high", 100, 30, 720, 1);

            createContentTypeConfig("announcement", "公告", "官方公告内容",
                true, true, "urgent", "critical", 150, 15, 1440, 2);

            createContentTypeConfig("blog", "博客", "个人博客内容",
                false, false, "normal", "low", 200, 60, 30, 3);

            createContentTypeConfig("page", "页面", "静态页面内容",
                true, true, "normal", "normal", 200, 60, 120, 4);

            logger.info("默认内容类型配置初始化完成");
        } catch (Exception e) {
            logger.error("初始化内容类型配置失败", e);
        }
    }

    private void createContentTypeConfig(String code, String name, String description,
                                         boolean reviewRequired, boolean publishApprovalRequired,
                                         String defaultUrgency, String defaultImportance,
                                         Integer maxTitleLength, Integer reviewFrequency,
                                         Integer warningOffset, int sortOrder) {
        try {
            ContentTypeConfig config = new ContentTypeConfig();
            config.setTypeCode(code);
            config.setTypeName(name);
            config.setTypeDescription(description);
            config.setReviewRequired(reviewRequired);
            config.setPublishApprovalRequired(publishApprovalRequired);
            config.setDefaultUrgencyLevel(defaultUrgency);
            config.setDefaultImportanceLevel(defaultImportance);
            config.setMaxTitleLength(maxTitleLength);
            config.setReviewFrequencyMinutes(reviewFrequency);
            config.setWarningOffsetMinutes(warningOffset);
            config.setSortOrder(sortOrder);
            config.setActive(true);
            config.setCreatedBy("system");

            contentTypeConfigService.createConfig(config);
            logger.info("创建内容类型配置: code={}, name={}", code, name);
        } catch (Exception e) {
            logger.warn("内容类型配置可能已存在: code={}", code, e);
        }
    }
}
