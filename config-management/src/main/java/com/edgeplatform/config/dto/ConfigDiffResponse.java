package com.edgeplatform.config.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfigDiffResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String configId;
    private Integer fromVersion;
    private Integer toVersion;
    private List<DiffEntry> differences;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiffEntry implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String key;
        private Object oldValue;
        private Object newValue;
        private String changeType;
    }
}
