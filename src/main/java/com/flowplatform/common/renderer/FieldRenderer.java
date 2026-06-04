package com.flowplatform.common.renderer;

import com.alibaba.fastjson2.JSONObject;

public interface FieldRenderer {
    String getType();
    String render(JSONObject field);
}
