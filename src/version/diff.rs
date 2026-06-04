use crate::theme::Theme;
use crate::version::VersionCommit;
use egui::Color32;
use similar::{ChangeTag, TextDiff};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DiffChange {
    Added,
    Removed,
    Modified,
    Unchanged,
}

#[derive(Debug, Clone)]
pub struct DiffLine {
    pub line_number: usize,
    pub content: String,
    pub change_type: DiffChange,
}

pub fn compute_line_diff(old: &str, new: &str) -> Vec<DiffLine> {
    let diff = TextDiff::from_lines(old, new);
    let mut lines = Vec::new();
    let mut old_line_num = 1;
    let mut new_line_num = 1;

    for change in diff.iter_all_changes() {
        match change.tag() {
            ChangeTag::Delete => {
                lines.push(DiffLine {
                    line_number: old_line_num,
                    content: change.to_string(),
                    change_type: DiffChange::Removed,
                });
                old_line_num += 1;
            }
            ChangeTag::Insert => {
                lines.push(DiffLine {
                    line_number: new_line_num,
                    content: change.to_string(),
                    change_type: DiffChange::Added,
                });
                new_line_num += 1;
            }
            ChangeTag::Equal => {
                lines.push(DiffLine {
                    line_number: new_line_num,
                    content: change.to_string(),
                    change_type: DiffChange::Unchanged,
                });
                old_line_num += 1;
                new_line_num += 1;
            }
        }
    }

    lines
}

pub fn render_diff_ui(diff: &[DiffLine], ui: &mut egui::Ui, styles: &Theme) {
    egui::ScrollArea::vertical().show(ui, |ui| {
        for line in diff {
            let bg_color = match line.change_type {
                DiffChange::Added => Color32::from_rgba_unmultiplied(0, 255, 0, 30),
                DiffChange::Removed => Color32::from_rgba_unmultiplied(255, 0, 0, 30),
                DiffChange::Modified => Color32::from_rgba_unmultiplied(255, 255, 0, 30),
                DiffChange::Unchanged => styles.bg_color,
            };

            ui.horizontal(|ui| {
                let label_text = format!("{:4} ", line.line_number);
                ui.colored_label(Color32::GRAY, label_text);
                ui.add(
                    egui::Label::new(
                        egui::RichText::new(&line.content)
                            .monospace()
                            .background_color(bg_color),
                    )
                );
            });
        }
    });
}

pub fn render_version_selector(
    commits: &[VersionCommit],
    selected: &mut Vec<String>,
    ui: &mut egui::Ui,
) -> bool {
    let mut changed = false;
    ui.label("Select commits to compare (1 or 2):");
    egui::ScrollArea::vertical().max_height(200.0).show(ui, |ui| {
        for commit in commits {
            let is_selected = selected.contains(&commit.id);
            let mut is_selected_mut = is_selected;
            if ui.checkbox(&mut is_selected_mut, &commit.id).clicked() {
                if is_selected_mut {
                    if selected.len() < 2 {
                        selected.push(commit.id.clone());
                    }
                } else {
                        selected.retain(|id| id != &commit.id);
                    }
            }
            ui.label(format!("{} - {}", commit.timestamp.format("%Y-%m-%d %H:%M"), commit.message));
        }
    });
    changed
}
