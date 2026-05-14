package com.learningplatform.config;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Data
@Component
@ConfigurationProperties(prefix = "learning.course")
public class CourseTypeConfig {

    private static final Logger logger = LoggerFactory.getLogger(CourseTypeConfig.class);

    private Map<String, CourseType> types = new ConcurrentHashMap<>();

    @Autowired
    private Environment environment;

    @PostConstruct
    public void init() {
        logger.info("初始化课程类型配置...");
        loadConfiguredTypes();
        logger.info("课程类型配置初始化完成，共加载 {} 个课程类型", types.size());
    }

    private void loadConfiguredTypes() {
        Map<String, CourseType> loadedTypes = new ConcurrentHashMap<>();
        
        Set<String> typeCodes = new HashSet<>();
        
        if (environment instanceof ConfigurableEnvironment) {
            ConfigurableEnvironment env = (ConfigurableEnvironment) environment;
            for (PropertySource<?> propertySource : env.getPropertySources()) {
                if (propertySource instanceof MapPropertySource) {
                    MapPropertySource mapSource = (MapPropertySource) propertySource;
                    for (String key : mapSource.getPropertyNames()) {
                        if (key.startsWith("learning.course.types.") && key.contains(".code")) {
                            String code = mapSource.getProperty(key).toString();
                            typeCodes.add(code);
                        }
                    }
                }
            }
        }

        typeCodes.addAll(Arrays.asList("default", "technical", "certification"));

        for (String typeCode : typeCodes) {
            String prefix = "learning.course.types." + typeCode + ".";
            String enabledProp = prefix + "enabled";
            String nameProp = prefix + "name";
            String descProp = prefix + "description";
            String priorityProp = prefix + "priority";
            String requiresCertProp = prefix + "requires-certificate";

            boolean enabled = getBooleanProperty(enabledProp, typeCode.equals("default") || 
                                                   typeCode.equals("technical") || 
                                                   typeCode.equals("certification"));
            if (!enabled) {
                continue;
            }

            CourseType courseType = new CourseType();
            courseType.setCode(typeCode);
            courseType.setEnabled(true);
            courseType.setName(getPropertyOrDefault(nameProp, getDefaultName(typeCode)));
            courseType.setDescription(getPropertyOrDefault(descProp, getDefaultDescription(typeCode)));
            courseType.setPriority(getIntProperty(priorityProp, getDefaultPriority(typeCode)));
            courseType.setRequiresCertificate(getBooleanProperty(requiresCertProp, getDefaultRequiresCert(typeCode)));

            loadedTypes.put(typeCode, courseType);
            logger.debug("加载课程类型: {} -> {}", typeCode, courseType.getName());
        }

        if (loadedTypes.isEmpty()) {
            loadedTypes.put("default", createDefaultCourseType());
        }

        this.types = loadedTypes;
    }

    private String getDefaultName(String code) {
        switch (code) {
            case "default": return "通用课程";
            case "technical": return "技术课程";
            case "certification": return "认证课程";
            default: return code;
        }
    }

    private String getDefaultDescription(String code) {
        switch (code) {
            case "default": return "通用学习课程类型";
            case "technical": return "技术学习课程类型";
            case "certification": return "职业认证课程类型";
            default: return "自定义课程类型";
        }
    }

    private int getDefaultPriority(String code) {
        switch (code) {
            case "default": return 0;
            case "technical": return 1;
            case "certification": return 2;
            default: return 10;
        }
    }

    private boolean getDefaultRequiresCert(String code) {
        switch (code) {
            case "default": return false;
            case "technical":
            case "certification":
                return true;
            default: return false;
        }
    }

    private CourseType createDefaultCourseType() {
        CourseType defaultType = new CourseType();
        defaultType.setCode("default");
        defaultType.setEnabled(true);
        defaultType.setName("通用课程");
        defaultType.setDescription("通用学习课程类型");
        defaultType.setPriority(0);
        defaultType.setRequiresCertificate(false);
        return defaultType;
    }

    private String getPropertyOrDefault(String key, String defaultValue) {
        String value = environment.getProperty(key);
        return value != null ? value : defaultValue;
    }

    private int getIntProperty(String key, int defaultValue) {
        String value = environment.getProperty(key);
        try {
            return value != null ? Integer.parseInt(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = environment.getProperty(key);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }

    public List<CourseType> getAllTypes() {
        return types.values().stream()
                .sorted(Comparator.comparingInt(CourseType::getPriority))
                .collect(Collectors.toList());
    }

    public List<CourseType> getEnabledTypes() {
        return types.values().stream()
                .filter(CourseType::isEnabled)
                .sorted(Comparator.comparingInt(CourseType::getPriority))
                .collect(Collectors.toList());
    }

    public Optional<CourseType> getType(String code) {
        return Optional.ofNullable(types.get(code));
    }

    public boolean isValidType(String code) {
        CourseType type = types.get(code);
        return type != null && type.isEnabled();
    }

    public void addCourseType(CourseType courseType) {
        types.put(courseType.getCode(), courseType);
        logger.info("添加课程类型: {} -> {}", courseType.getCode(), courseType.getName());
    }

    public void updateCourseType(String code, CourseType courseType) {
        courseType.setCode(code);
        types.put(code, courseType);
        logger.info("更新课程类型: {} -> {}", code, courseType.getName());
    }

    public boolean removeCourseType(String code) {
        if ("default".equals(code)) {
            logger.warn("不能删除默认课程类型: default");
            return false;
        }
        CourseType removed = types.remove(code);
        if (removed != null) {
            logger.info("删除课程类型: {}", code);
            return true;
        }
        return false;
    }

    public void reload() {
        logger.info("重新加载课程类型配置...");
        int oldSize = types.size();
        loadConfiguredTypes();
        int newSize = types.size();
        logger.info("课程类型配置重新加载完成，变化: {} -> {}", oldSize, newSize);
    }

    @Data
    public static class CourseType {
        private String code;
        private String name;
        private String description;
        private int priority;
        private boolean enabled = true;
        private boolean requiresCertificate = false;
        private Map<String, Object> metadata = new HashMap<>();
    }
}
