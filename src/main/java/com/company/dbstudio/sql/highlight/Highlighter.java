package com.company.dbstudio.sql.highlight;

import org.fxmisc.richtext.model.StyleSpans;

import java.util.Collection;
import java.util.List;

public interface Highlighter {

    StyleSpans<Collection<String>> highlight(List<Token> tokens);

    String getStyleClass(TokenType type);

    void setTableNames(List<String> tableNames);

    void setColumnNames(List<String> columnNames);
}
