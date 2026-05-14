package com.eventticket.service;

import com.eventticket.config.SeatSectionConfig;
import com.eventticket.config.SeatSectionConfig.SectionConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SeatSectionService {

    @Autowired
    private SeatSectionConfig sectionConfig;

    public List<String> getAllSectionCodes() {
        return sectionConfig.getSectionCodes();
    }

    public Map<String, SectionConfig> getAllSectionConfigs() {
        return sectionConfig.getAllSections();
    }

    public SectionConfig getSectionConfig(String sectionCode) {
        return sectionConfig.getSectionConfig(sectionCode);
    }

    public boolean isValidSection(String sectionCode) {
        return sectionConfig.isValidSection(sectionCode);
    }

    public int getBasePrice(String sectionCode) {
        return sectionConfig.getBasePrice(sectionCode);
    }

    public int getPriority(String sectionCode) {
        return sectionConfig.getPriority(sectionCode);
    }

    public boolean isSelectable(String sectionCode) {
        return sectionConfig.isSelectable(sectionCode);
    }

    public String getDisplayName(String sectionCode) {
        return sectionConfig.getDisplayName(sectionCode);
    }

    public String getDefaultSection() {
        return sectionConfig.getDefaultSection();
    }

    public List<SectionConfig> getSelectableSections() {
        return getAllSectionConfigs().values().stream()
                .filter(SectionConfig::isSelectable)
                .sorted(Comparator.comparingInt(SectionConfig::getPriority))
                .collect(Collectors.toList());
    }

    public List<SectionConfig> getSectionsSortedByPriority() {
        return getAllSectionConfigs().values().stream()
                .sorted(Comparator.comparingInt(SectionConfig::getPriority))
                .collect(Collectors.toList());
    }

    public Map<String, Integer> getSectionPriceMap() {
        Map<String, Integer> priceMap = new HashMap<>();
        getAllSectionConfigs().forEach((code, config) -> 
            priceMap.put(code, config.getBasePrice())
        );
        return priceMap;
    }

    public Map<String, String> getSectionDisplayNameMap() {
        Map<String, String> displayMap = new HashMap<>();
        getAllSectionConfigs().forEach((code, config) -> 
            displayMap.put(code, config.getDisplayName())
        );
        return displayMap;
    }

    public SectionConfig validateAndGetSection(String sectionCode) {
        if (!isValidSection(sectionCode)) {
            String defaultSection = getDefaultSection();
            log.warn("Invalid section code: {}, using default: {}", sectionCode, defaultSection);
            return getSectionConfig(defaultSection);
        }
        return getSectionConfig(sectionCode);
    }

    public int calculateSeatPrice(String sectionCode, Integer customPrice) {
        SectionConfig config = validateAndGetSection(sectionCode);
        if (customPrice != null && customPrice > 0) {
            return customPrice;
        }
        return config.getBasePrice();
    }

    public void logSectionConfiguration() {
        log.info("Seat Section Configuration:");
        getSectionsSortedByPriority().forEach(config -> {
            log.info("  Section: {} ({}), Priority: {}, Price: {}, Selectable: {}",
                config.getName(),
                config.getDisplayName(),
                config.getPriority(),
                config.getBasePrice(),
                config.isSelectable()
            );
        });
    }

    public int getTotalSections() {
        return getAllSectionConfigs().size();
    }

    public boolean isVipSection(String sectionCode) {
        return "vip".equalsIgnoreCase(sectionCode) || "vvip".equalsIgnoreCase(sectionCode);
    }

    public boolean isStudentSection(String sectionCode) {
        return "student".equalsIgnoreCase(sectionCode);
    }

    public boolean isStandingSection(String sectionCode) {
        return "standing".equalsIgnoreCase(sectionCode);
    }

    public String getTicketTypeFromSection(String sectionCode) {
        if (isVipSection(sectionCode)) {
            return "vip";
        } else if (isStudentSection(sectionCode)) {
            return "student";
        } else if (isStandingSection(sectionCode)) {
            return "standing";
        }
        return "regular";
    }
}
