package com.flowplatform.common.renderer;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

@Component
public class DefaultFieldRenderer extends AbstractFieldRenderer {

    @Override
    public String getType() {
        return "default";
    }

    @Override
    protected String renderInput(JSONObject field) {
        String key = field.getString("key");
        String placeholder = getPlaceholder(field);
        return String.format("<input type=\"text\" name=\"%s\" placeholder=\"%s\">", key, placeholder);
    }
}
