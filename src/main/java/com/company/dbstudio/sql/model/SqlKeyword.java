package com.company.dbstudio.sql.model;

import java.util.Arrays;
import java.util.List;

public enum SqlKeyword {
    SELECT("SELECT", "DML", "从数据库表中检索数据"),
    INSERT("INSERT", "DML", "向表中插入新数据"),
    UPDATE("UPDATE", "DML", "更新表中的现有数据"),
    DELETE("DELETE", "DML", "从表中删除数据"),
    CREATE("CREATE", "DDL", "创建数据库对象"),
    ALTER("ALTER", "DDL", "修改数据库对象结构"),
    DROP("DROP", "DDL", "删除数据库对象"),
    TRUNCATE("TRUNCATE", "DDL", "清空表数据"),
    RENAME("RENAME", "DDL", "重命名数据库对象"),
    FROM("FROM", "CLAUSE", "指定数据源表"),
    WHERE("WHERE", "CLAUSE", "过滤条件"),
    AND("AND", "OPERATOR", "逻辑与"),
    OR("OR", "OPERATOR", "逻辑或"),
    NOT("NOT", "OPERATOR", "逻辑非"),
    IN("IN", "OPERATOR", "多值匹配"),
    LIKE("LIKE", "OPERATOR", "模式匹配"),
    BETWEEN("BETWEEN", "OPERATOR", "范围匹配"),
    IS("IS", "OPERATOR", "空值判断"),
    NULL("NULL", "LITERAL", "空值"),
    ORDER("ORDER", "CLAUSE", "排序"),
    BY("BY", "CLAUSE", "排序字段"),
    ASC("ASC", "KEYWORD", "升序"),
    DESC("DESC", "KEYWORD", "降序"),
    GROUP("GROUP", "CLAUSE", "分组"),
    HAVING("HAVING", "CLAUSE", "分组过滤"),
    JOIN("JOIN", "CLAUSE", "表连接"),
    LEFT("LEFT", "KEYWORD", "左连接"),
    RIGHT("RIGHT", "KEYWORD", "右连接"),
    INNER("INNER", "KEYWORD", "内连接"),
    OUTER("OUTER", "KEYWORD", "外连接"),
    FULL("FULL", "KEYWORD", "全连接"),
    ON("ON", "CLAUSE", "连接条件"),
    UNION("UNION", "OPERATOR", "结果集合并"),
    ALL("ALL", "KEYWORD", "全部"),
    DISTINCT("DISTINCT", "KEYWORD", "去重"),
    LIMIT("LIMIT", "CLAUSE", "限制返回行数"),
    OFFSET("OFFSET", "CLAUSE", "偏移量"),
    AS("AS", "KEYWORD", "别名"),
    COUNT("COUNT", "FUNCTION", "计数函数"),
    SUM("SUM", "FUNCTION", "求和函数"),
    AVG("AVG", "FUNCTION", "平均值函数"),
    MIN("MIN", "FUNCTION", "最小值函数"),
    MAX("MAX", "FUNCTION", "最大值函数"),
    ROUND("ROUND", "FUNCTION", "四舍五入函数"),
    CONCAT("CONCAT", "FUNCTION", "字符串连接函数"),
    SUBSTRING("SUBSTRING", "FUNCTION", "子串函数"),
    UPPER("UPPER", "FUNCTION", "转大写"),
    LOWER("LOWER", "FUNCTION", "转小写"),
    LENGTH("LENGTH", "FUNCTION", "字符串长度"),
    NOW("NOW", "FUNCTION", "当前时间"),
    DATE("DATE", "FUNCTION", "日期函数"),
    CAST("CAST", "FUNCTION", "类型转换"),
    CASE("CASE", "KEYWORD", "条件表达式开始"),
    WHEN("WHEN", "KEYWORD", "条件分支"),
    THEN("THEN", "KEYWORD", "条件结果"),
    ELSE("ELSE", "KEYWORD", "默认分支"),
    END("END", "KEYWORD", "条件表达式结束"),
    EXISTS("EXISTS", "OPERATOR", "存在性检查"),
    ANY("ANY", "KEYWORD", "任意匹配"),
    SOME("SOME", "KEYWORD", "部分匹配"),
    TRUE("TRUE", "LITERAL", "布尔真值"),
    FALSE("FALSE", "LITERAL", "布尔假值"),
    TABLE("TABLE", "DDL", "表"),
    COLUMN("COLUMN", "DDL", "列"),
    INDEX("INDEX", "DDL", "索引"),
    CONSTRAINT("CONSTRAINT", "DDL", "约束"),
    PRIMARY("PRIMARY", "KEYWORD", "主键"),
    FOREIGN("FOREIGN", "KEYWORD", "外键"),
    UNIQUE("UNIQUE", "KEYWORD", "唯一约束"),
    DEFAULT("DEFAULT", "KEYWORD", "默认值"),
    AUTO_INCREMENT("AUTO_INCREMENT", "KEYWORD", "自增"),
    REFERENCES("REFERENCES", "DDL", "外键引用"),
    VIEW("VIEW", "DDL", "视图"),
    PROCEDURE("PROCEDURE", "DDL", "存储过程"),
    FUNCTION("FUNCTION", "DDL", "函数"),
    TRIGGER("TRIGGER", "DDL", "触发器"),
    SCHEMA("SCHEMA", "DDL", "模式"),
    DATABASE("DATABASE", "DDL", "数据库"),
    USE("USE", "DCL", "切换数据库"),
    GRANT("GRANT", "DCL", "授权"),
    REVOKE("REVOKE", "DCL", "撤销权限"),
    COMMIT("COMMIT", "TCL", "提交事务"),
    ROLLBACK("ROLLBACK", "TCL", "回滚事务"),
    SAVEPOINT("SAVEPOINT", "TCL", "保存点"),
    BEGIN("BEGIN", "TCL", "开始事务"),
    TRANSACTION("TRANSACTION", "TCL", "事务"),
    EXPLAIN("EXPLAIN", "KEYWORD", "执行计划分析"),
    SHOW("SHOW", "DCL", "显示信息"),
    DESCRIBE("DESCRIBE", "DCL", "描述表结构"),
    DESC_ALIAS("DESC", "DCL", "描述表结构(缩写)"),
    SET("SET", "DCL", "设置变量"),
    VALUES("VALUES", "DML", "值列表"),
    INTO("INTO", "DML", "插入目标"),
    DUPLICATE("DUPLICATE", "KEYWORD", "重复"),
    KEY("KEY", "DDL", "键"),
    ENGINE("ENGINE", "KEYWORD", "存储引擎"),
    CHARSET("CHARSET", "KEYWORD", "字符集"),
    COLLATE("COLLATE", "KEYWORD", "排序规则"),
    COMMENT("COMMENT", "KEYWORD", "注释"),
    IF("IF", "KEYWORD", "条件判断"),
    WHILE("WHILE", "KEYWORD", "循环"),
    FOR("FOR", "KEYWORD", "循环"),
    LOOP("LOOP", "KEYWORD", "循环"),
    RETURN("RETURN", "KEYWORD", "返回"),
    DECLARE("DECLARE", "DDL", "声明变量"),
    CURSOR("CURSOR", "KEYWORD", "游标"),
    FETCH("FETCH", "KEYWORD", "获取游标数据"),
    CLOSE("CLOSE", "KEYWORD", "关闭游标"),
    OPEN("OPEN", "KEYWORD", "打开游标"),
    WITH("WITH", "CLAUSE", "CTE子句");

    private final String keyword;
    private final String category;
    private final String description;

    SqlKeyword(String keyword, String category, String description) {
        this.keyword = keyword;
        this.category = category;
        this.description = description;
    }

    public String getKeyword() {
        return keyword;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public static List<SqlKeyword> getAllKeywords() {
        return Arrays.asList(values());
    }

    public static List<String> getAllKeywordStrings() {
        return Arrays.stream(values())
                .map(SqlKeyword::getKeyword)
                .toList();
    }

    public static SqlKeyword fromString(String text) {
        for (SqlKeyword keyword : values()) {
            if (keyword.keyword.equalsIgnoreCase(text)) {
                return keyword;
            }
        }
        return null;
    }

    public boolean isDml() {
        return "DML".equals(category);
    }

    public boolean isDdl() {
        return "DDL".equals(category);
    }

    public boolean isDcl() {
        return "DCL".equals(category);
    }

    public boolean isTcl() {
        return "TCL".equals(category);
    }

    public boolean isFunction() {
        return "FUNCTION".equals(category);
    }
}
