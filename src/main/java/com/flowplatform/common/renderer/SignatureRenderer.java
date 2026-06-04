package com.flowplatform.common.renderer;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

@Component
public class SignatureRenderer extends AbstractFieldRenderer {

    @Override
    public String getType() {
        return "signature";
    }

    @Override
    protected String renderInput(JSONObject field) {
        String key = field.getString("key");
        return String.format("<div class=\"signature-pad\" data-field=\"%s\"><canvas></canvas></div>", key);
    }
}
