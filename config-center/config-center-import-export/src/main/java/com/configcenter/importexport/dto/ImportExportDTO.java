package com.configcenter.importexport.dto;

import com.configcenter.common.enums.ConfigType;
import com.configcenter.common.enums.Environment;
import lombok.Data;

import java.util.List;

@Data
public class ImportExportDTO {
    
    private String version = "1.0";
    
    private String exportTime;
    
    private String exportedBy;
    
    private List<ConfigItemExport> configs;

    @Data
    public static class ConfigItemExport {
        private String configKey;
        private String configValue;
        private ConfigType configType;
        private Boolean isEncrypted;
        private Environment environment;
        private String groupId;
        private String groupName;
        private String description;
        private String currentVersion;
    }
}
