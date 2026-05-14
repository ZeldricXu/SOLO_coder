package com.hotelbooking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "hotelbooking.room")
public class RoomTypeConfig {

    private Map<String, RoomTypeConfigEntry> types = new HashMap<>();

    private String defaultType = "STANDARD";

    public Map<String, RoomTypeConfigEntry> getTypes() {
        return types;
    }

    public void setTypes(Map<String, RoomTypeConfigEntry> types) {
        this.types = types;
    }

    public String getDefaultType() {
        return defaultType;
    }

    public void setDefaultType(String defaultType) {
        this.defaultType = defaultType;
    }

    public RoomTypeConfigEntry getTypeConfig(String roomType) {
        return types.getOrDefault(roomType.toUpperCase(), types.get(defaultType));
    }

    public List<String> getAllRoomTypes() {
        return new ArrayList<>(types.keySet());
    }

    public boolean isValidRoomType(String roomType) {
        return types.containsKey(roomType.toUpperCase());
    }

    public static class RoomTypeConfigEntry {
        private String code;
        private String name;
        private String description;
        private double basePrice;
        private int maxOccupancy;
        private int sizeSqm;
        private List<String> defaultFeatures = new ArrayList<>();
        private List<String> amenities = new ArrayList<>();
        private String bedType;
        private String viewType;
        private boolean smokingAllowed = false;
        private boolean breakfastIncluded = false;
        private double discountRate = 0.0;
        private Map<String, Object> extra = new HashMap<>();

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public double getBasePrice() {
            return basePrice;
        }

        public void setBasePrice(double basePrice) {
            this.basePrice = basePrice;
        }

        public int getMaxOccupancy() {
            return maxOccupancy;
        }

        public void setMaxOccupancy(int maxOccupancy) {
            this.maxOccupancy = maxOccupancy;
        }

        public int getSizeSqm() {
            return sizeSqm;
        }

        public void setSizeSqm(int sizeSqm) {
            this.sizeSqm = sizeSqm;
        }

        public List<String> getDefaultFeatures() {
            return defaultFeatures;
        }

        public void setDefaultFeatures(List<String> defaultFeatures) {
            this.defaultFeatures = defaultFeatures;
        }

        public List<String> getAmenities() {
            return amenities;
        }

        public void setAmenities(List<String> amenities) {
            this.amenities = amenities;
        }

        public String getBedType() {
            return bedType;
        }

        public void setBedType(String bedType) {
            this.bedType = bedType;
        }

        public String getViewType() {
            return viewType;
        }

        public void setViewType(String viewType) {
            this.viewType = viewType;
        }

        public boolean isSmokingAllowed() {
            return smokingAllowed;
        }

        public void setSmokingAllowed(boolean smokingAllowed) {
            this.smokingAllowed = smokingAllowed;
        }

        public boolean isBreakfastIncluded() {
            return breakfastIncluded;
        }

        public void setBreakfastIncluded(boolean breakfastIncluded) {
            this.breakfastIncluded = breakfastIncluded;
        }

        public double getDiscountRate() {
            return discountRate;
        }

        public void setDiscountRate(double discountRate) {
            this.discountRate = discountRate;
        }

        public Map<String, Object> getExtra() {
            return extra;
        }

        public void setExtra(Map<String, Object> extra) {
            this.extra = extra;
        }

        public double getActualPrice() {
            return basePrice * (1 - discountRate);
        }
    }
}
