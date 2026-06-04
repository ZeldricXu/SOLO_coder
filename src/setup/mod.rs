use crate::config::{AppConfig, Language};
use crate::theme::{Theme, ThemePreset};
use std::path::PathBuf;

pub struct SetupWizard {
    pub current_step: SetupStep,
    pub language: Language,
    pub theme_preset: ThemePreset,
    pub notebook_path: PathBuf,
    pub is_complete: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SetupStep {
    Welcome,
    Language,
    Theme,
    NotebookPath,
    Complete,
}

impl Default for SetupWizard {
    fn default() -> Self {
        Self::new()
    }
}

impl SetupWizard {
    pub fn new() -> Self {
        Self {
            current_step: SetupStep::Welcome,
            language: Language::default(),
            theme_preset: ThemePreset::Dark,
            notebook_path: AppConfig::default_notebook_path(),
            is_complete: false,
        }
    }

    pub fn next(&mut self) {
        self.current_step = match self.current_step {
            SetupStep::Welcome => SetupStep::Language,
            SetupStep::Language => SetupStep::Theme,
            SetupStep::Theme => SetupStep::NotebookPath,
            SetupStep::NotebookPath => SetupStep::Complete,
            SetupStep::Complete => SetupStep::Complete,
        };
    }

    pub fn prev(&mut self) {
        self.current_step = match self.current_step {
            SetupStep::Welcome => SetupStep::Welcome,
            SetupStep::Language => SetupStep::Welcome,
            SetupStep::Theme => SetupStep::Language,
            SetupStep::NotebookPath => SetupStep::Theme,
            SetupStep::Complete => SetupStep::NotebookPath,
        };
    }

    pub fn can_go_prev(&self) -> bool {
        !matches!(self.current_step, SetupStep::Welcome)
    }

    pub fn can_go_next(&self) -> bool {
        !matches!(self.current_step, SetupStep::Complete)
    }

    pub fn finish(&mut self) -> AppConfig {
        self.is_complete = true;
        AppConfig {
            language: self.language,
            theme_preset: match self.theme_preset {
                ThemePreset::Light => "light".to_string(),
                ThemePreset::Dark => "dark".to_string(),
                ThemePreset::HighContrast => "high_contrast".to_string(),
            },
            notebook_path: Some(self.notebook_path.clone()),
            ..AppConfig::default()
        }
    }
}

pub fn render_setup_wizard(
    ctx: &egui::Context,
    wizard: &mut SetupWizard,
    theme: &Theme,
) -> bool {
    let mut complete = false;

    egui::CentralPanel::default()
        .frame(egui::Frame::none().fill(theme.bg_color))
        .show(ctx, |ui| {
            ui.vertical_centered(|ui| {
                ui.add_space(80.0);
                
                let title = match wizard.current_step {
                    SetupStep::Welcome => t(wizard.language, "欢迎使用 MarkNote", "Welcome to MarkNote"),
                    SetupStep::Language => t(wizard.language, "选择语言", "Select Language"),
                    SetupStep::Theme => t(wizard.language, "选择主题", "Select Theme"),
                    SetupStep::NotebookPath => t(wizard.language, "设置笔记目录", "Set Notebook Directory"),
                    SetupStep::Complete => t(wizard.language, "设置完成！", "Setup Complete!"),
                };
                
                ui.heading(egui::RichText::new(title).color(theme.heading_color).size(28.0));
                ui.add_space(40.0);

                egui::Frame::none()
                    .inner_margin(egui::Margin::symmetric(60.0, 20.0))
                    .show(ui, |ui| {
                        ui.set_min_width(500.0);
                        
                        match wizard.current_step {
                            SetupStep::Welcome => render_welcome_step(ui, wizard, theme),
                            SetupStep::Language => render_language_step(ui, wizard),
                            SetupStep::Theme => render_theme_step(ui, wizard),
                            SetupStep::NotebookPath => render_notebook_path_step(ui, wizard, theme),
                            SetupStep::Complete => render_complete_step(ui, wizard, theme),
                        }
                    });

                ui.add_space(40.0);
                
                ui.horizontal(|ui| {
                    ui.with_layout(egui::Layout::left_to_right(egui::Align::Center), |ui| {
                        if wizard.can_go_prev() {
                            if ui.button(t(wizard.language, "上一步", "Previous")).clicked() {
                                wizard.prev();
                            }
                        }
                    });
                    
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if wizard.can_go_next() {
                            if ui.button(t(wizard.language, "下一步", "Next")).clicked() {
                                wizard.next();
                            }
                        } else if wizard.current_step == SetupStep::Complete {
                            if ui.button(t(wizard.language, "开始使用", "Get Started")).clicked() {
                                complete = true;
                            }
                        }
                    });
                });
            });
        });

    complete
}

fn render_welcome_step(ui: &mut egui::Ui, wizard: &SetupWizard, theme: &Theme) {
    ui.add_space(20.0);
    ui.label(
        egui::RichText::new(t(wizard.language, 
            "一个现代化、轻量级的跨平台 Markdown 笔记应用", 
            "A modern, lightweight cross-platform Markdown note-taking app"
        )).color(theme.text_color).size(16.0)
    );
    ui.add_space(20.0);
    
    ui.label(egui::RichText::new(t(wizard.language,
        "✨ 功能特点:",
        "✨ Features:"
    )).color(theme.accent_color).size(18.0));
    
    ui.add_space(10.0);
    
    let features = if wizard.language == Language::Chinese {
        vec![
            "📝 所见即所得的 Markdown 编辑器",
            "🔍 全文搜索，快速定位笔记",
            "🔗 双向链接与知识图谱",
            "🎨 多种主题，支持自定义",
            "📄 多格式导出 (HTML/PDF/DOCX)",
            "📊 幻灯片演示模式",
        ]
    } else {
        vec![
            "📝 WYSIWYG Markdown Editor",
            "🔍 Full-text Search",
            "🔗 Bidirectional Links & Knowledge Graph",
            "🎨 Multiple Themes & Customization",
            "📄 Multi-format Export (HTML/PDF/DOCX)",
            "📊 Slideshow Mode",
        ]
    };
    
    for feature in features {
        ui.label(egui::RichText::new(feature).color(theme.text_color));
        ui.add_space(5.0);
    }
}

fn render_language_step(ui: &mut egui::Ui, wizard: &mut SetupWizard) {
    ui.vertical_centered(|ui| {
        ui.add_space(30.0);
        
        if ui.selectable_label(wizard.language == Language::Chinese, "🇨🇳  简体中文").clicked() {
            wizard.language = Language::Chinese;
        }
        ui.add_space(10.0);
        
        if ui.selectable_label(wizard.language == Language::English, "🇺🇸  English").clicked() {
            wizard.language = Language::English;
        }
    });
}

fn render_theme_step(ui: &mut egui::Ui, wizard: &mut SetupWizard) {
    ui.vertical_centered(|ui| {
        ui.add_space(20.0);
        
        ui.horizontal(|ui| {
            ui.add_space(ui.available_width() / 2.0 - 150.0);
            
            ui.vertical(|ui| {
                let light_btn = egui::Button::new(egui::RichText::new("☀️ 亮色").size(14.0))
                    .min_size(egui::vec2(100.0, 60.0))
                    .fill(egui::Color32::from_rgb(250, 250, 250))
                    .stroke(if wizard.theme_preset == ThemePreset::Light { 
                        egui::Stroke::new(2.0, egui::Color32::from_rgb(99, 102, 241)) 
                    } else { 
                        egui::Stroke::new(1.0, egui::Color32::LIGHT_GRAY) 
                    });
                
                if ui.add(light_btn).clicked() {
                    wizard.theme_preset = ThemePreset::Light;
                }
            });
            
            ui.add_space(20.0);
            
            ui.vertical(|ui| {
                let dark_btn = egui::Button::new(egui::RichText::new("🌙 暗色").size(14.0).color(egui::Color32::WHITE))
                    .min_size(egui::vec2(100.0, 60.0))
                    .fill(egui::Color32::from_rgb(30, 30, 30))
                    .stroke(if wizard.theme_preset == ThemePreset::Dark { 
                        egui::Stroke::new(2.0, egui::Color32::from_rgb(99, 102, 241)) 
                    } else { 
                        egui::Stroke::new(1.0, egui::Color32::DARK_GRAY) 
                    });
                
                if ui.add(dark_btn).clicked() {
                    wizard.theme_preset = ThemePreset::Dark;
                }
            });
            
            ui.add_space(20.0);
            
            ui.vertical(|ui| {
                let hc_btn = egui::Button::new(egui::RichText::new("◉ 高对比").size(14.0).color(egui::Color32::YELLOW))
                    .min_size(egui::vec2(100.0, 60.0))
                    .fill(egui::Color32::BLACK)
                    .stroke(if wizard.theme_preset == ThemePreset::HighContrast { 
                        egui::Stroke::new(2.0, egui::Color32::YELLOW) 
                    } else { 
                        egui::Stroke::new(1.0, egui::Color32::DARK_GRAY) 
                    });
                
                if ui.add(hc_btn).clicked() {
                    wizard.theme_preset = ThemePreset::HighContrast;
                }
            });
        });
    });
}

fn render_notebook_path_step(ui: &mut egui::Ui, wizard: &mut SetupWizard, theme: &Theme) {
    ui.add_space(20.0);
    
    ui.label(egui::RichText::new(t(wizard.language,
        "选择您的笔记存储目录：",
        "Choose your notebook storage directory:"
    )).color(theme.text_color));
    
    ui.add_space(10.0);
    
    ui.horizontal(|ui| {
        let path_str = wizard.notebook_path.to_string_lossy().to_string();
        ui.add_sized([400.0, 24.0], egui::Label::new(egui::RichText::new(path_str).color(theme.text_color).monospace()));
        
        if ui.button(t(wizard.language, "浏览", "Browse")).clicked() {
            if let Some(path) = rfd::FileDialog::new().pick_folder() {
                wizard.notebook_path = path;
            }
        }
    });
    
    ui.add_space(20.0);
    
    let default_path = AppConfig::default_notebook_path().to_string_lossy().to_string();
    let zh_text = format!("默认: {}", default_path);
    let en_text = format!("Default: {}", default_path);
    ui.label(egui::RichText::new(t(wizard.language, &zh_text, &en_text))
        .color(theme.text_color).weak().small());
}

fn render_complete_step(ui: &mut egui::Ui, wizard: &SetupWizard, theme: &Theme) {
    ui.vertical_centered(|ui| {
        ui.add_space(30.0);
        
        ui.label(egui::RichText::new("🎉").size(48.0));
        
        ui.add_space(20.0);
        
        ui.label(egui::RichText::new(t(wizard.language,
            "您已完成初始设置！",
            "You've completed the setup!"
        )).color(theme.accent_color).size(20.0));
        
        ui.add_space(15.0);
        
        let path_str = wizard.notebook_path.to_string_lossy().to_string();
        let zh_path = format!("笔记将保存在: {}", path_str);
        let en_path = format!("Notes will be saved in: {}", path_str);
        ui.label(egui::RichText::new(t(wizard.language, &zh_path, &en_path))
            .color(theme.text_color));
        
        ui.add_space(20.0);
        
        ui.label(egui::RichText::new(t(wizard.language,
            "点击下方按钮开始您的笔记之旅。",
            "Click the button below to start your note-taking journey."
        )).color(theme.text_color).weak());
    });
}

fn t(lang: Language, zh: &str, en: &str) -> String {
    match lang {
        Language::Chinese => zh.to_string(),
        Language::English => en.to_string(),
    }
}
