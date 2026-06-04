use std::path::{Path, PathBuf};
use std::fs;

use crate::editor::{Editor, SlideShow};
use crate::file_manager::{FileTree, FileTreeAction, SearchEngine};
use crate::links::{LinkDatabase, KnowledgeGraph, render_graph};
use crate::theme::{Theme, ThemePreset};
use crate::version::{GitBackend, VersionCommit, compute_line_diff, render_diff_ui, render_version_selector};
use crate::export::{HtmlExporter, PdfExporter, DocxExporter, copy_rich_text, ExportContext};

pub struct MarkNoteApp {
    notebook_path: Option<PathBuf>,
    current_file: Option<PathBuf>,
    editor: Editor,
    file_tree: Option<FileTree>,
    search_engine: Option<SearchEngine>,
    link_database: LinkDatabase,
    knowledge_graph: Option<KnowledgeGraph>,
    theme: Theme,
    current_preset: ThemePreset,
    git_backend: Option<GitBackend>,
    version_history: Vec<VersionCommit>,
    selected_versions: Vec<String>,
    show_graph: bool,
    show_backlinks: bool,
    show_version_history: bool,
    search_query: String,
    search_results: Vec<crate::file_manager::SearchResult>,
    recent_files: Vec<PathBuf>,
    show_export_menu: bool,
    show_settings: bool,
    sidebar_width: f32,
    right_panel_width: f32,
    slideshow: SlideShow,
    export_context: Option<ExportContext>,
    show_external_file_change_notification: bool,
    externally_changed_file: Option<PathBuf>,
}

impl Default for MarkNoteApp {
    fn default() -> Self {
        Self {
            notebook_path: None,
            current_file: None,
            editor: Editor::new(),
            file_tree: None,
            search_engine: None,
            link_database: LinkDatabase::new(),
            knowledge_graph: None,
            theme: Theme::from_preset(ThemePreset::Dark),
            current_preset: ThemePreset::Dark,
            git_backend: None,
            version_history: Vec::new(),
            selected_versions: Vec::new(),
            show_graph: false,
            show_backlinks: true,
            show_version_history: false,
            search_query: String::new(),
            search_results: Vec::new(),
            recent_files: Vec::new(),
            show_export_menu: false,
            show_settings: false,
            sidebar_width: 250.0,
            right_panel_width: 300.0,
            slideshow: SlideShow::new(),
            export_context: None,
            show_external_file_change_notification: false,
            externally_changed_file: None,
        }
    }
}

impl MarkNoteApp {
    pub fn open_notebook(&mut self, path: &Path) -> Result<(), Box<dyn std::error::Error>> {
        let notebook_dir = path.join(".notebook");
        fs::create_dir_all(&notebook_dir)?;
        
        let index_dir = notebook_dir.join("index");
        let custom_theme_path = notebook_dir.join("custom_theme.toml");
        
        self.theme = if custom_theme_path.exists() {
            Theme::load_custom_or_fallback(&custom_theme_path)
        } else {
            Theme::from_preset(self.current_preset)
        };
        
        self.notebook_path = Some(path.to_path_buf());
        self.file_tree = Some(FileTree::new_with_expanded(path, std::collections::HashSet::new()));
        self.search_engine = Some(SearchEngine::new(&index_dir, path)?);
        self.git_backend = Some(GitBackend::ensure_repo(path)?);
        
        self.refresh_notebook()?;
        
        Ok(())
    }
    
    fn refresh_notebook(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        if let Some(ft) = &mut self.file_tree {
            ft.refresh();
        }
        
        if let Some(se) = &mut self.search_engine {
            se.start_background_index();
        }
        
        if let Some(gb) = &self.git_backend {
            self.version_history = gb.get_history(100)?;
        }
        
        self.scan_links()?;
        
        self.knowledge_graph = Some(KnowledgeGraph::from_database(&self.link_database));
        
        Ok(())
    }
    
    fn scan_links(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        if let Some(nb) = &self.notebook_path {
            self.link_database.scan_directory(nb);
        }
        Ok(())
    }
    
    pub fn open_file(&mut self, path: &Path) -> Result<(), Box<dyn std::error::Error>> {
        let content = fs::read_to_string(path)?;
        self.editor.set_content(&content);
        self.current_file = Some(path.to_path_buf());
        
        if !self.recent_files.iter().any(|p| p == path) {
            self.recent_files.insert(0, path.to_path_buf());
            if self.recent_files.len() > 20 {
                self.recent_files.pop();
            }
        }
        
        if let Some(se) = &mut self.search_engine {
            se.index_file(path, &content)?;
        }
        
        self.link_database.parse_links(path, &content);
        self.knowledge_graph = Some(KnowledgeGraph::from_database(&self.link_database));
        
        Ok(())
    }
    
    pub fn save_file(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        if let Some(path) = &self.current_file {
            let content = self.editor.get_content();
            fs::write(path, content)?;
            
            if let Some(se) = &mut self.search_engine {
                se.index_file(path, content)?;
            }
            
            if let Some(gb) = &mut self.git_backend {
                let file_name = path.file_name()
                    .and_then(|n| n.to_str())
                    .unwrap_or("untitled");
                gb.commit(&format!("Save: {}", file_name))?;
                self.version_history = gb.get_history(100)?;
            }
            
            self.link_database.parse_links(path, content);
            self.knowledge_graph = Some(KnowledgeGraph::from_database(&self.link_database));
            
            self.editor.state.is_dirty = false;
        }
        Ok(())
    }
    
    fn render_menu_bar(&mut self, ctx: &egui::Context) {
        egui::TopBottomPanel::top("menu_bar").show(ctx, |ui| {
            ui.horizontal(|ui| {
                egui::menu::menu_button(ui, "文件", |ui| {
                    if ui.button("打开笔记本...").clicked() {
                        if let Some(path) = rfd::FileDialog::new().pick_folder() {
                            let _ = self.open_notebook(&path);
                        }
                    }
                    
                    if ui.button("新建笔记").clicked() {
                        if let Some(nb) = &self.notebook_path {
                            let new_path = nb.join("未命名笔记.md");
                            let _ = fs::write(&new_path, "# 新笔记\n\n");
                            let _ = self.open_file(&new_path);
                            let _ = self.refresh_notebook();
                        }
                    }
                    
                    ui.separator();
                    
                    if ui.button("保存").clicked() {
                        let _ = self.save_file();
                    }
                    
                    ui.separator();
                    
                    ui.menu_button("导出", |ui| {
                        if ui.button("导出为 HTML").clicked() {
                            if let (Some(path), Some(cf)) = (&self.notebook_path, &self.current_file) {
                                let stem = cf.file_stem().and_then(|s| s.to_str()).unwrap_or("export");
                                let out = path.join(format!("{}.html", stem));
                                let exporter = HtmlExporter::new(&self.theme);
                                self.export_context = Some(ExportContext::new(
                                    self.editor.get_content().to_string(),
                                    out,
                                    Box::new(exporter),
                                ));
                                if let Some(ctx) = &mut self.export_context {
                                    ctx.start();
                                }
                            }
                        }
                        
                        if ui.button("导出为 PDF").clicked() {
                            if let (Some(path), Some(cf)) = (&self.notebook_path, &self.current_file) {
                                let stem = cf.file_stem().and_then(|s| s.to_str()).unwrap_or("export");
                                let out = path.join(format!("{}.pdf", stem));
                                let exporter = PdfExporter::new(&self.theme);
                                self.export_context = Some(ExportContext::new(
                                    self.editor.get_content().to_string(),
                                    out,
                                    Box::new(exporter),
                                ));
                                if let Some(ctx) = &mut self.export_context {
                                    ctx.start();
                                }
                            }
                        }
                        
                        if ui.button("导出为 DOCX").clicked() {
                            if let (Some(path), Some(cf)) = (&self.notebook_path, &self.current_file) {
                                let stem = cf.file_stem().and_then(|s| s.to_str()).unwrap_or("export");
                                let out = path.join(format!("{}.docx", stem));
                                let exporter = DocxExporter::new();
                                self.export_context = Some(ExportContext::new(
                                    self.editor.get_content().to_string(),
                                    out,
                                    Box::new(exporter),
                                ));
                                if let Some(ctx) = &mut self.export_context {
                                    ctx.start();
                                }
                            }
                        }
                        
                        if ui.button("复制富文本").clicked() {
                            let _ = copy_rich_text(self.editor.get_content());
                        }
                    });
                });
                
                egui::menu::menu_button(ui, "编辑", |ui| {
                    if ui.button("粗体 Ctrl+B").clicked() {
                        self.editor.apply_format_bold();
                    }
                    if ui.button("斜体 Ctrl+I").clicked() {
                        self.editor.apply_format_italic();
                    }
                    if ui.button("代码").clicked() {
                        self.editor.apply_format_code();
                    }
                    if ui.button("链接").clicked() {
                        self.editor.apply_format_link();
                    }
                });
                
                egui::menu::menu_button(ui, "视图", |ui| {
                    if ui.button("纯编辑").clicked() {
                        self.editor.view_mode = crate::editor::ViewMode::EditOnly;
                    }
                    if ui.button("纯预览").clicked() {
                        self.editor.view_mode = crate::editor::ViewMode::PreviewOnly;
                    }
                    if ui.button("分屏").clicked() {
                        self.editor.view_mode = crate::editor::ViewMode::SplitView;
                    }
                    if ui.button("所见即所得").clicked() {
                        self.editor.view_mode = crate::editor::ViewMode::Wysiwyg;
                    }
                    
                    ui.separator();
                    
                    ui.checkbox(&mut self.show_backlinks, "反向链接面板");
                    ui.checkbox(&mut self.show_graph, "知识图谱");
                    ui.checkbox(&mut self.show_version_history, "版本历史");
                    
                    ui.separator();
                    
                    if ui.button("幻灯片演示").clicked() {
                        self.slideshow.activate(self.editor.get_content());
                    }
                });
                
                egui::menu::menu_button(ui, "主题", |ui| {
                    if ui.button("亮色").clicked() {
                        self.current_preset = ThemePreset::Light;
                        self.theme = Theme::from_preset(ThemePreset::Light);
                    }
                    if ui.button("暗色").clicked() {
                        self.current_preset = ThemePreset::Dark;
                        self.theme = Theme::from_preset(ThemePreset::Dark);
                    }
                    if ui.button("高对比度").clicked() {
                        self.current_preset = ThemePreset::HighContrast;
                        self.theme = Theme::from_preset(ThemePreset::HighContrast);
                    }
                    
                    ui.separator();
                    
                    if ui.button("自定义主题").clicked() {
                        if let Some(nb) = &self.notebook_path {
                            let custom_path = nb.join(".notebook").join("custom_theme.toml");
                            let _ = self.theme.save_to_file(&custom_path);
                        }
                    }
                    
                    ui.separator();
                    
                    if ui.button("设置").clicked() {
                        self.show_settings = !self.show_settings;
                    }
                });
                
                egui::menu::menu_button(ui, "搜索", |ui| {
                    ui.text_edit_singleline(&mut self.search_query);
                    if ui.button("搜索").clicked() {
                        if let Some(se) = &self.search_engine {
                            if let Ok(results) = se.search(&self.search_query, 50) {
                                self.search_results = results;
                            }
                        }
                    }
                });
                
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if let Some(cf) = &self.current_file {
                        ui.label(format!("{}", cf.display()));
                    }
                });
            });
        });
    }
    
    fn render_export_progress(&mut self, ctx: &egui::Context) {
        let mut should_close = false;
        
        if let Some(export_ctx) = &mut self.export_context {
            if export_ctx.is_running {
                export_ctx.run_export();
            }
            
            let error = export_ctx.error.clone();
            let completed = export_ctx.completed;
            let output_path = export_ctx.output_path.clone();
            let progress_render = export_ctx.progress.clone();
            
            egui::Window::new("导出进度")
                .collapsible(false)
                .resizable(false)
                .anchor(egui::Align2::CENTER_CENTER, egui::vec2(0.0, 0.0))
                .show(ctx, |ui| {
                    ui.vertical(|ui| {
                        if let Some(err) = &error {
                            ui.colored_label(egui::Color32::RED, err);
                        } else if completed {
                            ui.colored_label(egui::Color32::GREEN, "导出完成!");
                            ui.label(format!("文件: {}", output_path.display()));
                        } else {
                            progress_render.render(ui);
                        }
                    });
                    
                    ui.separator();
                    ui.horizontal(|ui| {
                        if ui.button("关闭").clicked() {
                            should_close = true;
                        }
                    });
                });
        }
        
        if should_close {
            self.export_context = None;
        }
    }

    fn render_external_file_change_notification(&mut self, ctx: &egui::Context) {
        let mut action = None;
        
        if self.show_external_file_change_notification {
            if let Some(changed_file) = &self.externally_changed_file {
                let file_name = changed_file.file_name()
                    .and_then(|n| n.to_str())
                    .unwrap_or("");
                let path_to_reload = changed_file.clone();
                
                egui::Window::new("文件已外部修改")
                    .collapsible(false)
                    .resizable(false)
                    .anchor(egui::Align2::CENTER_CENTER, egui::vec2(0.0, 0.0))
                    .show(ctx, |ui| {
                        ui.label(format!("文件已在外部被修改: {}", file_name));
                        ui.label("是否重新加载?");
                        ui.separator();
                        ui.horizontal(|ui| {
                            if ui.button("重新加载").clicked() {
                                action = Some(Some(path_to_reload));
                            }
                            if ui.button("忽略").clicked() {
                                action = Some(None);
                            }
                        });
                    });
            }
        }
        
        if let Some(reload_path) = action {
            self.show_external_file_change_notification = false;
            self.externally_changed_file = None;
            if let Some(path) = reload_path {
                let _ = self.open_file(&path);
            }
        }
    }
    
    fn render_sidebar(&mut self, ctx: &egui::Context) {
        egui::SidePanel::left("file_sidebar")
            .default_width(self.sidebar_width)
            .resizable(true)
            .show(ctx, |ui| {
                ui.vertical(|ui| {
                    ui.heading("文件");
                    ui.separator();
                    
                    if self.notebook_path.is_none() {
                        ui.label("请打开一个笔记本");
                        if ui.button("打开笔记本...").clicked() {
                            if let Some(path) = rfd::FileDialog::new().pick_folder() {
                                let _ = self.open_notebook(&path);
                            }
                        }
                        return;
                    }
                    
                    if let Some(ft) = &mut self.file_tree {
                        match ft.ui(ui, &self.theme) {
                            FileTreeAction::OpenFile(path) => {
                                if path.is_file() {
                                    let _ = self.open_file(&path);
                                }
                            }
                            FileTreeAction::FilesDropped(_paths) => {
                                let _ = self.refresh_notebook();
                            }
                            FileTreeAction::FileChangedExternally(path) => {
                                self.show_external_file_change_notification = true;
                                self.externally_changed_file = Some(path);
                            }
                            FileTreeAction::None => {}
                        }
                    }
                    
                    ui.separator();
                    
                    ui.heading("最近文件");
                    let mut to_open: Option<PathBuf> = None;
                    for rf in &self.recent_files {
                        if ui.button(rf.file_name().and_then(|n| n.to_str()).unwrap_or("")).clicked() {
                            to_open = Some(rf.clone());
                        }
                    }
                    if let Some(path) = to_open {
                        let _ = self.open_file(&path);
                    }
                    
                    if !self.search_results.is_empty() {
                        ui.separator();
                        ui.heading("搜索结果");
                        let mut to_open_search: Option<PathBuf> = None;
                        for sr in &self.search_results {
                            if ui.button(sr.file_path.file_name().and_then(|n| n.to_str()).unwrap_or("")).clicked() {
                                to_open_search = Some(sr.file_path.clone());
                            }
                            ui.label(&sr.snippet);
                            ui.separator();
                        }
                        if let Some(path) = to_open_search {
                            let _ = self.open_file(&path);
                        }
                    }
                });
            });
    }
    
    fn render_right_panel(&mut self, ctx: &egui::Context) {
        if self.show_graph {
            egui::Window::new("知识图谱")
                .resizable(true)
                .show(ctx, |ui| {
                    if let Some(kg) = &mut self.knowledge_graph {
                        kg.apply_force_directed();
                        if let Some(opened) = render_graph(kg, ui, &self.theme) {
                            let _ = self.open_file(&opened);
                        }
                    } else {
                        ui.label("加载中...");
                    }
                });
        }
        
        if self.show_backlinks {
            egui::SidePanel::right("backlinks_panel")
                .default_width(self.right_panel_width)
                .resizable(true)
                .show(ctx, |ui| {
                    ui.vertical(|ui| {
                        ui.heading("反向链接");
                        ui.separator();
                        
                        if let Some(cf) = &self.current_file {
                            let title = cf.file_stem().and_then(|s| s.to_str()).unwrap_or("");
                            let backlinks: Vec<(PathBuf, String)> = self.link_database
                                .get_backlinks(title)
                                .iter()
                                .map(|bl| (bl.source_file.clone(), bl.snippet.clone()))
                                .collect();
                            
                            if backlinks.is_empty() {
                                ui.label("暂无反向链接");
                            } else {
                                let mut to_open: Option<PathBuf> = None;
                                for (source_file, snippet) in &backlinks {
                                    if ui.link(source_file.file_name().and_then(|n| n.to_str()).unwrap_or("")).clicked() {
                                        to_open = Some(source_file.clone());
                                    }
                                    ui.label(snippet);
                                    ui.separator();
                                }
                                if let Some(path) = to_open {
                                    let _ = self.open_file(&path);
                                }
                            }
                        } else {
                            ui.label("请打开一篇笔记");
                        }
                    });
                });
        }
        
        if self.show_version_history {
            egui::Window::new("版本历史")
                .resizable(true)
                .default_size([400.0, 500.0])
                .show(ctx, |ui| {
                    ui.vertical(|ui| {
                        if self.version_history.is_empty() {
                            ui.label("暂无版本历史");
                        } else {
                            render_version_selector(&self.version_history, &mut self.selected_versions, ui);
                            
                            if self.selected_versions.len() == 2 {
                                ui.separator();
                                ui.heading("差异对比");
                                
                                if let (Some(cf), Some(gb)) = (&self.current_file, &self.git_backend) {
                                    let rel_path = cf.strip_prefix(self.notebook_path.as_ref().unwrap())
                                        .unwrap_or(cf)
                                        .to_str()
                                        .unwrap_or("");
                                    
                                    if let (Ok(old), Ok(new)) = (
                                        gb.get_file_content_at_commit(rel_path, &self.selected_versions[0]),
                                        gb.get_file_content_at_commit(rel_path, &self.selected_versions[1]),
                                    ) {
                                        let diff = compute_line_diff(&old, &new);
                                        egui::ScrollArea::vertical().show(ui, |ui| {
                                            render_diff_ui(&diff, ui, &self.theme);
                                        });
                                    }
                                }
                                
                                if ui.button("回滚到版本").clicked() {
                                    let cf_path = self.current_file.clone();
                                    if let (Some(cf), Some(gb)) = (cf_path, &mut self.git_backend) {
                                        let rel_path = cf.strip_prefix(self.notebook_path.as_ref().unwrap())
                                            .unwrap_or(&cf)
                                            .to_str()
                                            .unwrap_or("");
                                        let _ = gb.restore_to_commit(&self.selected_versions[0], rel_path);
                                        let _ = self.open_file(&cf);
                                    }
                                }
                            } else if self.selected_versions.len() == 1 {
                                ui.separator();
                                if let (Some(cf), Some(gb)) = (&self.current_file, &self.git_backend) {
                                    let rel_path = cf.strip_prefix(self.notebook_path.as_ref().unwrap())
                                        .unwrap_or(cf)
                                        .to_str()
                                        .unwrap_or("");
                                    
                                    if let Ok(content) = gb.get_file_content_at_commit(rel_path, &self.selected_versions[0]) {
                                        ui.label("该版本内容:");
                                        egui::ScrollArea::vertical().max_height(300.0).show(ui, |ui| {
                                            ui.add(egui::TextEdit::multiline(&mut content.as_str()).desired_width(f32::INFINITY));
                                        });
                                    }
                                }
                            }
                        }
                    });
                });
        }
        
        if self.show_settings {
            egui::Window::new("设置")
                .resizable(true)
                .default_size([400.0, 500.0])
                .show(ctx, |ui| {
                    ui.vertical(|ui| {
                        ui.heading("样式设置");
                        ui.separator();
                        
                        let mut changed = false;
                        
                        changed |= ui.add(egui::Slider::new(&mut self.theme.text_font_size, 10.0..=24.0).text("字号")).changed();
                        
                        if let Some(nb) = &self.notebook_path {
                            if changed {
                                let custom_path = nb.join(".notebook").join("custom_theme.toml");
                                let _ = self.theme.save_to_file(&custom_path);
                            }
                        }
                    });
                });
        }
    }
    
    fn apply_theme(&self, ctx: &egui::Context) {
        let mut visuals = egui::Visuals::default();
        self.theme.apply_to_visuals(&mut visuals);
        ctx.set_visuals(visuals);
    }
}

impl eframe::App for MarkNoteApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.apply_theme(ctx);
        self.render_menu_bar(ctx);
        self.slideshow.render(ctx, &self.theme);
        self.render_export_progress(ctx);
        self.render_external_file_change_notification(ctx);
        self.render_sidebar(ctx);
        self.render_right_panel(ctx);
        
        egui::CentralPanel::default().show(ctx, |ui| {
            if self.current_file.is_some() {
                self.editor.ui(ui, &self.theme);
            } else {
                ui.centered_and_justified(|ui| {
                    ui.vertical(|ui| {
                        ui.heading("MarkNote");
                        ui.label("轻量级 Markdown 笔记应用");
                        ui.separator();
                        ui.label("请打开一个笔记本或创建新笔记");
                        if ui.button("打开笔记本...").clicked() {
                            if let Some(path) = rfd::FileDialog::new().pick_folder() {
                                let _ = self.open_notebook(&path);
                            }
                        }
                    });
                });
            }
        });
    }
    
    fn on_exit(&mut self, _gl: Option<&eframe::glow::Context>) {
        if self.editor.state.is_dirty {
            let _ = self.save_file();
        }
    }
}
