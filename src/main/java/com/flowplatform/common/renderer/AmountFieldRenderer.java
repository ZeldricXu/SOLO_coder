package com.flowplatform.common.renderer;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

@Component
public class AmountFieldRenderer extends AbstractFieldRenderer {

    @Override
    public String getType() {
        return "amount";
    }

    @Override
    protected String renderInput(JSONObject field) {
        String key = field.getString("key");
        String placeholder = getPlaceholder(field);
        boolean required = field.getBooleanValue("required");
        return String.format("<input type=\"number\" step=\"0.01\" name=\"%s\" placeholder=\"%s\" class=\"amount-input\"%s>", key, placeholder, buildRequiredAttr(required));
    }
}
