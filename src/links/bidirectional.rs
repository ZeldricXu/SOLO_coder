use std::collections::HashMap;
use std::path::{Path, PathBuf};
use egui;
use regex::Regex;

use crate::theme::Theme;

#[derive(Debug, Clone)]
pub struct LinkInfo {
    pub source: PathBuf,
    pub target: String,
    pub target_path: Option<PathBuf>,
}

#[derive(Debug, Clone)]
pub struct Backlink {
    pub source_file: PathBuf,
    pub snippet: String,
}

#[derive(Debug, Default)]
pub struct LinkDatabase {
    pub forward_links: HashMap<PathBuf, Vec<LinkInfo>>,
    pub backlinks: HashMap<String, Vec<Backlink>>,
    pub file_to_title: HashMap<PathBuf, String>,
}

impl LinkDatabase {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn parse_links(&mut self, path: &Path, content: &str) {
        let re = Regex::new(r"\[\[([^\]]+)\]\]").unwrap();
        let title = path.file_stem()
            .and_then(|s| s.to_str())
            .unwrap_or("")
            .to_string();
        
        self.file_to_title.insert(path.to_path_buf(), title.clone());
        self.forward_links.remove(path);
        self.backlinks.values_mut().for_each(|v| {
            v.retain(|b| b.source_file != path);
        });

        for cap in re.captures_iter(content) {
            let target = cap[1].to_string();
            let link_info = LinkInfo {
                source: path.to_path_buf(),
                target: target.clone(),
                target_path: self.find_target_path(&target),
            };
            
            self.forward_links
                .entry(path.to_path_buf())
                .or_default()
                .push(link_info);

            let snippet = self.extract_snippet(content, cap.get(0).unwrap().start());
            let backlink = Backlink {
                source_file: path.to_path_buf(),
                snippet,
            };
            
            self.backlinks
                .entry(target)
                .or_default()
                .push(backlink);
        }
    }

    fn find_target_path(&self, target: &str) -> Option<PathBuf> {
        self.file_to_title
            .iter()
            .find(|(_, t)| *t == target)
            .map(|(p, _)| p.clone())
    }

    fn extract_snippet(&self, content: &str, pos: usize) -> String {
        let start = content[..pos].rfind('\n').map(|i| i + 1).unwrap_or(0);
        let end = content[pos..].find('\n').map(|i| pos + i).unwrap_or(content.len());
        content[start..end].trim().to_string()
    }

    pub fn get_backlinks(&self, file_title: &str) -> Vec<&Backlink> {
        self.backlinks
            .get(file_title)
            .map(|v| v.iter().collect())
            .unwrap_or_default()
    }

    pub fn get_forward_links(&self, path: &Path) -> Vec<&LinkInfo> {
        self.forward_links
            .get(path)
            .map(|v| v.iter().collect())
            .unwrap_or_default()
    }

    pub fn remove_file(&mut self, path: &Path) {
        self.forward_links.remove(path);
        self.file_to_title.remove(path);
        self.backlinks.values_mut().for_each(|v| {
            v.retain(|b| b.source_file != path);
        });
    }

    pub fn scan_directory(&mut self, dir: &Path) {
        if let Ok(entries) = std::fs::read_dir(dir) {
            for entry in entries.flatten() {
                let path = entry.path();
                if path.is_dir() {
                    self.scan_directory(&path);
                } else if path.extension().and_then(|s| s.to_str()) == Some("md") {
                    if let Ok(content) = std::fs::read_to_string(&path) {
                        self.parse_links(&path, &content);
                    }
                }
            }
        }
    }
}

pub fn render_backlinks_panel(
    backlinks: &[&Backlink],
    ui: &mut egui::Ui,
    styles: &Theme,
) -> Option<PathBuf> {
    let mut clicked_path = None;
    
    ui.heading("Backlinks");
    ui.separator();

    if backlinks.is_empty() {
        ui.label("No backlinks found");
        return None;
    }

    egui::ScrollArea::vertical().show(ui, |ui| {
        for backlink in backlinks {
            let filename = backlink.source_file
                .file_name()
                .and_then(|s| s.to_str())
                .unwrap_or("Unknown");
            
            if ui.add(
                egui::Label::new(egui::RichText::new(filename).color(styles.accent_color))
                    .sense(egui::Sense::click()),
            ).clicked() {
                clicked_path = Some(backlink.source_file.clone());
            }
            
            ui.label(egui::RichText::new(&backlink.snippet).small().weak());
            ui.add_space(8.0);
        }
    });

    clicked_path
}
