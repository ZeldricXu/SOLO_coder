use egui;
use crate::theme::Theme;
use crate::parser::{parse_markdown, render_to_egui};

pub struct SlideShow {
    pub slides: Vec<String>,
    pub current_slide: usize,
    pub is_active: bool,
}

impl SlideShow {
    pub fn new() -> Self {
        Self {
            slides: Vec::new(),
            current_slide: 0,
            is_active: false,
        }
    }

    pub fn from_content(content: &str) -> Self {
        let mut slides: Vec<String> = Vec::new();
        let mut current = String::new();

        for line in content.lines() {
            if line.starts_with("## ") && !current.is_empty() {
                slides.push(current.trim_end().to_string());
                current.clear();
            }
            if !current.is_empty() {
                current.push('\n');
            }
            current.push_str(line);
        }

        if !current.trim().is_empty() {
            slides.push(current.trim_end().to_string());
        }

        if slides.is_empty() {
            slides.push(content.to_string());
        }

        Self {
            slides,
            current_slide: 0,
            is_active: false,
        }
    }

    pub fn render(&mut self, ctx: &egui::Context, styles: &Theme) {
        if !self.is_active {
            return;
        }

        if self.slides.is_empty() {
            self.is_active = false;
            return;
        }

        let mut deactivate = false;
        let mut go_next = false;
        let mut go_prev = false;
        let mut go_home = false;
        let mut go_end = false;

        ctx.input(|i| {
            if i.key_pressed(egui::Key::Escape) {
                deactivate = true;
            }
            if i.key_pressed(egui::Key::ArrowRight) || i.key_pressed(egui::Key::ArrowDown) || i.key_pressed(egui::Key::Space) {
                go_next = true;
            }
            if i.key_pressed(egui::Key::ArrowLeft) || i.key_pressed(egui::Key::ArrowUp) {
                go_prev = true;
            }
            if i.key_pressed(egui::Key::Home) {
                go_home = true;
            }
            if i.key_pressed(egui::Key::End) {
                go_end = true;
            }
        });

        if deactivate {
            self.is_active = false;
            return;
        }
        if go_next {
            self.next_slide();
        }
        if go_prev {
            self.prev_slide();
        }
        if go_home {
            self.current_slide = 0;
        }
        if go_end {
            self.current_slide = self.slides.len().saturating_sub(1);
        }

        let screen = ctx.screen_rect();

        let mut frame = egui::Frame::none();
        frame = frame.fill(styles.bg_color);
        frame = frame.inner_margin(egui::Margin::same(60.0));

        let panel = egui::Area::new(egui::Id::new("slideshow_overlay"))
            .order(egui::Order::Foreground)
            .fixed_pos(screen.min)
            .default_size(screen.size())
            .interactable(true);

        panel.show(ctx, |ui| {
            frame.show(ui, |ui| {
                ui.set_min_size(screen.size());

                egui::ScrollArea::vertical().show(ui, |ui| {
                    let mut slide_styles = styles.clone();
                    slide_styles.text_font_size *= 1.5;
                    slide_styles.heading_sizes = [
                        slide_styles.heading_sizes[0] * 1.5,
                        slide_styles.heading_sizes[1] * 1.5,
                        slide_styles.heading_sizes[2] * 1.5,
                        slide_styles.heading_sizes[3] * 1.5,
                        slide_styles.heading_sizes[4] * 1.5,
                        slide_styles.heading_sizes[5] * 1.5,
                    ];

                    if let Some(slide_content) = self.slides.get(self.current_slide) {
                        let events = parse_markdown(slide_content);
                        render_to_egui(&events, ui, &slide_styles);
                    }
                });
            });
        });

        let slide_num = egui::Area::new(egui::Id::new("slideshow_number"))
            .order(egui::Order::Foreground)
            .fixed_pos(egui::pos2(screen.right() - 120.0, screen.bottom() - 40.0));

        slide_num.show(ctx, |ui| {
            let label = format!("{}/{}", self.current_slide + 1, self.slides.len());
            ui.label(
                egui::RichText::new(label)
                    .size(styles.text_font_size * 1.2)
                    .color(styles.text_color),
            );
        });
    }

    pub fn next_slide(&mut self) {
        if self.current_slide < self.slides.len().saturating_sub(1) {
            self.current_slide += 1;
        }
    }

    pub fn prev_slide(&mut self) {
        if self.current_slide > 0 {
            self.current_slide -= 1;
        }
    }

    pub fn activate(&mut self, content: &str) {
        *self = Self::from_content(content);
        self.is_active = true;
    }

    pub fn deactivate(&mut self) {
        self.is_active = false;
    }
}
