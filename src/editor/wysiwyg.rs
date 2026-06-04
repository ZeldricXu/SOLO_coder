use egui::{text::LayoutJob, Color32, FontFamily, FontId, TextFormat};
use pulldown_cmark::{Event, Parser, Options, Tag, TagEnd};
use std::ops::Range;

#[derive(Debug, Clone)]
pub struct InlineStyle {
    pub range: Range<usize>,
    pub style: StyleType,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StyleType {
    Bold,
    Italic,
    Code,
    Link,
    Strikethrough,
}

#[derive(Debug, Clone)]
pub struct ParagraphAnalysis {
    pub range: Range<usize>,
    pub styles: Vec<InlineStyle>,
    pub markers: Vec<Range<usize>>,
}

#[derive(Debug, Clone)]
pub struct WysiwygEditor {
    cursor_pos: usize,
    last_content: String,
    cached_paragraphs: Vec<ParagraphAnalysis>,
    ime_in_progress: bool,
    ime_preedit_range: Option<std::ops::Range<usize>>,
}

impl Default for WysiwygEditor {
    fn default() -> Self {
        Self::new()
    }
}

impl WysiwygEditor {
    pub fn new() -> Self {
        Self {
            cursor_pos: 0,
            last_content: String::new(),
            cached_paragraphs: Vec::new(),
            ime_in_progress: false,
            ime_preedit_range: None,
        }
    }

    pub fn update(&mut self, content: &str, cursor_pos: usize, ctx: &egui::Context) {
        self.cursor_pos = cursor_pos;
        
        self.check_ime_state(ctx);
        
        if !self.ime_in_progress && content != self.last_content {
            self.analyze_content(content);
            self.last_content = content.to_string();
        }
    }

    fn check_ime_state(&mut self, ctx: &egui::Context) {
        ctx.input(|i| {
            self.ime_in_progress = false;
            self.ime_preedit_range = None;
            
            for event in &i.events {
                if let egui::Event::Ime(ime_event) = event {
                    match ime_event {
                        egui::ImeEvent::Preedit(_) => {
                            self.ime_in_progress = true;
                        }
                        egui::ImeEvent::Enabled => {
                            self.ime_in_progress = true;
                        }
                        egui::ImeEvent::Commit(_) => {
                            self.ime_in_progress = false;
                            self.ime_preedit_range = None;
                        }
                        egui::ImeEvent::Disabled => {
                            self.ime_in_progress = false;
                            self.ime_preedit_range = None;
                        }
                    }
                }
            }
        });
    }

    pub fn is_ime_in_progress(&self) -> bool {
        self.ime_in_progress
    }

    fn analyze_content(&mut self, content: &str) {
        self.cached_paragraphs.clear();
        
        let mut start = 0;
        let mut in_code_block = false;
        let mut code_block_depth = 0;
        
        for (i, c) in content.char_indices() {
            if c == '`' {
                let rest: String = content.chars().skip(i).take(3).collect();
                if rest.starts_with("```") {
                    if in_code_block {
                        code_block_depth -= 1;
                        if code_block_depth == 0 {
                            in_code_block = false;
                        }
                    } else {
                        in_code_block = true;
                        code_block_depth += 1;
                    }
                }
            }
            
            if !in_code_block && c == '\n' {
                if i + 1 < content.len() && content.chars().nth(i + 1) == Some('\n') {
                    let paragraph = Self::analyze_paragraph(content, start..i);
                    if !paragraph.range.is_empty() {
                        self.cached_paragraphs.push(paragraph);
                    }
                    start = i + 2;
                }
            }
        }
        
        if start < content.len() {
            let paragraph = Self::analyze_paragraph(content, start..content.len());
            if !paragraph.range.is_empty() {
                self.cached_paragraphs.push(paragraph);
            }
        }
    }

    fn analyze_paragraph(content: &str, range: Range<usize>) -> ParagraphAnalysis {
        let paragraph_text = &content[range.clone()];
        let mut styles = Vec::new();
        let mut markers = Vec::new();
        
        let mut options = Options::empty();
        options.insert(Options::ENABLE_STRIKETHROUGH);
        
        let parser = Parser::new_ext(paragraph_text, options);
        let mut current_pos: usize = 0;
        
        let mut style_stack: Vec<(StyleType, usize)> = Vec::new();
        
        for event in parser {
            match event {
                Event::Start(tag) => match tag {
                    Tag::Strong => {
                        style_stack.push((StyleType::Bold, current_pos));
                        markers.push(range.start + current_pos..range.start + current_pos + 2);
                        current_pos += 2;
                    }
                    Tag::Emphasis => {
                        style_stack.push((StyleType::Italic, current_pos));
                        markers.push(range.start + current_pos..range.start + current_pos + 1);
                        current_pos += 1;
                    }
                    Tag::Strikethrough => {
                        style_stack.push((StyleType::Strikethrough, current_pos));
                        markers.push(range.start + current_pos..range.start + current_pos + 2);
                        current_pos += 2;
                    }
                    Tag::Link { dest_url: _, .. } => {
                        style_stack.push((StyleType::Link, current_pos));
                        markers.push(range.start + current_pos..range.start + current_pos + 1);
                        current_pos += 1;
                    }
                    _ => {}
                },
                Event::End(tag) => match tag {
                    TagEnd::Strong => {
                        if let Some((style, start_idx)) = style_stack.pop() {
                            styles.push(InlineStyle {
                                range: range.start + start_idx + 2..range.start + current_pos,
                                style,
                            });
                            markers.push(range.start + current_pos..range.start + current_pos + 2);
                            current_pos += 2;
                        }
                    }
                    TagEnd::Emphasis => {
                        if let Some((style, start_idx)) = style_stack.pop() {
                            styles.push(InlineStyle {
                                range: range.start + start_idx + 1..range.start + current_pos,
                                style,
                            });
                            markers.push(range.start + current_pos..range.start + current_pos + 1);
                            current_pos += 1;
                        }
                    }
                    TagEnd::Strikethrough => {
                        if let Some((style, start_idx)) = style_stack.pop() {
                            styles.push(InlineStyle {
                                range: range.start + start_idx + 2..range.start + current_pos,
                                style,
                            });
                            markers.push(range.start + current_pos..range.start + current_pos + 2);
                            current_pos += 2;
                        }
                    }
                    TagEnd::Link => {
                        if let Some((style, start_idx)) = style_stack.pop() {
                            styles.push(InlineStyle {
                                range: range.start + start_idx + 1..range.start + current_pos,
                                style,
                            });
                            let url_len = paragraph_text[current_pos..].find(')').unwrap_or(0) + 1;
                            markers.push(range.start + current_pos..range.start + current_pos + url_len);
                            current_pos += url_len;
                        }
                    }
                    _ => {}
                },
                Event::Text(text) => {
                    current_pos += text.len();
                }
                Event::Code(code) => {
                    let code_start = range.start + current_pos;
                    styles.push(InlineStyle {
                        range: code_start + 1..code_start + 1 + code.len(),
                        style: StyleType::Code,
                    });
                    markers.push(code_start..code_start + 1);
                    markers.push(code_start + 1 + code.len()..code_start + 2 + code.len());
                    current_pos += code.len() + 2;
                }
                _ => {}
            }
        }
        
        ParagraphAnalysis {
            range,
            styles,
            markers,
        }
    }

    pub fn cursor_in_marker(&self) -> bool {
        for para in &self.cached_paragraphs {
            for marker in &para.markers {
                if marker.contains(&self.cursor_pos) || marker.end == self.cursor_pos {
                    return true;
                }
            }
        }
        false
    }

    pub fn get_current_paragraph(&self) -> Option<&ParagraphAnalysis> {
        for para in &self.cached_paragraphs {
            if para.range.contains(&self.cursor_pos) || para.range.end == self.cursor_pos {
                return Some(para);
            }
        }
        None
    }

    pub fn render_editable(
        &self,
        content: &str,
        ui: &mut egui::Ui,
        text_color: Color32,
        code_bg: Color32,
        link_color: Color32,
        font_size: f32,
    ) -> egui::Response {
        let show_markers = self.cursor_in_marker();
        
        if self.ime_in_progress || show_markers {
            let mut layouter = |ui: &egui::Ui, string: &str, wrap_width: f32| {
                let mut job = LayoutJob::default();
                job.wrap.max_width = wrap_width;
                
                if self.ime_in_progress {
                    job.append(
                        string,
                        0.0,
                        TextFormat {
                            font_id: FontId::new(font_size, FontFamily::Proportional),
                            color: text_color,
                            ..Default::default()
                        },
                    );
                } else {
                    job.append(
                        string,
                        0.0,
                        TextFormat {
                            font_id: FontId::new(font_size, FontFamily::Proportional),
                            color: text_color,
                            ..Default::default()
                        },
                    );
                }
                
                ui.fonts(|f| f.layout_job(job))
            };

            egui::TextEdit::multiline(&mut content.to_string())
                .font(egui::TextStyle::Body)
                .desired_width(f32::INFINITY)
                .desired_rows(20)
                .layouter(&mut layouter)
                .show(ui)
                .response
        } else {
            self.render_styled(content, ui, text_color, code_bg, link_color, font_size)
        }
    }

    fn render_styled(
        &self,
        content: &str,
        ui: &mut egui::Ui,
        text_color: Color32,
        code_bg: Color32,
        link_color: Color32,
        font_size: f32,
    ) -> egui::Response {
        let mut job = LayoutJob::default();
        let mut last_pos = 0;
        
        let mut sorted_styles: Vec<&InlineStyle> = Vec::new();
        for para in &self.cached_paragraphs {
            sorted_styles.extend(&para.styles);
        }
        sorted_styles.sort_by_key(|s| s.range.start);
        
        for style in &sorted_styles {
            if style.range.start > last_pos {
                let plain_text = &content[last_pos..style.range.start];
                if !plain_text.is_empty() {
                    job.append(
                        plain_text,
                        0.0,
                        TextFormat {
                            font_id: FontId::new(font_size, FontFamily::Proportional),
                            color: text_color,
                            ..Default::default()
                        },
                    );
                }
            }
            
            let styled_text = &content[style.range.clone()];
            let format = match style.style {
                StyleType::Bold => TextFormat {
                    font_id: FontId::new(font_size, FontFamily::Proportional),
                    color: text_color,
                    ..Default::default()
                },
                StyleType::Italic => TextFormat {
                    font_id: FontId::new(font_size, FontFamily::Proportional),
                    color: text_color,
                    ..Default::default()
                },
                StyleType::Code => TextFormat {
                    font_id: FontId::new(font_size * 0.9, FontFamily::Monospace),
                    color: text_color,
                    background: code_bg,
                    ..Default::default()
                },
                StyleType::Link => TextFormat {
                    font_id: FontId::new(font_size, FontFamily::Proportional),
                    color: link_color,
                    underline: egui::Stroke::new(1.0, link_color),
                    ..Default::default()
                },
                StyleType::Strikethrough => TextFormat {
                    font_id: FontId::new(font_size, FontFamily::Proportional),
                    color: text_color,
                    strikethrough: egui::Stroke::new(1.0, text_color),
                    ..Default::default()
                },
            };
            
            job.append(styled_text, 0.0, format);
            last_pos = style.range.end;
        }
        
        if last_pos < content.len() {
            let remaining = &content[last_pos..];
            if !remaining.is_empty() {
                job.append(
                    remaining,
                    0.0,
                    TextFormat {
                        font_id: FontId::new(font_size, FontFamily::Proportional),
                        color: text_color,
                        ..Default::default()
                    },
                );
            }
        }
        
        let response = ui.label(job);
        response.interact(egui::Sense::click())
    }
}
