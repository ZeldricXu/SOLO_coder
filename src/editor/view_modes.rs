use egui::{self, ScrollArea, SidePanel, CentralPanel};
use crate::theme::Theme;
use crate::parser::{parse_markdown, render_to_egui};
use super::Editor;

pub fn render_edit_view(editor: &mut Editor, ui: &mut egui::Ui, theme: &Theme) {
    let frame = egui::Frame::none()
        .fill(theme.bg_color)
        .inner_margin(egui::Margin::same(8.0));

    frame.show(ui, |ui| {
        ScrollArea::vertical()
            .scroll_offset(egui::vec2(0.0, editor.state.scroll_offset))
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

                editor.state.scroll_offset = ui.ctx().input(|i| i.smooth_scroll_delta.y);
            });
    });
}

pub fn render_preview_view(editor: &Editor, ui: &mut egui::Ui, theme: &Theme) {
    let frame = egui::Frame::none()
        .fill(theme.bg_color)
        .inner_margin(egui::Margin::same(8.0));

    frame.show(ui, |ui| {
        ScrollArea::vertical()
            .auto_shrink([false; 2])
            .show(ui, |ui| {
                let events = parse_markdown(&editor.state.content);
                render_to_egui(&events, ui, theme);
            });
    });
}

pub fn render_split_view(editor: &mut Editor, ui: &mut egui::Ui, theme: &Theme) {
    let mut left_scroll = editor.state.scroll_offset;
    let mut right_scroll = editor.state.scroll_offset;

    SidePanel::left("edit_panel")
        .resizable(true)
        .default_width(ui.available_width() / 2.0)
        .show_inside(ui, |ui| {
            let frame = egui::Frame::none()
                .fill(theme.bg_color)
                .inner_margin(egui::Margin::same(8.0));

            frame.show(ui, |ui| {
                ScrollArea::vertical()
                    .scroll_offset(egui::vec2(0.0, left_scroll))
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

                        left_scroll = ui.ctx().input(|i| i.smooth_scroll_delta.y);
                    });
            });
        });

    CentralPanel::default().show_inside(ui, |ui| {
        let frame = egui::Frame::none()
            .fill(theme.bg_color)
            .inner_margin(egui::Margin::same(8.0));

        frame.show(ui, |ui| {
            ScrollArea::vertical()
                .scroll_offset(egui::vec2(0.0, right_scroll))
                .auto_shrink([false; 2])
                .show(ui, |ui| {
                    let events = parse_markdown(&editor.state.content);
                    render_to_egui(&events, ui, theme);

                    right_scroll = ui.ctx().input(|i| i.smooth_scroll_delta.y);
                });
            });
        });

    sync_scroll(&mut left_scroll, &mut right_scroll);
    editor.state.scroll_offset = left_scroll;
}

pub fn sync_scroll(left: &mut f32, right: &mut f32) {
    let avg = (*left + *right) / 2.0;
    *left = avg;
    *right = avg;
}
