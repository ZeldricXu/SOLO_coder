package com.flowplatform.common.renderer;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

@Component
public class DatePickerRenderer extends AbstractFieldRenderer {

    @Override
    public String getType() {
        return "date";
    }

    @Override
    protected String renderInput(JSONObject field) {
        String key = field.getString("key");
        boolean required = field.getBooleanValue("required");
        String minDate = getStr(field, "minDate", "");
        String maxDate = getStr(field, "maxDate", "");
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<input type=\"date\" name=\"%s\"", key));
        if (required) sb.append(" required");
        if (!minDate.isEmpty()) sb.append(String.format(" data-min-date=\"%s\"", minDate));
        if (!maxDate.isEmpty()) sb.append(String.format(" data-max-date=\"%s\"", maxDate));
        sb.append(">");
        return sb.toString();
    }
}
