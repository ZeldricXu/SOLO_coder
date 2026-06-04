use egui::{RichText, Color32};
use pulldown_cmark::{Event, Parser, Options, Tag, TagEnd, CodeBlockKind};
use regex::Regex;
use crate::theme::Theme;
use crate::parser::treesitter::SyntaxHighlighter;

#[derive(Debug, Clone)]
pub enum MarkdownEvent {
    Text(String),
    BoldStart,
    BoldEnd,
    ItalicStart,
    ItalicEnd,
    Code(String),
    Link { url: String, text: String },
    Image { url: String, alt: String },
    Heading(u8),
    HeadingEnd,
    ParagraphStart,
    ParagraphEnd,
    ListStart(bool),
    ListItem,
    ListItemEnd,
    ListEnd,
    BlockQuoteStart,
    BlockQuoteEnd,
    CodeBlock { lang: String, content: String },
    TableStart,
    TableHead,
    TableRow,
    TableCell,
    TableEnd,
    HorizontalRule,
    WikiLink(String),
    MathInline(String),
    MathBlock(String),
    TaskList { checked: bool },
}

fn preprocess_wikilinks(content: &str) -> String {
    let re = Regex::new(r"\[\[([^\]]+)\]\]").unwrap();
    re.replace_all(content, "WIKILINK:$1:").to_string()
}

fn preprocess_math(content: &str) -> String {
    let re_block = Regex::new(r"\$\$([^$]+)\$\$").unwrap();
    let mut result = re_block.replace_all(content, "MATHBLOCK:$1:").to_string();
    let re_inline = Regex::new(r"\$([^$]+)\$").unwrap();
    result = re_inline.replace_all(&result, "MATHINLINE:$1:").to_string();
    result
}

pub fn parse_markdown(content: &str) -> Vec<MarkdownEvent> {
    let processed = preprocess_math(&preprocess_wikilinks(content));
    let mut options = Options::empty();
    options.insert(Options::ENABLE_TABLES);
    options.insert(Options::ENABLE_TASKLISTS);
    options.insert(Options::ENABLE_STRIKETHROUGH);
    
    let parser = Parser::new_ext(&processed, options);
    let mut events = Vec::new();
    let mut in_code_block = false;
    let mut code_block_lang = String::new();
    let mut code_block_content = String::new();
    let mut in_link = false;
    let mut link_url = String::new();
    let mut link_text = String::new();

    for event in parser {
        match event {
            Event::Start(tag) => match tag {
                Tag::Heading { level, .. } => {
                    events.push(MarkdownEvent::Heading(level as u8));
                }
                Tag::Paragraph => {
                    events.push(MarkdownEvent::ParagraphStart);
                }
                Tag::BlockQuote(_) => {
                    events.push(MarkdownEvent::BlockQuoteStart);
                }
                Tag::CodeBlock(kind) => {
                    in_code_block = true;
                    code_block_lang = match kind {
                        CodeBlockKind::Fenced(lang) => lang.to_string(),
                        CodeBlockKind::Indented => String::new(),
                    };
                    code_block_content = String::new();
                }
                Tag::List(start) => {
                    events.push(MarkdownEvent::ListStart(start.is_some()));
                }
                Tag::Item => {
                    events.push(MarkdownEvent::ListItem);
                }
                Tag::Emphasis => {
                    events.push(MarkdownEvent::ItalicStart);
                }
                Tag::Strong => {
                    events.push(MarkdownEvent::BoldStart);
                }
                Tag::Link { dest_url, .. } => {
                    in_link = true;
                    link_url = dest_url.to_string();
                    link_text = String::new();
                }
                Tag::Table(_) => {
                    events.push(MarkdownEvent::TableStart);
                }
                Tag::TableHead => {
                    events.push(MarkdownEvent::TableHead);
                }
                Tag::TableRow => {
                    events.push(MarkdownEvent::TableRow);
                }
                Tag::TableCell => {
                    events.push(MarkdownEvent::TableCell);
                }
                _ => {}
            },
            Event::End(tag) => match tag {
                TagEnd::Heading(_) => {
                    events.push(MarkdownEvent::HeadingEnd);
                }
                TagEnd::Paragraph => {
                    events.push(MarkdownEvent::ParagraphEnd);
                }
                TagEnd::BlockQuote => {
                    events.push(MarkdownEvent::BlockQuoteEnd);
                }
                TagEnd::CodeBlock => {
                    in_code_block = false;
                    events.push(MarkdownEvent::CodeBlock {
                        lang: code_block_lang.clone(),
                        content: code_block_content.clone(),
                    });
                }
                TagEnd::List(_) => {
                    events.push(MarkdownEvent::ListEnd);
                }
                TagEnd::Item => {
                    events.push(MarkdownEvent::ListItemEnd);
                }
                TagEnd::Emphasis => {
                    events.push(MarkdownEvent::ItalicEnd);
                }
                TagEnd::Strong => {
                    events.push(MarkdownEvent::BoldEnd);
                }
                TagEnd::Link => {
                    in_link = false;
                    events.push(MarkdownEvent::Link {
                        url: link_url.clone(),
                        text: link_text.clone(),
                    });
                }
                TagEnd::Table => {
                    events.push(MarkdownEvent::TableEnd);
                }
                _ => {}
            },
            Event::Text(text) => {
                let text_str = text.to_string();
                if in_code_block {
                    code_block_content.push_str(&text_str);
                } else if in_link {
                    link_text.push_str(&text_str);
                } else if text_str.starts_with("WIKILINK:") && text_str.ends_with(':') {
                    let name = &text_str["WIKILINK:".len()..text_str.len()-1];
                    events.push(MarkdownEvent::WikiLink(name.to_string()));
                } else if text_str.starts_with("MATHBLOCK:") && text_str.ends_with(':') {
                    let math = &text_str["MATHBLOCK:".len()..text_str.len()-1];
                    events.push(MarkdownEvent::MathBlock(math.to_string()));
                } else if text_str.starts_with("MATHINLINE:") && text_str.ends_with(':') {
                    let math = &text_str["MATHINLINE:".len()..text_str.len()-1];
                    events.push(MarkdownEvent::MathInline(math.to_string()));
                } else {
                    events.push(MarkdownEvent::Text(text_str));
                }
            }
            Event::Code(code) => {
                events.push(MarkdownEvent::Code(code.to_string()));
            }
            Event::TaskListMarker(checked) => {
                events.push(MarkdownEvent::TaskList { checked });
            }
            _ => {}
        }
    }

    events
}

pub fn render_to_egui(events: &[MarkdownEvent], ui: &mut egui::Ui, styles: &Theme) {
    let mut in_bold = false;
    let mut in_italic = false;
    let mut in_paragraph = false;
    let mut paragraph_text = String::new();
    let mut heading_level = 0;
    let mut in_list = false;
    let mut list_count = 0;
    let mut in_blockquote = false;
    let mut table_rows: Vec<Vec<String>> = Vec::new();
    let mut current_row: Vec<String> = Vec::new();
    let mut current_cell = String::new();
    let mut in_table_cell = false;

    for event in events {
        match event {
            MarkdownEvent::Text(text) => {
                if in_table_cell {
                    current_cell.push_str(text);
                } else if heading_level > 0 {
                    paragraph_text.push_str(text);
                } else {
                    paragraph_text.push_str(text);
                }
            }
            MarkdownEvent::BoldStart => {
                in_bold = true;
            }
            MarkdownEvent::BoldEnd => {
                in_bold = false;
            }
            MarkdownEvent::ItalicStart => {
                in_italic = true;
            }
            MarkdownEvent::ItalicEnd => {
                in_italic = false;
            }
            MarkdownEvent::Code(code) => {
                let rt = RichText::new(code)
                    .family(egui::FontFamily::Monospace)
                    .background_color(styles.code_bg)
                    .color(styles.code_text)
                    .size(styles.text_font_size * 0.9);
                ui.label(rt);
            }
            MarkdownEvent::Link { url, text } => {
                ui.hyperlink_to(text, url);
            }
            MarkdownEvent::Image { url, alt } => {
                ui.label(format!("[图片: {}] ({})", alt, url));
            }
            MarkdownEvent::Heading(level) => {
                heading_level = *level;
                paragraph_text.clear();
            }
            MarkdownEvent::HeadingEnd => {
                let size = if heading_level as usize <= styles.heading_sizes.len() {
                    styles.heading_sizes[heading_level as usize - 1]
                } else {
                    styles.text_font_size
                };
                let mut rt = RichText::new(&paragraph_text)
                    .size(size)
                    .color(styles.heading_color)
                    .strong();
                ui.label(rt);
                heading_level = 0;
                paragraph_text.clear();
            }
            MarkdownEvent::ParagraphStart => {
                in_paragraph = true;
                paragraph_text.clear();
            }
            MarkdownEvent::ParagraphEnd => {
                if !paragraph_text.is_empty() {
                    let mut rt = RichText::new(&paragraph_text)
                        .size(styles.text_font_size)
                        .color(styles.text_color);
                    if in_bold { rt = rt.strong(); }
                    if in_italic { rt = rt.italics(); }
                    if in_blockquote {
                        ui.horizontal(|ui| {
                            ui.add_space(10.0);
                            ui.label(rt);
                        });
                    } else {
                        ui.label(rt);
                    }
                }
                in_paragraph = false;
                paragraph_text.clear();
            }
            MarkdownEvent::ListStart(ordered) => {
                in_list = true;
                list_count = 0;
                if *ordered {
                    list_count = 1;
                }
            }
            MarkdownEvent::ListItem => {
                paragraph_text.clear();
            }
            MarkdownEvent::ListItemEnd => {
                let bullet = if list_count > 0 {
                    let s = format!("{}. ", list_count);
                    list_count += 1;
                    s
                } else {
                    "• ".to_string()
                };
                let mut rt = RichText::new(format!("{}{}", bullet, paragraph_text))
                    .size(styles.text_font_size)
                    .color(styles.text_color);
                if in_bold { rt = rt.strong(); }
                if in_italic { rt = rt.italics(); }
                ui.horizontal(|ui| {
                    ui.add_space(20.0);
                    ui.label(rt);
                });
                paragraph_text.clear();
            }
            MarkdownEvent::ListEnd => {
                in_list = false;
                list_count = 0;
            }
            MarkdownEvent::BlockQuoteStart => {
                in_blockquote = true;
                ui.visuals_mut().widgets.noninteractive.bg_fill = Color32::from_gray(60);
            }
            MarkdownEvent::BlockQuoteEnd => {
                in_blockquote = false;
            }
            MarkdownEvent::CodeBlock { lang, content } => {
                egui::ScrollArea::horizontal().show(ui, |ui| {
                    if !lang.is_empty() {
                        ui.label(RichText::new(lang.as_str()).small().color(styles.link_color));
                    }
                    let mut highlighter = SyntaxHighlighter::new();
                    let job = highlighter.highlight_to_layout_job(
                        content,
                        lang,
                        styles.text_font_size * 0.9,
                        styles.code_text,
                        styles,
                    );
                    ui.label(job);
                });
            }
            MarkdownEvent::TableStart => {
                table_rows.clear();
                current_row.clear();
            }
            MarkdownEvent::TableHead => {
                current_row.clear();
            }
            MarkdownEvent::TableRow => {
                if !current_row.is_empty() {
                    table_rows.push(std::mem::take(&mut current_row));
                }
                current_row.clear();
            }
            MarkdownEvent::TableCell => {
                if !current_cell.is_empty() {
                    current_row.push(std::mem::take(&mut current_cell));
                }
                in_table_cell = true;
                current_cell.clear();
            }
            MarkdownEvent::TableEnd => {
                if !current_cell.is_empty() {
                    current_row.push(std::mem::take(&mut current_cell));
                }
                if !current_row.is_empty() {
                    table_rows.push(std::mem::take(&mut current_row));
                }
                if !table_rows.is_empty() {
                    let num_cols = table_rows[0].len();
                    egui::Grid::new("markdown_table").striped(true).show(ui, |ui| {
                        for (i, row) in table_rows.iter().enumerate() {
                            for cell in row.iter().take(num_cols) {
                                let mut rt = RichText::new(cell).color(styles.text_color);
                                if i == 0 {
                                    rt = rt.strong();
                                }
                                ui.label(rt);
                            }
                            ui.end_row();
                        }
                    });
                }
                table_rows.clear();
                in_table_cell = false;
            }
            MarkdownEvent::HorizontalRule => {
                ui.separator();
            }
            MarkdownEvent::WikiLink(name) => {
                ui.link(RichText::new(name).color(styles.link_color).underline());
            }
            MarkdownEvent::MathInline(math) => {
                ui.label(crate::parser::render_math_inline(math));
            }
            MarkdownEvent::MathBlock(math) => {
                ui.horizontal(|ui| {
                    ui.add_space(ui.available_width() / 4.0);
                    crate::parser::render_math_block(math, ui);
                });
            }
            MarkdownEvent::TaskList { checked } => {
                let mut is_checked = *checked;
                ui.checkbox(&mut is_checked, "");
            }
        }
    }

    if !paragraph_text.is_empty() && heading_level == 0 {
        let mut rt = RichText::new(&paragraph_text)
            .size(styles.text_font_size)
            .color(styles.text_color);
        if in_bold { rt = rt.strong(); }
        if in_italic { rt = rt.italics(); }
        ui.label(rt);
    }
}
