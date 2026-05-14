package com.movie.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "movie")
public class MovieTypeConfig {

    private List<MovieType> types = new ArrayList<>();
    private Map<String, MovieType> typeMap = new HashMap<>();
    private String defaultType = "other";

    public List<MovieType> getTypes() {
        return types;
    }

    public void setTypes(List<MovieType> types) {
        this.types = types;
        this.typeMap.clear();
        for (MovieType type : types) {
            if (type.getCode() != null) {
                typeMap.put(type.getCode().toLowerCase(), type);
            }
        }
    }

    public String getDefaultType() {
        return defaultType;
    }

    public void setDefaultType(String defaultType) {
        this.defaultType = defaultType;
    }

    public boolean isValidType(String typeCode) {
        if (typeCode == null) {
            return false;
        }
        return typeMap.containsKey(typeCode.toLowerCase());
    }

    public MovieType getTypeByCode(String typeCode) {
        if (typeCode == null) {
            return null;
        }
        return typeMap.get(typeCode.toLowerCase());
    }

    public String getTypeName(String typeCode) {
        MovieType type = getTypeByCode(typeCode);
        return type != null ? type.getName() : typeCode;
    }

    public String getTypeDescription(String typeCode) {
        MovieType type = getTypeByCode(typeCode);
        return type != null ? type.getDescription() : "";
    }

    public List<String> getAllTypeCodes() {
        return new ArrayList<>(typeMap.keySet());
    }

    public List<String> getAllTypeNames() {
        List<String> names = new ArrayList<>();
        for (MovieType type : types) {
            if (type.getName() != null) {
                names.add(type.getName());
            }
        }
        return names;
    }

    public static class MovieType {
        private String code;
        private String name;
        private String description;
        private String color;
        private Integer sortOrder;
        private Boolean enabled = true;

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

        public String getColor() {
            return color;
        }

        public void setColor(String color) {
            this.color = color;
        }

        public Integer getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(Integer sortOrder) {
            this.sortOrder = sortOrder;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
    }
}
