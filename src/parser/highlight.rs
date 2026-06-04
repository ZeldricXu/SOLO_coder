use egui::Color32;
use std::collections::HashMap;

pub struct SyntaxHighlighter {
    languages: HashMap<&'static str, ()>,
}

impl SyntaxHighlighter {
    pub fn new() -> Self {
        let mut languages = HashMap::new();
        languages.insert("rust", ());
        languages.insert("python", ());
        languages.insert("javascript", ());
        languages.insert("js", ());
        languages.insert("go", ());
        languages.insert("java", ());
        languages.insert("css", ());
        languages.insert("html", ());
        languages.insert("sql", ());
        Self { languages }
    }
}

fn is_keyword(word: &str, lang: &str) -> bool {
    let keywords: &[&str] = match lang {
        "rust" => &["fn", "let", "mut", "const", "struct", "enum", "impl", "trait", "pub", "mod", "use", "if", "else", "match", "for", "while", "loop", "return", "break", "continue", "self", "Self", "true", "false", "Option", "Result", "Some", "None", "Ok", "Err"],
        "python" => &["def", "class", "if", "elif", "else", "for", "while", "return", "import", "from", "as", "try", "except", "finally", "with", "lambda", "True", "False", "None", "and", "or", "not", "in", "is", "pass", "break", "continue"],
        "javascript" | "js" => &["function", "const", "let", "var", "if", "else", "for", "while", "return", "import", "export", "from", "class", "new", "this", "true", "false", "null", "undefined", "try", "catch", "finally", "switch", "case", "break", "continue"],
        "go" => &["func", "var", "const", "type", "struct", "interface", "package", "import", "if", "else", "for", "range", "return", "switch", "case", "break", "continue", "go", "chan", "select", "defer", "true", "false", "nil"],
        "java" => &["public", "private", "protected", "class", "interface", "extends", "implements", "static", "final", "void", "int", "String", "boolean", "if", "else", "for", "while", "return", "new", "this", "super", "true", "false", "null", "try", "catch", "finally"],
        "css" => &["color", "background", "margin", "padding", "border", "display", "flex", "grid", "position", "width", "height", "font", "text", "transform", "transition", "animation", "@media", "@keyframes", "hover", "active", "focus"],
        "html" => &["html", "head", "body", "div", "span", "p", "a", "img", "ul", "ol", "li", "table", "tr", "td", "th", "form", "input", "button", "script", "style", "link", "meta", "title"],
        "sql" => &["SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "IN", "LIKE", "ORDER", "BY", "GROUP", "HAVING", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "AS", "INSERT", "UPDATE", "DELETE", "CREATE", "TABLE", "DROP", "ALTER", "INDEX", "NULL", "TRUE", "FALSE"],
        _ => &[],
    };
    keywords.contains(&word)
}

fn is_number(s: &str) -> bool {
    s.chars().all(|c| c.is_ascii_digit() || c == '.' || c == 'x' || c == 'b' || c == 'o')
        && s.chars().any(|c| c.is_ascii_digit())
}

pub fn highlight_code(code: &str, lang: &str) -> Vec<(std::ops::Range<usize>, Color32)> {
    let mut ranges = Vec::new();
    let lang_lower = lang.to_lowercase();
    let lang = lang_lower.as_str();

    let keyword_color = Color32::from_rgb(86, 154, 214);
    let string_color = Color32::from_rgb(106, 153, 85);
    let comment_color = Color32::from_rgb(128, 128, 128);
    let function_color = Color32::from_rgb(204, 120, 204);
    let number_color = Color32::from_rgb(255, 153, 0);

    let mut chars = code.char_indices().peekable();
    let mut in_string = false;
    let mut string_start = 0;
    let mut string_quote = '\0';

    while let Some((i, c)) = chars.next() {
        if in_string {
            if c == string_quote {
                in_string = false;
                ranges.push((string_start..i + 1, string_color));
            } else if c == '\\' {
                chars.next();
            }
            continue;
        }

        if c == '"' || c == '\'' || c == '`' {
            in_string = true;
            string_start = i;
            string_quote = c;
            continue;
        }

        let comment_prefix = match lang {
            "rust" | "go" | "java" | "javascript" | "js" => "//",
            "python" => "#",
            "css" => "/*",
            "html" => "<!--",
            "sql" => "--",
            _ => "",
        };

        if !comment_prefix.is_empty() {
            let rest: String = code.chars().skip(i).take(comment_prefix.len()).collect();
            if rest == comment_prefix {
                let end = code.len();
                ranges.push((i..end, comment_color));
                break;
            }
        }

        if c.is_alphanumeric() || c == '_' {
            let mut word_end = i;
            while let Some((j, ch)) = chars.peek() {
                if ch.is_alphanumeric() || *ch == '_' {
                    word_end = *j;
                    chars.next();
                } else {
                    break;
                }
            }
            let word = &code[i..=word_end];

            if is_keyword(word, lang) {
                ranges.push((i..word_end + 1, keyword_color));
            } else if is_number(word) {
                ranges.push((i..word_end + 1, number_color));
            } else {
                if let Some((_, next_c)) = chars.peek() {
                    if *next_c == '(' {
                        ranges.push((i..word_end + 1, function_color));
                    }
                }
            }
        }
    }

    if in_string {
        ranges.push((string_start..code.len(), string_color));
    }

    ranges
}
