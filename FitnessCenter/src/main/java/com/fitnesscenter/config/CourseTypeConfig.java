package com.fitnesscenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@ConfigurationProperties(prefix = "fitness.course")
public class CourseTypeConfig {

    private Map<String, CourseType> types = new HashMap<>();

    public Map<String, CourseType> getTypes() {
        if (types.isEmpty()) {
            initDefaultTypes();
        }
        return types;
    }

    public void setTypes(Map<String, CourseType> types) {
        this.types = types;
    }

    private void initDefaultTypes() {
        CourseType yoga = new CourseType();
        yoga.setEnabled(true);
        yoga.setDescription("瑜伽课程 - 柔韧性练习");
        types.put("yoga", yoga);

        CourseType hiit = new CourseType();
        hiit.setEnabled(true);
        hiit.setDescription("高强度间歇训练");
        types.put("hiit", hiit);

        CourseType strength = new CourseType();
        strength.setEnabled(true);
        strength.setDescription("力量训练课程");
        types.put("strength", strength);

        CourseType cardio = new CourseType();
        cardio.setEnabled(true);
        cardio.setDescription("有氧训练课程");
        types.put("cardio", cardio);

        CourseType pilates = new CourseType();
        pilates.setEnabled(true);
        pilates.setDescription("普拉提课程");
        types.put("pilates", pilates);

        CourseType zumba = new CourseType();
        zumba.setEnabled(true);
        zumba.setDescription("尊巴舞蹈课程");
        types.put("zumba", zumba);

        CourseType spinning = new CourseType();
        spinning.setEnabled(true);
        spinning.setDescription("动感单车课程");
        types.put("spinning", spinning);

        CourseType swimming = new CourseType();
        swimming.setEnabled(true);
        swimming.setDescription("游泳课程");
        types.put("swimming", swimming);
    }

    public boolean isTypeEnabled(String courseType) {
        if (courseType == null || courseType.isEmpty()) {
            return false;
        }
        CourseType type = getTypes().get(courseType.toLowerCase());
        return type != null && type.isEnabled();
    }

    public String getTypeDescription(String courseType) {
        if (courseType == null || courseType.isEmpty()) {
            return "未知类型";
        }
        CourseType type = getTypes().get(courseType.toLowerCase());
        return type != null ? type.getDescription() : "未知类型";
    }

    public Set<String> getAllEnabledTypes() {
        return getTypes().entrySet().stream()
                .filter(entry -> entry.getValue().isEnabled())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    public Set<String> getAllTypes() {
        return getTypes().keySet();
    }

    public Map<String, String> getAllTypesWithDescription() {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, CourseType> entry : getTypes().entrySet()) {
            if (entry.getValue().isEnabled()) {
                result.put(entry.getKey(), entry.getValue().getDescription());
            }
        }
        return result;
    }

    public void addCourseType(String typeCode, String description, boolean enabled) {
        CourseType courseType = new CourseType();
        courseType.setEnabled(enabled);
        courseType.setDescription(description);
        types.put(typeCode.toLowerCase(), courseType);
    }

    public void enableCourseType(String typeCode) {
        CourseType courseType = types.get(typeCode.toLowerCase());
        if (courseType != null) {
            courseType.setEnabled(true);
        }
    }

    public void disableCourseType(String typeCode) {
        CourseType courseType = types.get(typeCode.toLowerCase());
        if (courseType != null) {
            courseType.setEnabled(false);
        }
    }

    public static class CourseType {
        private boolean enabled = true;
        private String description = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}
