package com.company.dbstudio.sql.highlight;

import java.util.List;
import java.util.Set;

public interface Lexer {
    List<Token> tokenize(String sql);

    Set<String> getKeywords();

    Set<String> getFunctions();

    default boolean isKeyword(String word) {
        return getKeywords().contains(word.toUpperCase());
    }

    default boolean isFunction(String word) {
        return getFunctions().contains(word.toUpperCase());
    }
}
