use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::time::Instant;
use walkdir::WalkDir;

use super::watcher::{FileSystemWatcher, FileChangeEvent};

#[derive(Clone, Debug)]
pub struct FileNode {
    pub name: String,
    pub path: PathBuf,
    pub is_dir: bool,
    pub children: Vec<FileNode>,
}

#[derive(Clone, Debug)]
pub enum FileTreeAction {
    None,
    OpenFile(PathBuf),
    FilesDropped(Vec<PathBuf>),
    FileChangedExternally(PathBuf),
}

#[derive(Debug)]
pub struct FileTree {
    pub root: FileNode,
    pub selected_path: Option<PathBuf>,
    pub drag_target: Option<PathBuf>,
    pub expanded_dirs: HashSet<PathBuf>,
    hover_start: Option<(PathBuf, Instant)>,
    watcher: Option<FileSystemWatcher>,
    pending_changes: Vec<FileChangeEvent>,
}

impl FileNode {
    fn from_path(path: &Path) -> Self {
        let name = path
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("")
            .to_string();
        let is_dir = path.is_dir();
        Self {
            name,
            path: path.to_path_buf(),
            is_dir,
            children: Vec::new(),
        }
    }
}

impl FileTree {
    pub fn new(root_path: &Path) -> Self {
        let root = FileNode::from_path(root_path);
        let mut tree = Self {
            root,
            selected_path: None,
            drag_target: None,
            expanded_dirs: HashSet::new(),
            hover_start: None,
            watcher: None,
            pending_changes: Vec::new(),
        };
        tree.refresh();
        let _ = tree.start_watcher();
        tree
    }

    pub fn new_with_expanded(root_path: &Path, expanded_dirs: HashSet<PathBuf>) -> Self {
        let root = FileNode::from_path(root_path);
        let mut tree = Self {
            root,
            selected_path: None,
            drag_target: None,
            expanded_dirs,
            hover_start: None,
            watcher: None,
            pending_changes: Vec::new(),
        };
        tree.refresh();
        let _ = tree.start_watcher();
        tree
    }

    pub fn start_watcher(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        let mut watcher = FileSystemWatcher::new(&self.root.path);
        watcher.start()?;
        self.watcher = Some(watcher);
        Ok(())
    }

    pub fn stop_watcher(&mut self) {
        self.watcher = None;
    }

    pub fn check_for_changes(&mut self) -> Option<FileTreeAction> {
        let should_refresh = self.watcher.as_mut().map(|w| w.should_refresh()).unwrap_or(false);
        
        if should_refresh {
            let events = self.watcher.as_mut().map(|w| w.get_events()).unwrap_or_default();
            let mut result_action = None;
            
            for event in &events {
                match event {
                    FileChangeEvent::Modified(path) => {
                        if Some(path.clone()) == self.selected_path {
                            result_action = Some(FileTreeAction::FileChangedExternally(path.clone()));
                        }
                    }
                    _ => {}
                }
            }
            
            self.pending_changes.extend(events);
            self.refresh();
            
            if let Some(watcher) = &mut self.watcher {
                watcher.clear_events();
            }
            
            return result_action;
        }
        None
    }

    pub fn take_pending_changes(&mut self) -> Vec<FileChangeEvent> {
        std::mem::take(&mut self.pending_changes)
    }

    pub fn refresh(&mut self) {
        self.root.children.clear();
        let root_path = self.root.path.clone();
        Self::load_children_recursive(&mut self.root, &root_path);
    }

    fn load_children_recursive(node: &mut FileNode, _root_path: &Path) {
        if !node.is_dir {
            return;
        }

        let mut entries: Vec<_> = WalkDir::new(&node.path)
            .min_depth(1)
            .max_depth(1)
            .into_iter()
            .filter_map(|e| e.ok())
            .filter(|e| {
                let path = e.path();
                if path.is_dir() {
                    true
                } else {
                    path.extension()
                        .and_then(|ext| ext.to_str())
                        .map(|ext| ext.to_lowercase() == "md")
                        .unwrap_or(false)
                }
            })
            .collect();

        entries.sort_by(|a, b| {
            let a_is_dir = a.path().is_dir();
            let b_is_dir = b.path().is_dir();
            if a_is_dir != b_is_dir {
                a_is_dir.cmp(&b_is_dir).reverse()
            } else {
                a.file_name().cmp(b.file_name())
            }
        });

        for entry in entries {
            let mut child = FileNode::from_path(entry.path());
            if child.is_dir {
                Self::load_children_recursive(&mut child, _root_path);
            }
            node.children.push(child);
        }
    }

    pub fn ui(&mut self, ui: &mut egui::Ui, styles: &crate::theme::Theme) -> FileTreeAction {
        if let Some(action) = self.check_for_changes() {
            return action;
        }

        let root_path = self.root.path.clone();

        let mut dropped_files: Vec<PathBuf> = Vec::new();
        ui.input(|i| {
            for file in &i.raw.dropped_files {
                if let Some(path) = &file.path {
                    if let Some(ext) = path.extension().and_then(|e| e.to_str()) {
                        if ext.to_lowercase() == "md" {
                            dropped_files.push(path.clone());
                        }
                    }
                }
            }
        });

        if !dropped_files.is_empty() {
            let mut copied = Vec::new();
            for src in &dropped_files {
                if let Some(file_name) = src.file_name() {
                    let dest = root_path.join(file_name);
                    if src != &dest {
                        if std::fs::copy(src, &dest).is_ok() {
                            copied.push(dest);
                        }
                    } else {
                        copied.push(dest);
                    }
                }
            }
            if !copied.is_empty() {
                self.refresh();
                return FileTreeAction::FilesDropped(copied);
            }
        }

        let mut result = FileTreeAction::None;
        let mut dragged_path: Option<PathBuf> = None;
        let mut dirs_to_toggle: Vec<PathBuf> = Vec::new();
        let selected = self.selected_path.clone();

        Self::render_children(
            ui,
            &self.root,
            &root_path,
            &selected,
            &self.expanded_dirs,
            &mut self.drag_target,
            &mut dragged_path,
            &mut result,
            styles,
            &mut self.hover_start,
            &mut dirs_to_toggle,
        );

        for dir_path in dirs_to_toggle {
            self.toggle_expanded(&dir_path);
        }

        if let (Some(dragged), Some(target)) = (dragged_path, &self.drag_target) {
            if dragged != *target {
                Self::move_file(&dragged, target);
                self.refresh();
            }
        }

        self.drag_target = None;
        result
    }

    fn render_children(
        ui: &mut egui::Ui,
        node: &FileNode,
        root_path: &Path,
        selected_path: &Option<PathBuf>,
        expanded_dirs: &HashSet<PathBuf>,
        drag_target: &mut Option<PathBuf>,
        dragged_path: &mut Option<PathBuf>,
        result: &mut FileTreeAction,
        styles: &crate::theme::Theme,
        hover_start: &mut Option<(PathBuf, Instant)>,
        dirs_to_toggle: &mut Vec<PathBuf>,
    ) {
        for child in &node.children {
            Self::render_node(
                ui,
                child,
                root_path,
                selected_path,
                expanded_dirs,
                drag_target,
                dragged_path,
                result,
                styles,
                hover_start,
                dirs_to_toggle,
            );
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn render_node(
        ui: &mut egui::Ui,
        node: &FileNode,
        _root_path: &Path,
        selected_path: &Option<PathBuf>,
        expanded_dirs: &HashSet<PathBuf>,
        drag_target: &mut Option<PathBuf>,
        dragged_path: &mut Option<PathBuf>,
        result: &mut FileTreeAction,
        styles: &crate::theme::Theme,
        hover_start: &mut Option<(PathBuf, Instant)>,
        dirs_to_toggle: &mut Vec<PathBuf>,
    ) {
        let is_selected = selected_path
            .as_ref()
            .map(|p| p == &node.path)
            .unwrap_or(false);

        let is_expanded = expanded_dirs.contains(&node.path);

        let node_id = format!("file_node_{}", node.path.display());
        let mut dir_clicked = false;

        ui.scope(|ui| {
            ui.horizontal(|ui| {
                if node.is_dir {
                    let arrow = if is_expanded { "▼" } else { "▶" };
                    let arrow_resp = ui.button(arrow);
                    if arrow_resp.clicked() {
                        dir_clicked = true;
                    }
                } else {
                    ui.add_space(18.0);
                }

                let icon = if node.is_dir { "📁" } else { "📄" };
                let label = format!("{} {}", icon, node.name);

                let label_response = if is_selected {
                    ui.colored_label(styles.accent_color, label)
                } else {
                    ui.label(label)
                };

                let label_response = label_response.interact(egui::Sense::click_and_drag());

                if label_response.clicked() {
                    if node.is_dir {
                        dir_clicked = true;
                    } else {
                        *result = FileTreeAction::OpenFile(node.path.clone());
                    }
                }

                if label_response.drag_started() {
                    *dragged_path = Some(node.path.clone());
                }

                if label_response.hovered() && dragged_path.is_some() {
                    if node.is_dir {
                        *drag_target = Some(node.path.clone());
                    }
                }

                if !node.is_dir && label_response.hovered() {
                    let now = Instant::now();
                    let should_show = match hover_start {
                        Some((path, start)) if path == &node.path => {
                            now.duration_since(*start).as_millis() >= 500
                        }
                        _ => false,
                    };
                    if !matches!(hover_start, Some((p, _)) if p == &node.path) {
                        *hover_start = Some((node.path.clone(), now));
                    }
                    if should_show {
                        Self::show_file_preview(ui, &node.path, styles, &node_id);
                    }
                } else if !node.is_dir {
                    if let Some((ref path, _)) = hover_start {
                        if path == &node.path {
                            *hover_start = None;
                        }
                    }
                }
            });
        });

        if dir_clicked {
            dirs_to_toggle.push(node.path.clone());
        }

        if node.is_dir && is_expanded {
            ui.horizontal(|ui| {
                ui.add_space(20.0);
                ui.vertical(|ui| {
                    for child in &node.children {
                        Self::render_node(
                            ui,
                            child,
                            _root_path,
                            selected_path,
                            expanded_dirs,
                            drag_target,
                            dragged_path,
                            result,
                            styles,
                            hover_start,
                            dirs_to_toggle,
                        );
                    }
                });
            });
        }
    }

    fn show_file_preview(ui: &mut egui::Ui, path: &Path, styles: &crate::theme::Theme, id: &str) {
        let mut info_lines: Vec<(String, bool)> = Vec::new();

        if let Some(name) = path.file_name().and_then(|n| n.to_str()) {
            info_lines.push((format!("文件: {}", name), true));
        }

        if let Ok(metadata) = std::fs::metadata(path) {
            let size_kb = metadata.len() as f64 / 1024.0;
            info_lines.push((format!("大小: {:.1} KB", size_kb), true));
            if let Ok(modified) = metadata.modified() {
                if let Ok(duration) = modified.duration_since(std::time::UNIX_EPOCH) {
                    let secs = duration.as_secs();
                    let days = secs / 86400;
                    let hours = (secs % 86400) / 3600;
                    let mins = (secs % 3600) / 60;
                    info_lines.push((format!("修改: {}天 {}时 {}分前", days, hours, mins), true));
                }
            }
        }

        if let Ok(content) = std::fs::read_to_string(path) {
            let lines: Vec<&str> = content.lines().take(5).collect();
            if !lines.is_empty() {
                info_lines.push(("预览:".to_string(), true));
                for line in lines {
                    info_lines.push((line.to_string(), false));
                }
            }
        }

        let popup_id = egui::Id::new(id);
        let response = ui.allocate_response(egui::vec2(1.0, 1.0), egui::Sense::hover());
        egui::popup_below_widget(
            ui,
            popup_id,
            &response,
            egui::PopupCloseBehavior::CloseOnClickOutside,
            |ui| {
                ui.set_max_width(350.0);
                ui.set_min_width(200.0);
                egui::ScrollArea::vertical().max_height(200.0).show(ui, |ui| {
                    for (line, is_header) in &info_lines {
                        if *is_header {
                            ui.label(
                                egui::RichText::new(line)
                                    .size(styles.text_font_size)
                                    .color(styles.heading_color)
                                    .strong(),
                            );
                        } else {
                            ui.label(
                                egui::RichText::new(line)
                                    .size(styles.text_font_size * 0.9)
                                    .color(styles.text_color),
                            );
                        }
                    }
                });
            },
        );
    }

    fn move_file(source: &Path, target: &Path) {
        let target_dir = if target.is_dir() {
            target.to_path_buf()
        } else {
            target.parent().unwrap_or(target).to_path_buf()
        };

        if let Some(file_name) = source.file_name() {
            let dest_path = target_dir.join(file_name);
            if source != dest_path {
                let _ = std::fs::rename(source, &dest_path);
            }
        }
    }

    pub fn toggle_expanded(&mut self, path: &Path) {
        if self.expanded_dirs.contains(path) {
            self.expanded_dirs.remove(path);
        } else {
            self.expanded_dirs.insert(path.to_path_buf());
        }
    }
}
