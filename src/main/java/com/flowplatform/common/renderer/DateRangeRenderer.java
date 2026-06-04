package com.flowplatform.common.renderer;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

@Component
public class DateRangeRenderer extends AbstractFieldRenderer {

    @Override
    public String getType() {
        return "dateRange";
    }

    @Override
    protected String renderInput(JSONObject field) {
        String key = field.getString("key");
        boolean required = field.getBooleanValue("required");
        return String.format("<input type=\"text\" name=\"%s_start\" placeholder=\"开始日期\"%s> ~ <input type=\"text\" name=\"%s_end\" placeholder=\"结束日期\"%s>", key, buildRequiredAttr(required), key, buildRequiredAttr(required));
    }
}
