package com.survey.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "survey.types")
public class SurveyTypeProperties {

    private List<TypeConfig> list = new ArrayList<>();

    private boolean enableConfigInit = true;

    @Data
    public static class TypeConfig {
        private String code;
        private String name;
        private String description;
        private String status = "active";
        private String category;
        private String icon;
        private String color;
        private String config;
        private boolean system = true;
        private int sortOrder = 0;
    }
}
