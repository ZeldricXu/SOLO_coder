use std::path::Path;
use std::fmt;

pub type ExportProgress = f32;

#[derive(Debug)]
pub enum ExportError {
    IoError(std::io::Error),
    ConversionError(String),
    ExternalToolError(String),
    ZipError(String),
}

impl fmt::Display for ExportError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ExportError::IoError(e) => write!(f, "IO error: {}", e),
            ExportError::ConversionError(e) => write!(f, "Conversion error: {}", e),
            ExportError::ExternalToolError(e) => write!(f, "External tool error: {}", e),
            ExportError::ZipError(e) => write!(f, "Zip error: {}", e),
        }
    }
}

impl std::error::Error for ExportError {}

impl From<std::io::Error> for ExportError {
    fn from(err: std::io::Error) -> Self {
        ExportError::IoError(err)
    }
}

pub type ExportResult<T> = Result<T, ExportError>;

pub trait ExportFormat {
    fn name(&self) -> &'static str;
    fn extension(&self) -> &'static str;
    fn export(
        &self,
        content: &str,
        output_path: &Path,
        progress_callback: Option<&dyn Fn(ExportProgress)>,
    ) -> ExportResult<()>;

    fn estimate_steps(&self, content: &str) -> usize {
        let _ = content;
        1
    }
}

#[derive(Clone)]
pub struct ExportProgressBar {
    current: f32,
    total_steps: usize,
    current_step: usize,
    status: String,
}

impl ExportProgressBar {
    pub fn new(total_steps: usize) -> Self {
        Self {
            current: 0.0,
            total_steps,
            current_step: 0,
            status: String::new(),
        }
    }

    pub fn set_status(&mut self, status: &str) {
        self.status = status.to_string();
    }

    pub fn update_step(&mut self, step: usize) {
        self.current_step = step;
        self.current = step as f32 / self.total_steps as f32;
    }

    pub fn update_progress(&mut self, progress: f32) {
        self.current = progress.clamp(0.0, 1.0);
    }

    pub fn increment(&mut self, amount: f32) {
        self.current = (self.current + amount).clamp(0.0, 1.0);
    }

    pub fn progress(&self) -> f32 {
        self.current
    }

    pub fn is_complete(&self) -> bool {
        self.current >= 1.0
    }

    pub fn render(&self, ui: &mut egui::Ui) {
        ui.vertical(|ui| {
            if !self.status.is_empty() {
                ui.label(&self.status);
            }
            let bar = egui::ProgressBar::new(self.current)
                .show_percentage()
                .animate(true);
            ui.add(bar);
        });
    }
}

pub struct ExportContext {
    pub content: String,
    pub output_path: std::path::PathBuf,
    pub format: Box<dyn ExportFormat>,
    pub progress: ExportProgressBar,
    pub is_running: bool,
    pub error: Option<String>,
    pub completed: bool,
}

impl ExportContext {
    pub fn new(content: String, output_path: std::path::PathBuf, format: Box<dyn ExportFormat>) -> Self {
        let steps = format.estimate_steps(&content);
        Self {
            content,
            output_path,
            format,
            progress: ExportProgressBar::new(steps),
            is_running: false,
            error: None,
            completed: false,
        }
    }

    pub fn start(&mut self) {
        if self.is_running || self.completed {
            return;
        }
        self.is_running = true;
        self.error = None;
        self.progress = ExportProgressBar::new(self.format.estimate_steps(&self.content));
    }

    pub fn run_export(&mut self) {
        if !self.is_running || self.completed {
            return;
        }

        self.progress.set_status("正在导出...");
        
        let content = self.content.clone();
        let output_path = self.output_path.clone();
        let result = self.format.export(
            &content,
            &output_path,
            None,
        );

        match result {
            Ok(_) => {
                self.progress.update_progress(1.0);
                self.progress.set_status("导出完成!");
                self.completed = true;
            }
            Err(e) => {
                self.error = Some(format!("导出失败: {}", e));
            }
        }
        self.is_running = false;
    }

    pub fn reset(&mut self) {
        self.is_running = false;
        self.completed = false;
        self.error = None;
        self.progress = ExportProgressBar::new(self.format.estimate_steps(&self.content));
    }
}
