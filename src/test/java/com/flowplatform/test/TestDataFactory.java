package com.flowplatform.test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

public class TestDataFactory {

    public static String simpleTextSchema() {
        JSONObject schema = new JSONObject();
        schema.put("formName", "简单文本表单");
        JSONArray fields = new JSONArray();
        fields.add(createField("textInput", "field_name", "姓名", true, "请输入姓名"));
        fields.add(createField("textarea", "field_desc", "备注", false, "请输入备注"));
        fields.add(createField("email", "field_email", "邮箱", true, "请输入邮箱"));
        fields.add(createField("phone", "field_mobile", "手机号", true, "请输入手机号", "^1[3-9]\\d{9}$"));
        schema.put("fields", fields);
        return JSON.toJSONString(schema);
    }

    public static String selectSchema() {
        JSONObject schema = new JSONObject();
        schema.put("formName", "选择表单");
        JSONArray fields = new JSONArray();
        JSONObject select = createField("select", "field_dept", "所属部门", true, "请选择部门");
        select.put("options", JSON.parseArray("["
                + "{label:'技术部',value:'dev'},"
                + "{label:'市场部',value:'mkt'},"
                + "{label:'人事部',value:'hr'}]"));
        fields.add(select);
        JSONObject radio = createField("radio", "field_gender", "性别", true);
        radio.put("options", JSON.parseArray("["
                + "{label:'男',value:'male'},"
                + "{label:'女',value:'female'}]"));
        fields.add(radio);
        JSONObject checkbox = createField("checkbox", "field_hobby", "爱好", false);
        checkbox.put("options", JSON.parseArray("["
                + "{label:'阅读',value:'read'},"
                + "{label:'运动',value:'sport'},"
                + "{label:'音乐',value:'music'}]"));
        fields.add(checkbox);
        schema.put("fields", fields);
        return JSON.toJSONString(schema);
    }

    public static String dateRangeSchema() {
        JSONObject schema = new JSONObject();
        schema.put("formName", "日期范围表单");
        JSONArray fields = new JSONArray();
        JSONObject date = createField("date", "field_start", "开始日期", true);
        date.put("minDate", "2024-01-01");
        date.put("maxDate", "2024-12-31");
        fields.add(date);
        fields.add(createField("datetime", "field_time", "预约时间", true));
        fields.add(createField("dateRange", "field_period", "活动周期", true));
        schema.put("fields", fields);
        return JSON.toJSONString(schema);
    }

    public static String subTableSchema() {
        JSONObject schema = new JSONObject();
        schema.put("formName", "嵌套子表表单");
        JSONArray fields = new JSONArray();
        fields.add(createField("textInput", "field_title", "申请标题", true, "请输入标题"));
        JSONObject subTable = createField("subTable", "field_items", "明细项目", true);
        JSONArray subFields = new JSONArray();
        subFields.add(createField("textInput", "name", "物品名称", true));
        subFields.add(createField("number", "qty", "数量", true));
        subFields.add(createField("amount", "price", "单价", true));
        JSONObject formulaField = createField("formula", "total", "小计", false);
        formulaField.put("formula", "${qty} * ${price}");
        subFields.add(formulaField);
        subTable.put("subFields", subFields);
        fields.add(subTable);
        schema.put("fields", fields);
        return JSON.toJSONString(schema);
    }

    public static String formulaSumSchema() {
        JSONObject schema = new JSONObject();
        schema.put("formName", "计算公式表单");
        JSONArray fields = new JSONArray();
        fields.add(createField("number", "field_a", "数值A", true));
        fields.add(createField("number", "field_b", "数值B", true));
        JSONObject formula1 = createField("formula", "field_c", "C=A+B", false);
        formula1.put("formula", "${field_a} + ${field_b}");
        fields.add(formula1);
        fields.add(createField("number", "field_e", "数值E", true));
        fields.add(createField("number", "field_f", "数值F", true));
        JSONObject formula2 = createField("formula", "field_d", "D=E*F/100(百分比)", false);
        formula2.put("formula", "${field_e} * ${field_f} / 100");
        fields.add(formula2);
        schema.put("fields", fields);
        return JSON.toJSONString(schema);
    }

    public static String missingTypeSchema() {
        JSONObject schema = new JSONObject();
        schema.put("formName", "异常Schema-缺少type");
        JSONArray fields = new JSONArray();
        JSONObject field = new JSONObject();
        field.put("key", "field_invalid");
        field.put("label", "无效字段");
        fields.add(field);
        schema.put("fields", fields);
        return JSON.toJSONString(schema);
    }

    public static String cyclicFormulaSchema() {
        JSONObject schema = new JSONObject();
        schema.put("formName", "异常Schema-循环引用");
        JSONArray fields = new JSONArray();
        JSONObject a = createField("formula", "field_a", "A", false);
        a.put("formula", "${field_b} * 2");
        fields.add(a);
        JSONObject b = createField("formula", "field_b", "B", false);
        b.put("formula", "${field_a} + 1");
        fields.add(b);
        schema.put("fields", fields);
        return JSON.toJSONString(schema);
    }

    public static String invalidPatternSchema() {
        JSONObject schema = new JSONObject();
        schema.put("formName", "异常Schema-无效正则");
        JSONArray fields = new JSONArray();
        JSONObject field = createField("textInput", "field_test", "测试", false, "", "[invalid");
        fields.add(field);
        schema.put("fields", fields);
        return JSON.toJSONString(schema);
    }

    public static JSONObject simpleProcessDefinition() {
        JSONObject process = new JSONObject();
        process.put("processName", "简单审批流程");
        process.put("processKey", "simple_approval");
        JSONArray nodes = new JSONArray();
        nodes.add(createNode("start", "开始", "start", 200, 50));
        nodes.add(createNode("approve1", "部门审批", "approval", 200, 150));
        nodes.add(createNode("end", "结束", "end", 200, 250));
        process.put("nodes", nodes);
        JSONArray edges = new JSONArray();
        edges.add(createEdge("start", "approve1"));
        edges.add(createEdge("approve1", "end"));
        process.put("edges", edges);
        return process;
    }

    public static JSONObject conditionProcessDefinition() {
        JSONObject process = new JSONObject();
        process.put("processName", "条件分支流程");
        process.put("processKey", "condition_approval");
        JSONArray nodes = new JSONArray();
        nodes.add(createNode("start", "开始", "start", 200, 50));
        nodes.add(createNode("gateway1", "金额判断", "condition", 200, 130));
        nodes.add(createNode("approve_mgr", "部门经理审批", "approval", 80, 220));
        nodes.add(createNode("approve_leader", "直接上级审批", "approval", 320, 220));
        nodes.add(createNode("end", "结束", "end", 200, 320));
        process.put("nodes", nodes);
        JSONArray edges = new JSONArray();
        edges.add(createEdge("start", "gateway1"));
        JSONObject edge1 = createEdge("gateway1", "approve_mgr");
        edge1.put("condition", "${amount} > 1000");
        edges.add(edge1);
        JSONObject edge2 = createEdge("gateway1", "approve_leader");
        edge2.put("condition", "${amount} <= 1000");
        edges.add(edge2);
        edges.add(createEdge("approve_mgr", "end"));
        edges.add(createEdge("approve_leader", "end"));
        process.put("edges", edges);
        return process;
    }

    public static JSONObject parallelSignProcessDefinition() {
        JSONObject process = new JSONObject();
        process.put("processName", "并行会签流程");
        process.put("processKey", "parallel_sign");
        JSONArray nodes = new JSONArray();
        nodes.add(createNode("start", "开始", "start", 200, 50));
        JSONObject parallel = createNode("parallel1", "并行会签", "parallel", 200, 130);
        parallel.put("signType", "all");
        JSONArray assignees = new JSONArray();
        assignees.add(101L);
        assignees.add(102L);
        assignees.add(103L);
        parallel.put("assignees", assignees);
        nodes.add(parallel);
        nodes.add(createNode("end", "结束", "end", 200, 220));
        process.put("nodes", nodes);
        JSONArray edges = new JSONArray();
        edges.add(createEdge("start", "parallel1"));
        edges.add(createEdge("parallel1", "end"));
        process.put("edges", edges);
        return process;
    }

    public static JSONObject cycleProcessDefinition() {
        JSONObject process = new JSONObject();
        process.put("processName", "死循环流程");
        process.put("processKey", "cycle_process");
        JSONArray nodes = new JSONArray();
        nodes.add(createNode("start", "开始", "start", 200, 50));
        nodes.add(createNode("node_a", "节点A", "approval", 100, 150));
        nodes.add(createNode("node_b", "节点B", "approval", 300, 150));
        process.put("nodes", nodes);
        JSONArray edges = new JSONArray();
        edges.add(createEdge("start", "node_a"));
        edges.add(createEdge("node_a", "node_b"));
        edges.add(createEdge("node_b", "node_a"));
        process.put("edges", edges);
        return process;
    }

    private static JSONObject createField(String type, String key, String label, boolean required) {
        return createField(type, key, label, required, "");
    }

    private static JSONObject createField(String type, String key, String label, boolean required, String placeholder) {
        return createField(type, key, label, required, placeholder, "");
    }

    private static JSONObject createField(String type, String key, String label, boolean required, String placeholder, String pattern) {
        JSONObject field = new JSONObject();
        field.put("id", key + "_" + System.currentTimeMillis());
        field.put("type", type);
        field.put("key", key);
        field.put("label", label);
        field.put("required", required);
        field.put("placeholder", placeholder);
        if (!pattern.isEmpty()) {
            field.put("pattern", pattern);
        }
        return field;
    }

    private static JSONObject createNode(String id, String name, String type, int x, int y) {
        JSONObject node = new JSONObject();
        node.put("id", id);
        node.put("name", name);
        node.put("type", type);
        node.put("x", x);
        node.put("y", y);
        return node;
    }

    private static JSONObject createEdge(String source, String target) {
        JSONObject edge = new JSONObject();
        edge.put("source", source);
        edge.put("target", target);
        return edge;
    }
}
