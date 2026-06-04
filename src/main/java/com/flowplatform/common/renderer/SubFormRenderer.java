package com.flowplatform.common.renderer;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

@Component
public class SubFormRenderer extends AbstractFieldRenderer {

    @Override
    public String getType() {
        return "subTable";
    }

    @Override
    protected String renderInput(JSONObject field) {
        String key = field.getString("key");
        JSONArray subFields = field.getJSONArray("subFields");
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<div class=\"sub-table\" data-table=\"%s\">\n", key));
        sb.append("  <table>\n    <thead><tr>\n");
        if (subFields != null) {
            for (int i = 0; i < subFields.size(); i++) {
                JSONObject sf = subFields.getJSONObject(i);
                sb.append(String.format("      <th>%s</th>\n", getStr(sf, "label", "")));
            }
        }
        sb.append("      <th>操作</th>\n    </tr></thead>\n    <tbody></tbody>\n  </table>\n");
        sb.append("  <button type=\"button\" class=\"add-row-btn\">+ 添加行</button>\n");
        sb.append("</div>");
        return sb.toString();
    }
}
