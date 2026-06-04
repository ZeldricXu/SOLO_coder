package com.flowplatform.common.renderer;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

@Component
public class PhoneFieldRenderer extends AbstractFieldRenderer {

    @Override
    public String getType() {
        return "phone";
    }

    @Override
    protected String renderInput(JSONObject field) {
        String key = field.getString("key");
        String placeholder = getPlaceholder(field);
        boolean required = field.getBooleanValue("required");
        String pattern = getStr(field, "pattern", "");
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<input type=\"tel\" name=\"%s\" placeholder=\"%s\"", key, placeholder));
        if (required) sb.append(" required");
        if (!pattern.isEmpty()) sb.append(String.format(" data-pattern=\"%s\"", pattern));
        sb.append(">");
        return sb.toString();
    }
}
