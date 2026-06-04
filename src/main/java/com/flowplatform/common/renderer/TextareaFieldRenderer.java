package com.flowplatform.common.renderer;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

@Component
public class TextareaFieldRenderer extends AbstractFieldRenderer {

    @Override
    public String getType() {
        return "textarea";
    }

    @Override
    protected String renderInput(JSONObject field) {
        String key = field.getString("key");
        String placeholder = getPlaceholder(field);
        boolean required = field.getBooleanValue("required");
        return String.format("<textarea name=\"%s\" placeholder=\"%s\"%s></textarea>", key, placeholder, buildRequiredAttr(required));
    }
}
