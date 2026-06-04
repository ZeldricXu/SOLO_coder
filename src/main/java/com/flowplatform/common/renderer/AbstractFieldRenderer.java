package com.flowplatform.common.renderer;

import com.alibaba.fastjson2.JSONObject;

public abstract class AbstractFieldRenderer implements FieldRenderer {

    @Override
    public final String render(JSONObject field) {
        String type = field.getString("type");
        String key = field.getString("key");
        String label = getStr(field, "label", "");
        boolean required = field.getBooleanValue("required");
        String innerHtml = renderInput(field);
        return wrapFieldGroup(type, key, label, required, innerHtml);
    }

    protected abstract String renderInput(JSONObject field);

    protected String wrapFieldGroup(String type, String key, String label, boolean required, String innerHtml) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<div class=\"form-group\" data-field-type=\"%s\" data-field-key=\"%s\">\n", type, key));
        sb.append(String.format("  <label class=\"form-label\">%s%s</label>\n", label, required ? "<span class=\"required\">*</span>" : ""));
        sb.append("  ").append(innerHtml).append("\n");
        sb.append("</div>\n");
        return sb.toString();
    }

    protected String buildRequiredAttr(boolean required) {
        return required ? " required" : "";
    }

    protected String getPlaceholder(JSONObject field) {
        return getStr(field, "placeholder", "");
    }

    protected String getStr(JSONObject field, String key, String defaultValue) {
        Object val = field.get(key);
        return val != null ? val.toString() : defaultValue;
    }
}
