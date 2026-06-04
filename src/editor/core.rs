use egui::{self, Key, Modifiers};
use crate::theme::Theme;
use crate::parser::{parse_markdown, render_to_egui};
use super::wysiwyg::WysiwygEditor;

#[derive(Debug, Clone)]
pub struct EditorState {
    pub content: String,
    pub cursor_pos: usize,
    pub selection: Option<(usize, usize)>,
    pub scroll_offset: f32,
    pub is_dirty: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ViewMode {
    EditOnly,
    PreviewOnly,
    SplitView,
    Wysiwyg,
}

#[derive(Debug, Clone)]
pub struct Editor {
    pub state: EditorState,
    pub view_mode: ViewMode,
    pub wysiwyg: WysiwygEditor,
}

impl EditorState {
    pub fn new() -> Self {
        Self {
            content: String::new(),
            cursor_pos: 0,
            selection: None,
            scroll_offset: 0.0,
            is_dirty: false,
        }
    }

    pub fn insert(&mut self, pos: usize, text: &str) {
        self.content.insert_str(pos, text);
        self.cursor_pos = pos + text.len();
        self.is_dirty = true;
    }

    pub fn delete_range(&mut self, start: usize, end: usize) {
        let end = end.min(self.content.len());
        if start < end {
            self.content.replace_range(start..end, "");
            self.cursor_pos = start;
            self.is_dirty = true;
        }
    }
}

impl Editor {
    pub fn new() -> Self {
        Self {
            state: EditorState::new(),
            view_mode: ViewMode::Wysiwyg,
            wysiwyg: WysiwygEditor::new(),
        }
    }

    pub fn set_content(&mut self, content: &str) {
        self.state.content = content.to_string();
        self.state.cursor_pos = 0;
        self.state.selection = None;
        self.state.is_dirty = false;
    }

    pub fn get_content(&self) -> &str {
        &self.state.content
    }

    pub fn ui(&mut self, ui: &mut egui::Ui, styles: &crate::theme::Theme) {
        self.handle_keyboard_shortcuts(ui);
        let ctx = ui.ctx().clone();
        self.wysiwyg.update(&self.state.content, self.state.cursor_pos, &ctx);

        match self.view_mode {
            ViewMode::EditOnly => Self::render_edit_view(self, ui, styles),
            ViewMode::PreviewOnly => Self::render_preview_view(self, ui, styles),
            ViewMode::SplitView => Self::render_split_view(self, ui, styles),
            ViewMode::Wysiwyg => Self::render_wysiwyg_view(self, ui, styles),
        }
    }

    fn render_edit_view(editor: &mut Editor, ui: &mut egui::Ui, styles: &Theme) {
        let frame = egui::Frame::none()
            .fill(styles.bg_color)
            .inner_margin(egui::Margin::same(8.0));

        frame.show(ui, |ui| {
            egui::ScrollArea::vertical()
                .auto_shrink([false; 2])
                .show(ui, |ui| {
                    let output = egui::TextEdit::multiline(&mut editor.state.content)
                        .font(egui::TextStyle::Body)
                        .desired_width(f32::INFINITY)
                        .desired_rows(20)
                        .show(ui);

                    if output.response.changed() {
                        editor.state.is_dirty = true;
                    }
                });
        });
    }

    fn render_preview_view(editor: &Editor, ui: &mut egui::Ui, styles: &Theme) {
        let frame = egui::Frame::none()
            .fill(styles.bg_color)
            .inner_margin(egui::Margin::same(8.0));

        frame.show(ui, |ui| {
            egui::ScrollArea::vertical()
                .auto_shrink([false; 2])
                .show(ui, |ui| {
                    let events = parse_markdown(&editor.state.content);
                    render_to_egui(&events, ui, styles);
                });
        });
    }

    fn render_split_view(editor: &mut Editor, ui: &mut egui::Ui, styles: &Theme) {
        let avail_width = ui.available_width();
        let panel_width = avail_width / 2.0;

        egui::SidePanel::left("edit_panel")
            .resizable(true)
            .default_width(panel_width)
            .show_inside(ui, |ui| {
                let frame = egui::Frame::none()
                    .fill(styles.bg_color)
                    .inner_margin(egui::Margin::same(8.0));

                frame.show(ui, |ui| {
                    egui::ScrollArea::vertical()
                        .auto_shrink([false; 2])
                        .show(ui, |ui| {
                            let output = egui::TextEdit::multiline(&mut editor.state.content)
                                .font(egui::TextStyle::Body)
                                .desired_width(f32::INFINITY)
                                .desired_rows(20)
                                .show(ui);

                            if output.response.changed() {
                                editor.state.is_dirty = true;
                            }
                        });
                });
            });

        egui::CentralPanel::default().show_inside(ui, |ui| {
            let frame = egui::Frame::none()
                .fill(styles.bg_color)
                .inner_margin(egui::Margin::same(8.0));

            frame.show(ui, |ui| {
                egui::ScrollArea::vertical()
                    .auto_shrink([false; 2])
                    .show(ui, |ui| {
                        let events = parse_markdown(&editor.state.content);
                        render_to_egui(&events, ui, styles);
                    });
            });
        });
    }

    fn render_wysiwyg_view(editor: &mut Editor, ui: &mut egui::Ui, styles: &Theme) {
        let frame = egui::Frame::none()
            .fill(styles.bg_color)
            .inner_margin(egui::Margin::same(8.0));

        let text_font_size = styles.text_font_size;
        let text_color = styles.text_color;
        let code_bg = styles.code_bg;

        frame.show(ui, |ui| {
            egui::ScrollArea::vertical()
                .auto_shrink([false; 2])
                .show(ui, |ui| {
                    let mut wysiwyg_layouter = move |ui: &egui::Ui, string: &str, wrap_width: f32| {
                        let mut job = egui::text::LayoutJob::default();
                        job.wrap.max_width = wrap_width;

                        let font_size = text_font_size;
                        let default_format = egui::TextFormat {
                            font_id: egui::FontId::new(font_size, egui::FontFamily::Proportional),
                            color: text_color,
                            ..Default::default()
                        };

                        let mut pos = 0usize;
                        let mut in_bold = false;
                        let mut in_italic = false;
                        let mut in_code = false;
                        let chars: Vec<char> = string.chars().collect();
                        let mut i = 0;

                        while i < chars.len() {
                            let c = chars[i];

                            if c == '`' && i + 1 < chars.len() && !in_code {
                                if pos > 0 {
                                    let segment: String = string.chars().take(pos).collect();
                                    if !segment.is_empty() {
                                        let mut fmt = default_format.clone();
                                        if in_bold { fmt.color = text_color; }
                                        if in_italic { fmt.color = text_color; }
                                        job.append(&segment, 0.0, fmt);
                                    }
                                    pos = 0;
                                }
                                in_code = !in_code;
                                i += 1;
                                pos = 0;
                                let mut code_content = String::new();
                                while i < chars.len() && chars[i] != '`' {
                                    code_content.push(chars[i]);
                                    i += 1;
                                }
                                if i < chars.len() { i += 1; }
                                in_code = false;
                                job.append(&code_content, 0.0, egui::TextFormat {
                                    font_id: egui::FontId::new(font_size * 0.9, egui::FontFamily::Monospace),
                                    color: text_color,
                                    background: code_bg,
                                    ..Default::default()
                                });
                                pos = 0;
                                continue;
                            }

                            if c == '*' && !in_code {
                                if i + 1 < chars.len() && chars[i + 1] == '*' {
                                    if pos > 0 {
                                        let segment: String = chars[i - pos..i].iter().collect();
                                        if !segment.is_empty() {
                                            let mut fmt = default_format.clone();
                                            if in_bold { fmt.color = text_color; }
                                            job.append(&segment, 0.0, fmt);
                                        }
                                        pos = 0;
                                    }
                                    in_bold = !in_bold;
                                    i += 2;
                                    continue;
                                } else {
                                    if pos > 0 {
                                        let segment: String = chars[i - pos..i].iter().collect();
                                        if !segment.is_empty() {
                                            let mut fmt = default_format.clone();
                                            job.append(&segment, 0.0, fmt);
                                        }
                                        pos = 0;
                                    }
                                    in_italic = !in_italic;
                                    i += 1;
                                    continue;
                                }
                            }

                            pos += 1;
                            i += 1;
                        }

                        if pos > 0 {
                            let segment: String = chars[chars.len() - pos..].iter().collect();
                            let mut fmt = default_format.clone();
                            if in_bold {
                                fmt.color = text_color;
                            }
                            if in_italic {
                                fmt.color = text_color;
                            }
                            job.append(&segment, 0.0, fmt);
                        }

                        ui.fonts(|f| f.layout_job(job))
                    };

                    let output = egui::TextEdit::multiline(&mut editor.state.content)
                        .font(egui::TextStyle::Body)
                        .desired_width(f32::INFINITY)
                        .desired_rows(20)
                        .layouter(&mut wysiwyg_layouter)
                        .show(ui);

                    if output.response.changed() {
                        editor.state.is_dirty = true;
                    }

                    if let Some(cursor) = output.cursor_range {
                        editor.state.cursor_pos = cursor.primary.ccursor.index;
                    }
                });
        });
    }

    fn handle_keyboard_shortcuts(&mut self, ui: &mut egui::Ui) {
        if ui.input_mut(|i| i.consume_key(Modifiers::CTRL, Key::B)) {
            self.apply_formatting("**", "**");
        }
        if ui.input_mut(|i| i.consume_key(Modifiers::CTRL, Key::I)) {
            self.apply_formatting("*", "*");
        }
    }

    fn apply_formatting(&mut self, prefix: &str, suffix: &str) {
        if let Some((start, end)) = self.state.selection {
            let (start, end) = if start <= end { (start, end) } else { (end, start) };
            let selected = &self.state.content[start..end].to_string();
            let formatted = format!("{}{}{}", prefix, selected, suffix);
            self.state.content.replace_range(start..end, &formatted);
            self.state.cursor_pos = end + prefix.len() + suffix.len();
            self.state.selection = Some((start + prefix.len(), end + prefix.len()));
            self.state.is_dirty = true;
        }
    }

    pub fn apply_format_bold(&mut self) {
        self.apply_formatting("**", "**");
    }

    pub fn apply_format_italic(&mut self) {
        self.apply_formatting("*", "*");
    }

    pub fn apply_format_code(&mut self) {
        self.apply_formatting("`", "`");
    }

    pub fn apply_format_link(&mut self) {
        self.apply_formatting("[", "](url)");
    }
}
