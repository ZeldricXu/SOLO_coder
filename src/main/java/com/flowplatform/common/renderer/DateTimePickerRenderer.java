package com.flowplatform.common.renderer;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

@Component
public class DateTimePickerRenderer extends AbstractFieldRenderer {

    @Override
    public String getType() {
        return "datetime";
    }

    @Override
    protected String renderInput(JSONObject field) {
        String key = field.getString("key");
        boolean required = field.getBooleanValue("required");
        return String.format("<input type=\"datetime-local\" name=\"%s\"%s>", key, buildRequiredAttr(required));
    }
}
