package com.flowplatform.common.renderer;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

@Component
public class SelectFieldRenderer extends AbstractFieldRenderer {

    @Override
    public String getType() {
        return "select";
    }

    @Override
    protected String renderInput(JSONObject field) {
        String key = field.getString("key");
        String placeholder = getPlaceholder(field);
        boolean required = field.getBooleanValue("required");
        JSONArray options = field.getJSONArray("options");
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<select name=\"%s\"%s>\n", key, buildRequiredAttr(required)));
        sb.append(String.format("  <option value=\"\">%s</option>\n", placeholder.isEmpty() ? "请选择" : placeholder));
        if (options != null) {
            for (int i = 0; i < options.size(); i++) {
                JSONObject opt = options.getJSONObject(i);
                sb.append(String.format("  <option value=\"%s\">%s</option>\n", getStr(opt, "value", ""), getStr(opt, "label", "")));
            }
        }
        sb.append("</select>");
        return sb.toString();
    }
}
