package com.company.dbstudio.sql.ui;

import com.company.dbstudio.sql.model.SqlKeyword;
import com.company.dbstudio.sql.service.SqlParserService;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.IndexRange;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SqlCodeArea extends StackPane {
    private final CodeArea codeArea;
    private final StringProperty text;
    private final SqlParserService parserService;
    private final Set<String> keywords;
    private final Set<String> functions;
    
    private static final Pattern SQL_KEYWORDS = Pattern.compile(
            "\\b(SELECT|FROM|WHERE|AND|OR|NOT|IN|LIKE|BETWEEN|IS|NULL|ORDER|BY|ASC|DESC|" +
            "GROUP|HAVING|JOIN|LEFT|RIGHT|INNER|OUTER|FULL|ON|UNION|ALL|DISTINCT|LIMIT|" +
            "OFFSET|AS|INSERT|INTO|VALUES|UPDATE|SET|DELETE|CREATE|TABLE|COLUMN|INDEX|" +
            "PRIMARY|FOREIGN|UNIQUE|DEFAULT|REFERENCES|ALTER|DROP|TRUNCATE|RENAME|" +
            "VIEW|PROCEDURE|FUNCTION|TRIGGER|SCHEMA|DATABASE|USE|GRANT|REVOKE|COMMIT|" +
            "ROLLBACK|BEGIN|TRANSACTION|EXPLAIN|SHOW|DESCRIBE|SET|IF|WHILE|FOR|LOOP|" +
            "RETURN|DECLARE|CASE|WHEN|THEN|ELSE|END|EXISTS|WITH|TRUE|FALSE|DUPLICATE|" +
            "KEY|ENGINE|CHARSET|COLLATE|COMMENT)\\b",
            Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern SQL_FUNCTIONS = Pattern.compile(
            "\\b(COUNT|SUM|AVG|MIN|MAX|ROUND|CONCAT|SUBSTRING|UPPER|LOWER|LENGTH|" +
            "NOW|DATE|CAST|ABS|COALESCE|IFNULL|NULLIF|GREATEST|LEAST)\\b",
            Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern STRING_PATTERN = Pattern.compile("'[^']*'");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+(\\.\\d+)?\\b");
    private static final Pattern COMMENT_PATTERN = Pattern.compile("--.*|/\\*[\\s\\S]*?\\*/");
    private static final Pattern OPERATOR_PATTERN = Pattern.compile("[+\\-*/%=<>!&|]+");

    private List<String> tableNames;
    private List<String> columnNames;

    public SqlCodeArea() {
        this.parserService = SqlParserService.getInstance();
        this.keywords = new HashSet<>();
        this.functions = new HashSet<>();
        this.tableNames = new ArrayList<>();
        this.columnNames = new ArrayList<>();
        
        for (SqlKeyword kw : SqlKeyword.getAllKeywords()) {
            if (kw.isFunction()) {
                functions.add(kw.getKeyword().toUpperCase());
            } else {
                keywords.add(kw.getKeyword().toUpperCase());
            }
        }

        this.text = new SimpleStringProperty("");
        
        codeArea = new CodeArea();
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        codeArea.getStyleClass().add("sql-code-area");
        
        Font font = Font.font("Monaco", FontWeight.NORMAL, 14);
        codeArea.setFont(font);
        
        codeArea.textProperty().addListener((obs, oldText, newText) -> {
            text.set(newText);
            highlightSql();
        });
        
        codeArea.textProperty().addListener((obs, oldVal, newVal) -> {
            scheduleHighlight();
        });
        
        setupContextMenu();
        setupKeyboardShortcuts();
        
        getChildren().add(codeArea);
        getStyleClass().add("sql-code-area-container");
        
        codeArea.setStyle(
            "-fx-background-color: #1e1e1e; " +
            "-fx-text-fill: #d4d4d4; " +
            "-fx-highlight-fill: #264f78; " +
            "-fx-highlight-text-fill: #ffffff;"
        );
        
        setPrefSize(800, 400);
    }

    private void scheduleHighlight() {
        codeArea.setStyleSpans(0, computeHighlighting(codeArea.getText()));
    }

    private void highlightSql() {
        String text = codeArea.getText();
        StyleSpans<Collection<String>> spans = computeHighlighting(text);
        codeArea.setStyleSpans(0, spans);
    }

    private StyleSpans<Collection<String>> computeHighlighting(String text) {
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        int lastKwEnd = 0;
        
        List<Matcher> matchers = Arrays.asList(
                COMMENT_PATTERN.matcher(text),
                STRING_PATTERN.matcher(text),
                SQL_KEYWORDS.matcher(text),
                SQL_FUNCTIONS.matcher(text),
                NUMBER_PATTERN.matcher(text),
                OPERATOR_PATTERN.matcher(text)
        );
        
        int[] styleOrder = {1, 2, 3, 4, 5, 6};
        String[] styleClasses = {
                "comment", "string", "keyword", "function", "number", "operator"
        };
        
        TreeMap<Integer, int[]> matches = new TreeMap<>();
        
        for (int i = 0; i < matchers.size(); i++) {
            Matcher matcher = matchers.get(i);
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                int priority = styleOrder[i];
                
                int[] existing = matches.get(start);
                if (existing == null || priority < existing[1]) {
                    matches.put(start, new int[]{end, priority, i});
                }
            }
        }
        
        for (Map.Entry<Integer, int[]> entry : matches.entrySet()) {
            int start = entry.getKey();
            int[] value = entry.getValue();
            int end = value[0];
            int styleIndex = value[2];
            
            if (start > lastKwEnd) {
                spansBuilder.add(Collections.emptyList(), start - lastKwEnd);
            }
            
            spansBuilder.add(Collections.singleton(styleClasses[styleIndex]), end - start);
            lastKwEnd = end;
        }
        
        if (lastKwEnd < text.length()) {
            spansBuilder.add(Collections.emptyList(), text.length() - lastKwEnd);
        }
        
        return spansBuilder.create();
    }

    private void setupContextMenu() {
        ContextMenu contextMenu = new ContextMenu();
        
        MenuItem executeItem = new MenuItem("执行 (Ctrl+Enter)");
        executeItem.setOnAction(e -> executeCurrentStatement());
        
        MenuItem executeAllItem = new MenuItem("全部执行 (Ctrl+Shift+Enter)");
        executeAllItem.setOnAction(e -> executeAll());
        
        MenuItem explainItem = new MenuItem("执行计划 (Ctrl+E)");
        explainItem.setOnAction(e -> explainPlan());
        
        MenuItem formatItem = new MenuItem("格式化 SQL (Ctrl+Shift+F)");
        formatItem.setOnAction(e -> formatSql());
        
        MenuItem commentItem = new MenuItem("注释/取消注释 (Ctrl+/)");
        commentItem.setOnAction(e -> toggleComment());
        
        MenuItem upperItem = new MenuItem("转为大写 (Ctrl+U)");
        upperItem.setOnAction(e -> toUpperCase());
        
        MenuItem lowerItem = new MenuItem("转为小写 (Ctrl+L)");
        lowerItem.setOnAction(e -> toLowerCase());
        
        MenuItem copyItem = new MenuItem("复制 (Ctrl+C)");
        copyItem.setOnAction(e -> codeArea.copy());
        
        MenuItem pasteItem = new MenuItem("粘贴 (Ctrl+V)");
        pasteItem.setOnAction(e -> codeArea.paste());
        
        MenuItem selectAllItem = new MenuItem("全选 (Ctrl+A)");
        selectAllItem.setOnAction(e -> codeArea.selectAll());
        
        contextMenu.getItems().addAll(
                executeItem, executeAllItem, explainItem,
                new javafx.scene.control.SeparatorMenuItem(),
                formatItem, commentItem, upperItem, lowerItem,
                new javafx.scene.control.SeparatorMenuItem(),
                copyItem, pasteItem, selectAllItem
        );
        
        codeArea.setContextMenu(contextMenu);
    }

    private void setupKeyboardShortcuts() {
        codeArea.setOnKeyPressed(event -> {
            if (new KeyCodeCombination(KeyCode.ENTER, KeyCombination.CONTROL_DOWN).match(event)) {
                event.consume();
                executeCurrentStatement();
            } else if (new KeyCodeCombination(KeyCode.ENTER, 
                    KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN).match(event)) {
                event.consume();
                executeAll();
            } else if (new KeyCodeCombination(KeyCode.E, KeyCombination.CONTROL_DOWN).match(event)) {
                event.consume();
                explainPlan();
            } else if (new KeyCodeCombination(KeyCode.F, 
                    KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN).match(event)) {
                event.consume();
                formatSql();
            } else if (new KeyCodeCombination(KeyCode.SLASH, KeyCombination.CONTROL_DOWN).match(event)) {
                event.consume();
                toggleComment();
            } else if (new KeyCodeCombination(KeyCode.U, KeyCombination.CONTROL_DOWN).match(event)) {
                event.consume();
                toUpperCase();
            } else if (new KeyCodeCombination(KeyCode.L, KeyCombination.CONTROL_DOWN).match(event)) {
                event.consume();
                toLowerCase();
            } else if (event.getCode() == KeyCode.TAB) {
                event.consume();
                if (event.isShiftDown()) {
                    unindentSelection();
                } else {
                    insertTab();
                }
            }
        });
    }

    private void executeCurrentStatement() {
        fireEvent(new SqlEditorEvent(SqlEditorEvent.EXECUTE_STATEMENT, getCurrentStatement()));
    }

    private void executeAll() {
        fireEvent(new SqlEditorEvent(SqlEditorEvent.EXECUTE_ALL, getText()));
    }

    private void explainPlan() {
        fireEvent(new SqlEditorEvent(SqlEditorEvent.EXPLAIN_PLAN, getCurrentStatement()));
    }

    private void formatSql() {
        String formatted = parserService.formatSql(getText()).get(0);
        replaceText(formatted);
    }

    private void toggleComment() {
        IndexRange selection = codeArea.getSelection();
        int start = selection.getStart();
        int end = selection.getEnd();
        
        if (start == end) {
            int lineStart = codeArea.getCurrentParagraph();
            String lineText = codeArea.getParagraph(lineStart).getText();
            
            if (lineText.trim().startsWith("--")) {
                String newText = lineText.replaceFirst("^\\s*--\\s?", "");
                codeArea.replaceText(lineStart, 0, lineStart, lineText.length(), newText);
            } else {
                String newText = "-- " + lineText;
                codeArea.replaceText(lineStart, 0, lineStart, lineText.length(), newText);
            }
        }
    }

    private void toUpperCase() {
        IndexRange selection = codeArea.getSelection();
        if (selection.getLength() > 0) {
            String selected = codeArea.getSelectedText();
            codeArea.replaceSelection(selected.toUpperCase());
        }
    }

    private void toLowerCase() {
        IndexRange selection = codeArea.getSelection();
        if (selection.getLength() > 0) {
            String selected = codeArea.getSelectedText();
            codeArea.replaceSelection(selected.toLowerCase());
        }
    }

    private void insertTab() {
        codeArea.replaceSelection("    ");
    }

    private void unindentSelection() {
        String selected = codeArea.getSelectedText();
        String unindented = selected.replaceAll("(?m)^    ", "");
        codeArea.replaceSelection(unindented);
    }

    public String getCurrentStatement() {
        String text = getText();
        int caretPos = codeArea.getCaretPosition();
        
        List<String> statements = parserService.splitStatements(text);
        int currentPos = 0;
        
        for (String stmt : statements) {
            int stmtStart = text.indexOf(stmt, currentPos);
            int stmtEnd = stmtStart + stmt.length();
            
            if (caretPos >= stmtStart && caretPos <= stmtEnd) {
                return stmt;
            }
            currentPos = stmtEnd + 1;
        }
        
        return text;
    }

    public String getText() {
        return text.get();
    }

    public StringProperty textProperty() {
        return text;
    }

    public void setText(String text) {
        codeArea.replaceText(text);
    }

    public void replaceText(String text) {
        codeArea.replaceText(text);
    }

    public void appendText(String text) {
        codeArea.appendText(text);
    }

    public void insertText(int position, String text) {
        codeArea.insertText(position, text);
    }

    public void clear() {
        codeArea.clear();
    }

    public CodeArea getCodeArea() {
        return codeArea;
    }

    public List<String> getTableNames() {
        return tableNames;
    }

    public void setTableNames(List<String> tableNames) {
        this.tableNames = tableNames;
    }

    public List<String> getColumnNames() {
        return columnNames;
    }

    public void setColumnNames(List<String> columnNames) {
        this.columnNames = columnNames;
    }

    public List<String> getCompletions() {
        String currentWord = parserService.getCurrentWord(codeArea.getText(), codeArea.getCaretPosition());
        return parserService.getCompletions(currentWord, tableNames, columnNames);
    }

    public void insertCompletion(String completion) {
        String text = codeArea.getText();
        int caretPos = codeArea.getCaretPosition();
        String currentWord = parserService.getCurrentWord(text, caretPos);
        int start = caretPos - currentWord.length();
        
        codeArea.replaceText(start, caretPos, completion);
        codeArea.moveTo(start + completion.length());
    }

    public void requestFocus() {
        codeArea.requestFocus();
    }

    public static class SqlEditorEvent extends javafx.event.Event {
        public static final javafx.event.EventType<SqlEditorEvent> EXECUTE_STATEMENT = 
                new javafx.event.EventType<>(ANY, "EXECUTE_STATEMENT");
        public static final javafx.event.EventType<SqlEditorEvent> EXECUTE_ALL = 
                new javafx.event.EventType<>(ANY, "EXECUTE_ALL");
        public static final javafx.event.EventType<SqlEditorEvent> EXPLAIN_PLAN = 
                new javafx.event.EventType<>(ANY, "EXPLAIN_PLAN");
        
        private final String sql;

        public SqlEditorEvent(javafx.event.EventType<SqlEditorEvent> eventType, String sql) {
            super(eventType);
            this.sql = sql;
        }

        public String getSql() {
            return sql;
        }
    }
}
