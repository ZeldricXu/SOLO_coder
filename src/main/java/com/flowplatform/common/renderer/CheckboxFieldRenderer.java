package com.flowplatform.common.renderer;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

@Component
public class CheckboxFieldRenderer extends AbstractFieldRenderer {

    @Override
    public String getType() {
        return "checkbox";
    }

    @Override
    protected String renderInput(JSONObject field) {
        String key = field.getString("key");
        boolean required = field.getBooleanValue("required");
        JSONArray options = field.getJSONArray("options");
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"checkbox-group\">\n");
        if (options != null) {
            for (int i = 0; i < options.size(); i++) {
                JSONObject opt = options.getJSONObject(i);
                sb.append(String.format("  <label><input type=\"checkbox\" name=\"%s\" value=\"%s\"%s> %s</label>\n", key, getStr(opt, "value", ""), required ? " data-required=\"true\"" : "", getStr(opt, "label", "")));
            }
        }
        sb.append("</div>");
        return sb.toString();
    }
}
