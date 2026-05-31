package com.projectservice.model.scaffold;

import java.util.List;

public class TemplateParameter {
    private String name;
    private String description;
    private String type;
    private Object defaultValue;
    private boolean required;
    private List<String> options;
    private String validation;
    private String category;

    public TemplateParameter() {}

    public TemplateParameter(String name, String description, String type, Object defaultValue,
                            boolean required, List<String> options, String validation, String category) {
        this.name = name;
        this.description = description;
        this.type = type;
        this.defaultValue = defaultValue;
        this.required = required;
        this.options = options;
        this.validation = validation;
        this.category = category;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Object getDefaultValue() { return defaultValue; }
    public void setDefaultValue(Object defaultValue) { this.defaultValue = defaultValue; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public List<String> getOptions() { return options; }
    public void setOptions(List<String> options) { this.options = options; }
    public String getValidation() { return validation; }
    public void setValidation(String validation) { this.validation = validation; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
}
