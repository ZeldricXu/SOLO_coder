package com.eventticket.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "seat.section")
public class SeatSectionConfig {

    private Map<String, SectionConfig> sections = new HashMap<>();
    private String defaultSection = "regular";

    @Data
    public static class SectionConfig {
        private String name;
        private String displayName;
        private int basePrice;
        private int priority;
        private boolean selectable;
        private String colorCode;
        private String description;
        private int minSeats;
        private int maxSeats;
    }

    public SectionConfig getSectionConfig(String sectionCode) {
        SectionConfig config = sections.get(sectionCode);
        if (config == null) {
            config = sections.get(defaultSection);
        }
        return config;
    }

    public boolean isValidSection(String sectionCode) {
        return sections.containsKey(sectionCode);
    }

    public int getBasePrice(String sectionCode) {
        SectionConfig config = getSectionConfig(sectionCode);
        return config != null ? config.getBasePrice() : 100;
    }

    public int getPriority(String sectionCode) {
        SectionConfig config = getSectionConfig(sectionCode);
        return config != null ? config.getPriority() : 100;
    }

    public boolean isSelectable(String sectionCode) {
        SectionConfig config = getSectionConfig(sectionCode);
        return config != null ? config.isSelectable() : true;
    }

    public String getDisplayName(String sectionCode) {
        SectionConfig config = getSectionConfig(sectionCode);
        return config != null ? config.getDisplayName() : "普通区";
    }

    public Map<String, SectionConfig> getAllSections() {
        return new HashMap<>(sections);
    }

    public java.util.List<String> getSectionCodes() {
        return new java.util.ArrayList<>(sections.keySet());
    }
}
