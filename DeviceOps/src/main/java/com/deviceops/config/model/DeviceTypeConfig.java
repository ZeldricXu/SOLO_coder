package com.deviceops.config.model;

public class DeviceTypeConfig {

    private String typeCode;
    private String typeName;
    private String typeDesc;
    private String category;
    private Boolean enabled;
    private String icon;

    public DeviceTypeConfig() {
    }

    public DeviceTypeConfig(String typeCode, String typeName, String typeDesc,
                            String category, Boolean enabled, String icon) {
        this.typeCode = typeCode;
        this.typeName = typeName;
        this.typeDesc = typeDesc;
        this.category = category;
        this.enabled = enabled;
        this.icon = icon;
    }

    public String getTypeCode() {
        return typeCode;
    }

    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    public String getTypeName() {
        return typeName;
    }

    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }

    public String getTypeDesc() {
        return typeDesc;
    }

    public void setTypeDesc(String typeDesc) {
        this.typeDesc = typeDesc;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }
}
