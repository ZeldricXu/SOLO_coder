package com.datastandard.modules.streaming.parser;

import com.datastandard.modules.streaming.common.SqlParseUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SqlValidator {

    public boolean validate(String sql) {
        log.info("验证SQL语法");

        if (!validateBasicStructure(sql)) {
            return false;
        }

        if (!validateParentheses(sql)) {
            return false;
        }

        if (!validateWindowFunctions(sql)) {
            return false;
        }

        return true;
    }

    private boolean validateBasicStructure(String sql) {
        String upper = sql.trim().toUpperCase();

        if (!upper.startsWith("SELECT")) {
            log.warn("SQL必须以SELECT开头");
            return false;
        }

        if (!upper.contains("FROM")) {
            log.warn("SQL必须包含FROM子句");
            return false;
        }

        if (upper.contains("GROUP BY") && !upper.contains("SELECT")) {
            log.warn("GROUP BY需要配合SELECT使用");
            return false;
        }

        return true;
    }

    private boolean validateParentheses(String sql) {
        if (!SqlParseUtils.isParenthesesBalanced(sql)) {
            long openParens = countChar(sql, '(');
            long closeParens = countChar(sql, ')');
            log.warn("括号不匹配: open={}, close={}", openParens, closeParens);
            return false;
        }
        return true;
    }

    private boolean validateWindowFunctions(String sql) {
        String upper = sql.toUpperCase();

        if (upper.contains("TUMBLE") || upper.contains("HOP") || upper.contains("SESSION")) {
            if (!upper.contains("(") || !upper.contains(")")) {
                log.warn("窗口函数语法不正确");
                return false;
            }
        }

        return true;
    }

    private long countChar(String str, char c) {
        return str.chars().filter(ch -> ch == c).count();
    }
}
