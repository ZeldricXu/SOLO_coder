package com.flowplatform.common.renderer;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

@Component
public class FileUploadRenderer extends AbstractFieldRenderer {

    @Override
    public String getType() {
        return "fileUpload";
    }

    @Override
    protected String renderInput(JSONObject field) {
        String key = field.getString("key");
        boolean required = field.getBooleanValue("required");
        return String.format("<input type=\"file\" name=\"%s\"%s>", key, buildRequiredAttr(required));
    }
}
