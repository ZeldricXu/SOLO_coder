use egui::{self, Color32, FontId, TextFormat, text::LayoutJob};
use crate::theme::Theme;
use crate::parser::layout_stage::{LayoutInstruction, LayoutKind, LayoutContent, LayoutFragment, FragmentKind};
use crate::parser::treesitter::SyntaxHighlighter;

pub struct RenderStage<'a> {
    theme: &'a Theme,
    highlighter: SyntaxHighlighter,
    pub link_clicked: Option<String>,
    pub wikilink_clicked: Option<String>,
}

impl<'a> RenderStage<'a> {
    pub fn new(theme: &'a Theme) -> Self {
        Self {
            theme,
            highlighter: SyntaxHighlighter::new(),
            link_clicked: None,
            wikilink_clicked: None,
        }
    }

    pub fn render(&mut self, ui: &mut egui::Ui, instructions: &[LayoutInstruction]) {
        for instr in instructions {
            self.render_instruction(ui, instr);
        }
    }

    fn render_instruction(&mut self, ui: &mut egui::Ui, instr: &LayoutInstruction) {
        ui.add_space(instr.spacing);
        
        if instr.indent > 0.0 {
            ui.horizontal(|ui| {
                ui.add_space(instr.indent);
                ui.vertical(|ui| {
                    self.render_instruction_content(ui, instr);
                });
            });
        } else {
            self.render_instruction_content(ui, instr);
        }
    }

    fn render_instruction_content(&mut self, ui: &mut egui::Ui, instr: &LayoutInstruction) {
        match &instr.kind {
            LayoutKind::Heading { level, content } => {
                self.render_heading(ui, *level, content);
            }
            LayoutKind::Paragraph { content } => {
                self.render_paragraph(ui, content);
            }
            LayoutKind::CodeBlock { lang, content, height } => {
                self.render_code_block(ui, lang, content, *height);
            }
            LayoutKind::ListItem { ordered, number, content } => {
                self.render_list_item(ui, *ordered, *number, content);
            }
            LayoutKind::BlockQuote { content } => {
                self.render_blockquote(ui, content);
            }
            LayoutKind::Table { headers, rows, column_widths } => {
                self.render_table(ui, headers, rows, column_widths);
            }
            LayoutKind::HorizontalRule => {
                self.render_horizontal_rule(ui);
            }
            LayoutKind::MathBlock { content } => {
                self.render_math_block(ui, content);
            }
        }
    }

    fn render_heading(&mut self, ui: &mut egui::Ui, level: u8, content: &LayoutContent) {
        let level_idx = (level.saturating_sub(1) as usize).min(5);
        let font_size = self.theme.heading_sizes[level_idx];
        
        ui.horizontal(|ui| {
            ui.style_mut().override_text_style = None;
            
            let job = match content {
                LayoutContent::PlainText(text) => {
                    let mut job = LayoutJob::default();
                    job.append(text, 0.0, TextFormat {
                        font_id: FontId::proportional(font_size),
                        color: self.theme.heading_color,
                        ..Default::default()
                    });
                    job
                }
                LayoutContent::RichText(fragments) => {
                    self.build_layout_job_with_size(fragments, ui, font_size, self.theme.heading_color)
                }
                LayoutContent::Nested(_) => LayoutJob::default(),
            };
            
            ui.label(job);
        });
    }

    fn render_paragraph(&mut self, ui: &mut egui::Ui, content: &LayoutContent) {
        self.render_content(ui, content);
    }

    fn render_code_block(&mut self, ui: &mut egui::Ui, lang: &str, content: &str, height: f32) {
        let frame = egui::Frame::none()
            .fill(self.theme.code_bg)
            .inner_margin(egui::Margin::same(8.0))
            .outer_margin(egui::Margin::same(4.0))
            .rounding(4.0);

        frame.show(ui, |ui| {
            ui.set_min_height(height);
            
            let job = self.highlighter.highlight_to_layout_job(
                content,
                lang,
                self.theme.text_font_size,
                self.theme.code_text,
                self.theme,
            );
            
            egui::ScrollArea::horizontal().show(ui, |ui| {
                ui.label(job);
            });
        });
    }

    fn render_list_item(&mut self, ui: &mut egui::Ui, ordered: bool, number: Option<usize>, content: &LayoutContent) {
        ui.horizontal(|ui| {
            if ordered {
                if let Some(n) = number {
                    ui.label(format!("{}. ", n));
                }
            } else {
                ui.label("• ");
            }
            
            ui.vertical(|ui| {
                match content {
                    LayoutContent::Nested(instructions) => {
                        for instr in instructions {
                            self.render_instruction(ui, instr);
                        }
                    }
                    other => {
                        self.render_content(ui, other);
                    }
                }
            });
        });
    }

    fn render_blockquote(&mut self, ui: &mut egui::Ui, content: &LayoutContent) {
        let frame = egui::Frame::none()
            .inner_margin(egui::Margin::symmetric(12.0, 4.0))
            .outer_margin(egui::Margin::same(4.0));

        frame.show(ui, |ui| {
            ui.painter().line_segment(
                [
                    ui.min_rect().left_top() + egui::vec2(2.0, 0.0),
                    ui.min_rect().left_bottom() + egui::vec2(2.0, 0.0),
                ],
                egui::Stroke::new(4.0, self.theme.accent_color),
            );
            
            match content {
                LayoutContent::Nested(instructions) => {
                    for instr in instructions {
                        self.render_instruction(ui, instr);
                    }
                }
                other => {
                    self.render_content(ui, other);
                }
            }
        });
    }

    fn render_table(&mut self, ui: &mut egui::Ui, headers: &[LayoutContent], rows: &[Vec<LayoutContent>], widths: &[f32]) {
        egui::ScrollArea::horizontal().show(ui, |ui| {
            egui::Grid::new("markdown_table")
                .striped(true)
                .min_col_width(40.0)
                .show(ui, |ui| {
                    for (i, header) in headers.iter().enumerate() {
                        let width = widths.get(i).copied().unwrap_or(100.0);
                        ui.horizontal(|ui| {
                            ui.set_min_width(width);
                            ui.scope(|ui| {
                                ui.style_mut().visuals.override_text_color = Some(self.theme.heading_color);
                                self.render_content(ui, header);
                            });
                        });
                    }
                    ui.end_row();

                    for row in rows {
                        for (i, cell) in row.iter().enumerate() {
                            let width = widths.get(i).copied().unwrap_or(100.0);
                            ui.horizontal(|ui| {
                                ui.set_min_width(width);
                                self.render_content(ui, cell);
                            });
                        }
                        ui.end_row();
                    }
                });
        });
    }

    fn render_horizontal_rule(&mut self, ui: &mut egui::Ui) {
        ui.add_space(8.0);
        let (rect, _) = ui.allocate_exact_size(egui::vec2(ui.available_width(), 2.0), egui::Sense::hover());
        ui.painter().line_segment(
            [rect.left_center(), rect.right_center()],
            egui::Stroke::new(2.0, self.theme.border_color),
        );
        ui.add_space(8.0);
    }

    fn render_math_block(&mut self, ui: &mut egui::Ui, content: &str) {
        let frame = egui::Frame::none()
            .fill(self.theme.code_bg)
            .inner_margin(egui::Margin::same(12.0))
            .outer_margin(egui::Margin::same(4.0))
            .rounding(4.0);

        frame.show(ui, |ui| {
            ui.centered_and_justified(|ui| {
                ui.label(egui::RichText::new(content).monospace().size(self.theme.text_font_size * 1.2).color(self.theme.text_color));
            });
        });
    }

    fn render_content(&mut self, ui: &mut egui::Ui, content: &LayoutContent) -> egui::Response {
        match content {
            LayoutContent::PlainText(text) => {
                ui.label(egui::RichText::new(text).size(self.theme.text_font_size).color(self.theme.text_color))
            }
            LayoutContent::RichText(fragments) => {
                let job = self.build_layout_job(fragments, ui);
                let response = ui.label(job);
                
                self.detect_clicks(&response, fragments);
                
                response
            }
            LayoutContent::Nested(instructions) => {
                for instr in instructions {
                    self.render_instruction(ui, instr);
                }
                ui.allocate_response(egui::Vec2::ZERO, egui::Sense::hover())
            }
        }
    }

    fn detect_clicks(&mut self, response: &egui::Response, fragments: &[LayoutFragment]) {
        if response.clicked() {
            for fragment in fragments {
                match &fragment.fragment_kind {
                    FragmentKind::Link { url } => {
                        self.link_clicked = Some(url.clone());
                    }
                    FragmentKind::WikiLink { target } => {
                        self.wikilink_clicked = Some(target.clone());
                    }
                    _ => {}
                }
            }
        }
    }

    fn build_layout_job(&self, fragments: &[LayoutFragment], ui: &egui::Ui) -> LayoutJob {
        self.build_layout_job_with_size(fragments, ui, self.theme.text_font_size, self.theme.text_color)
    }

    fn build_layout_job_with_size(&self, fragments: &[LayoutFragment], _ui: &egui::Ui, font_size: f32, default_color: Color32) -> LayoutJob {
        let mut job = LayoutJob::default();
        
        for fragment in fragments {
            let format = self.fragment_text_format_with_size(&fragment.fragment_kind, font_size, default_color);
            job.append(&fragment.text, 0.0, format);
        }
        
        job
    }

    fn fragment_text_format(&self, kind: &FragmentKind) -> TextFormat {
        self.fragment_text_format_with_size(kind, self.theme.text_font_size, self.theme.text_color)
    }

    fn fragment_text_format_with_size(&self, kind: &FragmentKind, font_size: f32, default_color: Color32) -> TextFormat {
        match kind {
            FragmentKind::Normal => TextFormat {
                font_id: FontId::proportional(font_size),
                color: default_color,
                ..Default::default()
            },
            FragmentKind::Bold => TextFormat {
                font_id: FontId::proportional(font_size),
                color: default_color,
                ..Default::default()
            },
            FragmentKind::Italic => TextFormat {
                font_id: FontId::proportional(font_size),
                color: default_color,
                italics: true,
                ..Default::default()
            },
            FragmentKind::Code => TextFormat {
                font_id: FontId::monospace(font_size * 0.9),
                color: self.theme.code_text,
                background: self.theme.code_bg,
                ..Default::default()
            },
            FragmentKind::Link { .. } => TextFormat {
                font_id: FontId::proportional(font_size),
                color: self.theme.link_color,
                underline: egui::Stroke::new(1.0, self.theme.link_color),
                ..Default::default()
            },
            FragmentKind::Strikethrough => TextFormat {
                font_id: FontId::proportional(font_size),
                color: default_color,
                strikethrough: egui::Stroke::new(1.0, default_color),
                ..Default::default()
            },
            FragmentKind::WikiLink { .. } => TextFormat {
                font_id: FontId::proportional(font_size),
                color: self.theme.link_color,
                underline: egui::Stroke::new(1.0, self.theme.link_color),
                ..Default::default()
            },
            FragmentKind::MathInline { .. } => TextFormat {
                font_id: FontId::monospace(font_size),
                color: self.theme.accent_color,
                ..Default::default()
            },
            FragmentKind::TaskList { checked } => TextFormat {
                font_id: FontId::proportional(font_size),
                color: if *checked { default_color } else { default_color },
                ..Default::default()
            },
        }
    }
}
