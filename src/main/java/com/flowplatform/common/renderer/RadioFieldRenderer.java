package com.flowplatform.common.renderer;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

@Component
public class RadioFieldRenderer extends AbstractFieldRenderer {

    @Override
    public String getType() {
        return "radio";
    }

    @Override
    protected String renderInput(JSONObject field) {
        String key = field.getString("key");
        boolean required = field.getBooleanValue("required");
        JSONArray options = field.getJSONArray("options");
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"radio-group\">\n");
        if (options != null) {
            for (int i = 0; i < options.size(); i++) {
                JSONObject opt = options.getJSONObject(i);
                sb.append(String.format("  <label><input type=\"radio\" name=\"%s\" value=\"%s\"%s> %s</label>\n", key, getStr(opt, "value", ""), required && i == 0 ? " required" : "", getStr(opt, "label", "")));
            }
        }
        sb.append("</div>");
        return sb.toString();
    }
}
