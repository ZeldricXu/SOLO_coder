use egui::Color32;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::Path;

const LIGHT_THEME: &str = include_str!("light.toml");
const DARK_THEME: &str = include_str!("dark.toml");
const HIGH_CONTRAST_THEME: &str = include_str!("high_contrast.toml");

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ThemePreset {
    Light,
    Dark,
    HighContrast,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ThemeColors {
    pub bg_color: String,
    pub text_color: String,
    pub heading_color: String,
    pub code_bg: String,
    pub code_text: String,
    pub link_color: String,
    pub accent_color: String,
    pub border_color: String,
    pub selection_bg: String,
    pub blockquote_bg: String,
    pub blockquote_border: String,
    pub table_header_bg: String,
    pub table_alt_bg: String,
    pub scrollbar_bg: String,
    pub scrollbar_handle: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ThemeFonts {
    pub text_font_family: String,
    pub code_font_family: String,
    pub text_font_size: f32,
    pub code_font_size: f32,
    pub heading_sizes: [f32; 6],
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ThemeSpacing {
    pub line_spacing: f32,
    pub paragraph_spacing: f32,
    pub code_block_padding: f32,
    pub list_indent: f32,
    pub blockquote_indent: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ThemeConfig {
    #[serde(flatten)]
    pub colors: ThemeColors,
    #[serde(flatten)]
    pub fonts: ThemeFonts,
    #[serde(flatten)]
    pub spacing: ThemeSpacing,
}

#[derive(Debug, Clone)]
pub struct Theme {
    pub bg_color: Color32,
    pub text_color: Color32,
    pub heading_color: Color32,
    pub code_bg: Color32,
    pub code_text: Color32,
    pub link_color: Color32,
    pub accent_color: Color32,
    pub border_color: Color32,
    pub selection_bg: Color32,
    pub blockquote_bg: Color32,
    pub blockquote_border: Color32,
    pub table_header_bg: Color32,
    pub table_alt_bg: Color32,
    pub scrollbar_bg: Color32,
    pub scrollbar_handle: Color32,
    pub text_font_family: String,
    pub code_font_family: String,
    pub text_font_size: f32,
    pub code_font_size: f32,
    pub heading_sizes: [f32; 6],
    pub line_spacing: f32,
    pub paragraph_spacing: f32,
    pub code_block_padding: f32,
    pub list_indent: f32,
    pub blockquote_indent: f32,
}

impl Theme {
    pub fn from_preset(preset: ThemePreset) -> Self {
        let toml_str = match preset {
            ThemePreset::Light => LIGHT_THEME,
            ThemePreset::Dark => DARK_THEME,
            ThemePreset::HighContrast => HIGH_CONTRAST_THEME,
        };
        let config: ThemeConfig = toml::from_str(toml_str).unwrap_or_else(|_| {
            toml::from_str(DARK_THEME).expect("Dark theme TOML must be valid")
        });
        Self::from_config(config)
    }

    pub fn from_file(path: &Path) -> Result<Self, Box<dyn std::error::Error>> {
        let content = fs::read_to_string(path)?;
        let config: ThemeConfig = toml::from_str(&content)?;
        Ok(Self::from_config(config))
    }

    pub fn load_custom_or_fallback(custom_path: &Path) -> Self {
        match Self::from_file(custom_path) {
            Ok(theme) => theme,
            Err(_) => Self::from_preset(ThemePreset::Dark),
        }
    }

    fn from_config(config: ThemeConfig) -> Self {
        Self {
            bg_color: parse_color(&config.colors.bg_color),
            text_color: parse_color(&config.colors.text_color),
            heading_color: parse_color(&config.colors.heading_color),
            code_bg: parse_color(&config.colors.code_bg),
            code_text: parse_color(&config.colors.code_text),
            link_color: parse_color(&config.colors.link_color),
            accent_color: parse_color(&config.colors.accent_color),
            border_color: parse_color(&config.colors.border_color),
            selection_bg: parse_color(&config.colors.selection_bg),
            blockquote_bg: parse_color(&config.colors.blockquote_bg),
            blockquote_border: parse_color(&config.colors.blockquote_border),
            table_header_bg: parse_color(&config.colors.table_header_bg),
            table_alt_bg: parse_color(&config.colors.table_alt_bg),
            scrollbar_bg: parse_color(&config.colors.scrollbar_bg),
            scrollbar_handle: parse_color(&config.colors.scrollbar_handle),
            text_font_family: config.fonts.text_font_family,
            code_font_family: config.fonts.code_font_family,
            text_font_size: config.fonts.text_font_size,
            code_font_size: config.fonts.code_font_size,
            heading_sizes: config.fonts.heading_sizes,
            line_spacing: config.spacing.line_spacing,
            paragraph_spacing: config.spacing.paragraph_spacing,
            code_block_padding: config.spacing.code_block_padding,
            list_indent: config.spacing.list_indent,
            blockquote_indent: config.spacing.blockquote_indent,
        }
    }

    pub fn save_to_file(&self, path: &Path) -> Result<(), Box<dyn std::error::Error>> {
        let config = ThemeConfig {
            colors: ThemeColors {
                bg_color: color_to_hex(&self.bg_color),
                text_color: color_to_hex(&self.text_color),
                heading_color: color_to_hex(&self.heading_color),
                code_bg: color_to_hex(&self.code_bg),
                code_text: color_to_hex(&self.code_text),
                link_color: color_to_hex(&self.link_color),
                accent_color: color_to_hex(&self.accent_color),
                border_color: color_to_hex(&self.border_color),
                selection_bg: color_to_hex(&self.selection_bg),
                blockquote_bg: color_to_hex(&self.blockquote_bg),
                blockquote_border: color_to_hex(&self.blockquote_border),
                table_header_bg: color_to_hex(&self.table_header_bg),
                table_alt_bg: color_to_hex(&self.table_alt_bg),
                scrollbar_bg: color_to_hex(&self.scrollbar_bg),
                scrollbar_handle: color_to_hex(&self.scrollbar_handle),
            },
            fonts: ThemeFonts {
                text_font_family: self.text_font_family.clone(),
                code_font_family: self.code_font_family.clone(),
                text_font_size: self.text_font_size,
                code_font_size: self.code_font_size,
                heading_sizes: self.heading_sizes,
            },
            spacing: ThemeSpacing {
                line_spacing: self.line_spacing,
                paragraph_spacing: self.paragraph_spacing,
                code_block_padding: self.code_block_padding,
                list_indent: self.list_indent,
                blockquote_indent: self.blockquote_indent,
            },
        };
        let content = toml::to_string_pretty(&config)?;
        fs::write(path, content)?;
        Ok(())
    }

    pub fn apply_to_visuals(&self, visuals: &mut egui::Visuals) {
        visuals.extreme_bg_color = self.bg_color;
        visuals.panel_fill = self.bg_color;
        visuals.widgets.noninteractive.bg_fill = self.bg_color;
        visuals.widgets.noninteractive.fg_stroke.color = self.text_color;
        visuals.selection.bg_fill = self.selection_bg;
        visuals.widgets.inactive.bg_fill = self.bg_color;
        visuals.widgets.hovered.bg_fill = self.selection_bg;
        visuals.widgets.active.bg_fill = self.accent_color;
    }

    pub fn text_font_id(&self) -> egui::FontId {
        egui::FontId::new(self.text_font_size, parse_font_family(&self.text_font_family, egui::FontFamily::Proportional))
    }

    pub fn code_font_id(&self) -> egui::FontId {
        egui::FontId::new(self.code_font_size, parse_font_family(&self.code_font_family, egui::FontFamily::Monospace))
    }

    pub fn heading_font_id(&self, level: usize) -> egui::FontId {
        let size = self.heading_sizes.get(level.saturating_sub(1)).copied().unwrap_or(self.text_font_size);
        egui::FontId::new(size, parse_font_family(&self.text_font_family, egui::FontFamily::Proportional))
    }
}

fn parse_font_family(name: &str, default: egui::FontFamily) -> egui::FontFamily {
    match name.to_lowercase().as_str() {
        "monospace" => egui::FontFamily::Monospace,
        "proportional" => egui::FontFamily::Proportional,
        _ => egui::FontFamily::Name(name.into()),
    }
}

fn parse_color(hex: &str) -> Color32 {
    let hex = hex.trim_start_matches('#');
    if hex.len() != 6 {
        return Color32::GRAY;
    }
    let r = u8::from_str_radix(&hex[0..2], 16).unwrap_or(128);
    let g = u8::from_str_radix(&hex[2..4], 16).unwrap_or(128);
    let b = u8::from_str_radix(&hex[4..6], 16).unwrap_or(128);
    Color32::from_rgb(r, g, b)
}

fn color_to_hex(color: &Color32) -> String {
    format!("#{:02X}{:02X}{:02X}", color.r(), color.g(), color.b())
}

impl Default for Theme {
    fn default() -> Self {
        Self::from_preset(ThemePreset::Dark)
    }
}
