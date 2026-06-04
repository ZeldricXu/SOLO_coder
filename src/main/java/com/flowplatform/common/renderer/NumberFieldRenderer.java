package com.flowplatform.common.renderer;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

@Component
public class NumberFieldRenderer extends AbstractFieldRenderer {

    @Override
    public String getType() {
        return "number";
    }

    @Override
    protected String renderInput(JSONObject field) {
        String key = field.getString("key");
        String placeholder = getPlaceholder(field);
        boolean required = field.getBooleanValue("required");
        return String.format("<input type=\"number\" name=\"%s\" placeholder=\"%s\"%s>", key, placeholder, buildRequiredAttr(required));
    }
}
