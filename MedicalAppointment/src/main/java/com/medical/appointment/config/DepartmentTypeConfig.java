package com.medical.appointment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "appointment.department")
public class DepartmentTypeConfig {

    private Map<String, DepartmentTypeInfo> types = new HashMap<>();

    public Map<String, DepartmentTypeInfo> getTypes() {
        return types;
    }

    public void setTypes(Map<String, DepartmentTypeInfo> types) {
        this.types = types;
    }

    public DepartmentTypeInfo getTypeInfo(String typeCode) {
        return types.get(typeCode);
    }

    public boolean isValidType(String typeCode) {
        return types.containsKey(typeCode);
    }

    public List<DepartmentTypeInfo> getAllTypes() {
        return new ArrayList<>(types.values());
    }

    public List<String> getAllTypeCodes() {
        return new ArrayList<>(types.keySet());
    }

    public static class DepartmentTypeInfo {
        private String code;
        private String name;
        private String description;
        private boolean enabled = true;
        private int priority = 100;

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

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }
    }
}
