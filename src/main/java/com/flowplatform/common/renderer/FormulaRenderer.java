package com.flowplatform.common.renderer;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

@Component
public class FormulaRenderer extends AbstractFieldRenderer {

    @Override
    public String getType() {
        return "formula";
    }

    @Override
    protected String renderInput(JSONObject field) {
        String key = field.getString("key");
        String formula = getStr(field, "formula", "");
        return String.format("<div class=\"formula-field\" data-field=\"%s\" data-formula=\"%s\"><span class=\"formula-value\">0</span></div>", key, formula);
    }
}
