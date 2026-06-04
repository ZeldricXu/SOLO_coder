use std::collections::HashMap;
use egui::{Color32, FontId, text::LayoutJob};
use tree_sitter::Parser;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TokenType {
    Keyword,
    String,
    Comment,
    Function,
    Number,
    Operator,
    Type,
    Variable,
    Property,
    Punctuation,
}

#[derive(Debug, Clone)]
pub struct HighlightSpan {
    pub start: usize,
    pub end: usize,
    pub token_type: TokenType,
}

const KEYWORDS: &[&str] = &[
    "if", "else", "for", "while", "return", "fn", "func", "def", "class",
    "let", "const", "var", "import", "export", "pub", "struct", "enum",
    "impl", "trait", "mod", "use", "match", "switch", "case", "try",
    "catch", "throw", "new", "self", "Self", "true", "false", "null",
    "nil", "None", "Some", "Ok", "Err", "break", "continue", "go",
    "defer", "chan", "select", "interface", "package", "type", "extends",
    "implements", "static", "final", "void", "public", "private",
    "protected", "abstract", "async", "await", "yield", "lambda", "with",
    "as", "in", "is", "not", "and", "or", "from", "raise", "except",
    "finally", "pass", "assert", "global", "nonlocal", "del",
];

pub struct SyntaxHighlighter {
    parsers: HashMap<String, Parser>,
    trees: HashMap<String, tree_sitter::Tree>,
}

impl SyntaxHighlighter {
    pub fn new() -> Self {
        Self {
            parsers: HashMap::new(),
            trees: HashMap::new(),
        }
    }

    fn get_language(&self, lang: &str) -> Option<tree_sitter::Language> {
        match lang {
            "rust" => Some(tree_sitter_rust::LANGUAGE.into()),
            "python" => Some(tree_sitter_python::LANGUAGE.into()),
            "javascript" | "js" => Some(tree_sitter_javascript::LANGUAGE.into()),
            "go" => Some(tree_sitter_go::LANGUAGE.into()),
            "java" => Some(tree_sitter_java::LANGUAGE.into()),
            "css" => Some(tree_sitter_css::LANGUAGE.into()),
            "html" => Some(tree_sitter_html::LANGUAGE.into()),
            _ => None,
        }
    }

    fn classify_node(&self, node: &tree_sitter::Node, source: &str) -> Option<TokenType> {
        let kind = node.kind();

        if node.child_count() > 0 && !is_leaf_like(kind) {
            return None;
        }

        if KEYWORDS.contains(&kind) {
            return Some(TokenType::Keyword);
        }

        if !node.is_named() {
            let text = node.utf8_text(source.as_bytes()).unwrap_or("");
            if KEYWORDS.contains(&text) {
                return Some(TokenType::Keyword);
            }
            if is_operator_text(text) {
                return Some(TokenType::Operator);
            }
            if is_punctuation_text(text) {
                return Some(TokenType::Punctuation);
            }
            return None;
        }

        if kind.contains("comment") {
            return Some(TokenType::Comment);
        }

        if kind.contains("string") || kind.contains("character") {
            return Some(TokenType::String);
        }

        if kind.contains("number") || kind.contains("float") || kind.contains("integer") {
            return Some(TokenType::Number);
        }

        if kind.contains("function") || kind.contains("call") || kind.contains("method") || kind.contains("macro") {
            if kind != "function_definition" {
                return Some(TokenType::Function);
            }
        }

        if kind.contains("type_identifier") || kind.contains("primitive_type") || kind.contains("builtin_type") || kind == "type" {
            return Some(TokenType::Type);
        }

        if kind.contains("property") || kind.contains("field") || kind.contains("attribute") {
            return Some(TokenType::Property);
        }

        if kind.contains("identifier") {
            let text = node.utf8_text(source.as_bytes()).unwrap_or("");
            if KEYWORDS.contains(&text) {
                return Some(TokenType::Keyword);
            }
            return Some(TokenType::Variable);
        }

        None
    }

    pub fn highlight(&mut self, code: &str, lang: &str) -> Vec<HighlightSpan> {
        let language = match self.get_language(lang) {
            Some(l) => l,
            None => return Vec::new(),
        };

        if !self.parsers.contains_key(lang) {
            let mut parser = Parser::new();
            parser.set_language(&language).ok();
            self.parsers.insert(lang.to_string(), parser);
        }

        let parser = self.parsers.get_mut(lang).unwrap();
        let tree = parser.parse(code, None);

        let tree = match tree {
            Some(t) => t,
            None => return Vec::new(),
        };

        self.trees.insert(lang.to_string(), tree);
        let tree = self.trees.get(lang).unwrap();

        let mut spans = Vec::new();
        self.collect_spans(tree.root_node(), code, &mut spans);

        spans.sort_by_key(|s| s.start);

        self.merge_adjacent(spans)
    }

    fn collect_spans(&self, node: tree_sitter::Node, source: &str, spans: &mut Vec<HighlightSpan>) {
        if let Some(token_type) = self.classify_node(&node, source) {
            let start = node.start_byte();
            let end = node.end_byte();
            if start < end {
                spans.push(HighlightSpan {
                    start,
                    end,
                    token_type,
                });
                return;
            }
        }
        let mut cursor = node.walk();
        if cursor.goto_first_child() {
            loop {
                self.collect_spans(cursor.node(), source, spans);
                if !cursor.goto_next_sibling() {
                    break;
                }
            }
        }
    }

    fn merge_adjacent(&self, spans: Vec<HighlightSpan>) -> Vec<HighlightSpan> {
        if spans.is_empty() {
            return spans;
        }
        let mut merged = Vec::with_capacity(spans.len());
        let mut current = spans[0].clone();
        for span in spans.iter().skip(1) {
            if span.start <= current.end && span.token_type == current.token_type {
                current.end = current.end.max(span.end);
            } else {
                merged.push(current);
                current = span.clone();
            }
        }
        merged.push(current);
        merged
    }

    pub fn highlight_to_layout_job(
        &mut self,
        code: &str,
        lang: &str,
        font_size: f32,
        default_color: Color32,
        theme: &crate::theme::Theme,
    ) -> LayoutJob {
        let spans = self.highlight(code, lang);
        let mut job = LayoutJob::default();

        if spans.is_empty() {
            job.append(code, 0.0, egui::TextFormat {
                font_id: FontId::monospace(font_size),
                color: default_color,
                ..Default::default()
            });
            return job;
        }

        let mut last_end = 0;
        for span in &spans {
            if span.start > last_end {
                let text = &code[last_end..span.start];
                job.append(text, 0.0, egui::TextFormat {
                    font_id: FontId::monospace(font_size),
                    color: default_color,
                    ..Default::default()
                });
            }

            let text = &code[span.start..span.end];
            let color = token_type_to_color(span.token_type, default_color, theme);
            job.append(text, 0.0, egui::TextFormat {
                font_id: FontId::monospace(font_size),
                color,
                ..Default::default()
            });

            last_end = span.end;
        }

        if last_end < code.len() {
            let text = &code[last_end..];
            job.append(text, 0.0, egui::TextFormat {
                font_id: FontId::monospace(font_size),
                color: default_color,
                ..Default::default()
            });
        }

        job
    }
}

fn token_type_to_color(token_type: TokenType, default_color: Color32, theme: &crate::theme::Theme) -> Color32 {
    match token_type {
        TokenType::Keyword => theme.link_color,
        TokenType::String => Color32::from_rgb(106, 153, 85),
        TokenType::Comment => Color32::from_rgb(128, 128, 128),
        TokenType::Function => Color32::from_rgb(204, 120, 204),
        TokenType::Number => Color32::from_rgb(255, 153, 0),
        TokenType::Type => Color32::from_rgb(78, 201, 176),
        TokenType::Operator => Color32::from_rgb(180, 180, 180),
        TokenType::Variable => default_color,
        TokenType::Property => default_color,
        TokenType::Punctuation => default_color,
    }
}

fn is_leaf_like(kind: &str) -> bool {
    kind.contains("identifier")
        || kind.contains("literal")
        || kind.contains("string")
        || kind.contains("comment")
        || kind.contains("number")
        || kind.contains("float")
        || kind.contains("integer")
        || kind.contains("character")
        || kind.contains("property")
        || kind.contains("field")
        || kind.contains("attribute")
        || kind.contains("type_identifier")
        || kind.contains("primitive_type")
        || kind.contains("builtin_type")
        || KEYWORDS.contains(&kind)
}

fn is_operator_text(text: &str) -> bool {
    let operators = [
        "=", "+", "-", "*", "/", "%", "&&", "||", "!", "==", "!=", "<", ">",
        "<=", ">=", "+=", "-=", "*=", "/=", "%=", "&", "|", "^", "~", "<<",
        ">>", "=>", "->", "?", ":", "::", "..", "...",
    ];
    operators.contains(&text)
}

fn is_punctuation_text(text: &str) -> bool {
    let punctuation = ["(", ")", "{", "}", "[", "]", ";", ",", "."];
    punctuation.contains(&text)
}
