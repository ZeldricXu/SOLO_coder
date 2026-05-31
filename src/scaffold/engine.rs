use crate::scaffold::domain::{ScaffoldConfig, GeneratedFile};
use crate::utils::error::{Result, PlatformError};
use std::sync::Arc;
use std::path::{Path, PathBuf};
use tracing::{warn, debug};

pub trait TemplateEngine: Send + Sync + 'static {
    fn render(&self, template_name: &str, context: &ScaffoldConfig) -> Result<String>;
    fn list_templates(&self) -> Vec<&'static str>;
}

pub trait ProjectWriter: Send + Sync + 'static {
    async fn write(&self, output_dir: &Path, files: &[GeneratedFile]) -> Result<()>;
    async fn create_dir_all(&self, path: &Path) -> Result<()>;
    async fn exists(&self, path: &Path) -> bool;
}

pub trait InteractivePrompter: Send + Sync + 'static {
    async fn prompt_string(&self, question: &str, default: Option<&str>) -> Result<String>;
    async fn prompt_bool(&self, question: &str, default: bool) -> Result<bool>;
    async fn prompt_select<T: ToString + Clone>(&self, question: &str, options: &[T], default: usize) -> Result<T>;
}

pub trait TemplateProvider: Send + Sync + 'static {
    fn get_template(&self, name: &str) -> Option<&'static str>;
}

#[derive(Default, Clone)]
pub struct BuiltinTemplates;

impl BuiltinTemplates {
    pub fn new() -> Self {
        Self
    }
}

impl TemplateProvider for BuiltinTemplates {
    fn get_template(&self, name: &str) -> Option<&'static str> {
        match name {
            "Cargo.toml" => Some(CARGO_TOML),
            "main.rs" => Some(MAIN_RS),
            "lib.rs" => Some(LIB_RS),
            ".gitignore" => Some(GITIGNORE),
            "README.md" => Some(README_MD),
            _ => None,
        }
    }
}

#[derive(Clone)]
pub struct TeraTemplateEngine {
    provider: Arc<dyn TemplateProvider>,
}

impl TeraTemplateEngine {
    pub fn new(provider: Arc<dyn TemplateProvider>) -> Self {
        Self { provider }
    }

    fn build_context(config: &ScaffoldConfig) -> serde_json::Value {
        serde_json::json!({
            "project_name": config.project_name,
            "author": config.author,
            "description": config.description,
            "version": config.version,
            "language": format!("{:?}", config.language).to_lowercase(),
            "project_type": format!("{:?}", config.project_type).to_lowercase(),
            "use_git": config.use_git,
            "include_license": config.include_license,
            "extra": config.extra_vars,
        })
    }

    fn render_builtin(template: &str, ctx: &ScaffoldConfig) -> Result<String> {
        let context = Self::build_context(ctx);
        let mut engine = tera::Tera::default();
        let rendered = engine.render_str(template, &context)
            .map_err(|e| PlatformError::Serialization(e.to_string()))?;
        Ok(rendered)
    }
}

impl TemplateEngine for TeraTemplateEngine {
    fn render(&self, template_name: &str, context: &ScaffoldConfig) -> Result<String> {
        match template_name {
            "Cargo.toml" => Self::render_builtin(CARGO_TOML, context),
            "main.rs" => Self::render_builtin(MAIN_RS, context),
            "lib.rs" => Self::render_builtin(LIB_RS, context),
            ".gitignore" => Self::render_builtin(GITIGNORE, context),
            "README.md" => Self::render_builtin(README_MD, context),
            other => Err(PlatformError::NotFound(format!("template not found: {}", other))),
        }
    }

    fn list_templates(&self) -> Vec<&'static str> {
        vec!["Cargo.toml", "main.rs", "lib.rs", ".gitignore", "README.md"]
    }
}

pub struct TempDirGuard {
    path: Option<PathBuf>,
    auto_cleanup: bool,
}

impl TempDirGuard {
    pub fn new(base: &Path, prefix: &str) -> Result<Self> {
        let timestamp = chrono::Utc::now().timestamp_nanos();
        let dir_name = format!(".scaffold_{}_{}", prefix, timestamp);
        let temp_path = base.join(dir_name);
        
        std::fs::create_dir_all(&temp_path)
            .map_err(|e| PlatformError::Io(e))?;
        
        debug!(temp_dir = %temp_path.display(), "created temporary directory");
        
        Ok(Self {
            path: Some(temp_path),
            auto_cleanup: true,
        })
    }

    pub fn path(&self) -> &Path {
        self.path.as_deref().expect("temp dir path should exist")
    }

    pub fn commit(mut self) -> PathBuf {
        self.auto_cleanup = false;
        self.path.take().expect("temp dir path should exist")
    }
}

impl Drop for TempDirGuard {
    fn drop(&mut self) {
        if self.auto_cleanup {
            if let Some(path) = self.path.take() {
                if let Err(e) = std::fs::remove_dir_all(&path) {
                    warn!(error = %e, path = %path.display(), "failed to cleanup temporary directory");
                } else {
                    debug!(path = %path.display(), "cleaned up temporary directory");
                }
            }
        }
    }
}

#[derive(Default, Clone)]
pub struct FilesystemWriter;

impl FilesystemWriter {
    pub fn new() -> Self {
        Self
    }

    async fn write_file_atomic(dir: &Path, file: &GeneratedFile) -> Result<()> {
        let full_path = dir.join(&file.path);
        
        if let Some(parent) = full_path.parent() {
            if !parent.as_os_str().is_empty() {
                tokio::fs::create_dir_all(parent).await
                    .map_err(|e| PlatformError::Io(e))?;
            }
        }

        let file_name = full_path.file_name()
            .ok_or_else(|| PlatformError::Validation("invalid file path".to_string()))?;
        let parent = full_path.parent().unwrap_or_else(|| Path::new("."));
        
        let temp_name = format!(".{}.tmp", file_name.to_string_lossy());
        let temp_path = parent.join(&temp_name);
        
        tokio::fs::write(&temp_path, &file.content).await
            .map_err(|e| PlatformError::Io(e))?;
        
        tokio::fs::rename(&temp_path, &full_path).await
            .map_err(|e| PlatformError::Io(e))?;
        
        Ok(())
    }
}

impl ProjectWriter for FilesystemWriter {
    async fn write(&self, output_dir: &Path, files: &[GeneratedFile]) -> Result<()> {
        let temp_guard = TempDirGuard::new(output_dir.parent().unwrap_or_else(|| Path::new(".")), "scaffold")?;
        let temp_dir = temp_guard.path();

        for file in files {
            Self::write_file_atomic(temp_dir, file).await?;
        }

        if output_dir.exists() {
            for entry in std::fs::read_dir(output_dir).map_err(|e| PlatformError::Io(e))? {
                let entry = entry.map_err(|e| PlatformError::Io(e))?;
                let source = entry.path();
                let file_name = entry.file_name();
                let dest = temp_dir.join(file_name);
                if !dest.exists() {
                    tokio::fs::rename(&source, &dest).await
                        .map_err(|e| PlatformError::Io(e))?;
                }
            }
            std::fs::remove_dir_all(output_dir).map_err(|e| PlatformError::Io(e))?;
        }

        if let Some(parent) = output_dir.parent() {
            if !parent.as_os_str().is_empty() {
                tokio::fs::create_dir_all(parent).await
                    .map_err(|e| PlatformError::Io(e))?;
            }
        }

        let committed_temp = temp_guard.commit();
        tokio::fs::rename(&committed_temp, output_dir).await
            .map_err(|e| PlatformError::Io(e))?;

        Ok(())
    }

    async fn create_dir_all(&self, path: &Path) -> Result<()> {
        tokio::fs::create_dir_all(path).await
            .map_err(|e| PlatformError::Io(e))?;
        Ok(())
    }

    async fn exists(&self, path: &Path) -> bool {
        tokio::fs::metadata(path).await.is_ok()
    }
}

#[derive(Default, Clone)]
pub struct DialoguerPrompter;

impl DialoguerPrompter {
    pub fn new() -> Self {
        Self
    }
}

impl InteractivePrompter for DialoguerPrompter {
    async fn prompt_string(&self, question: &str, default: Option<&str>) -> Result<String> {
        use dialoguer::Input;
        let mut input = Input::new().with_prompt(question);
        if let Some(d) = default {
            input = input.default(d.to_string());
        }
        let result = input.interact_text()
            .map_err(|e| PlatformError::Io(std::io::Error::new(std::io::ErrorKind::Other, e)))?;
        Ok(result)
    }

    async fn prompt_bool(&self, question: &str, default: bool) -> Result<bool> {
        use dialoguer::Confirm;
        let result = Confirm::new()
            .with_prompt(question)
            .default(default)
            .interact()
            .map_err(|e| PlatformError::Io(std::io::Error::new(std::io::ErrorKind::Other, e)))?;
        Ok(result)
    }

    async fn prompt_select<T: ToString + Clone>(&self, question: &str, options: &[T], default: usize) -> Result<T> {
        use dialoguer::Select;
        let items: Vec<String> = options.iter().map(ToString::to_string).collect();
        let index = Select::new()
            .with_prompt(question)
            .items(&items)
            .default(default)
            .interact()
            .map_err(|e| PlatformError::Io(std::io::Error::new(std::io::ErrorKind::Other, e)))?;
        Ok(options[index].clone())
    }
}

const CARGO_TOML: &str = r#"[package]
name = "{{ project_name }}"
version = "{{ version }}"
edition = "2021"
authors = ["{{ author }}"]
description = "{{ description }}"

[dependencies]
tokio = { version = "1.35", features = ["full"] }
"#;

const MAIN_RS: &str = r#"
fn main() {
    println!("Hello, {{ project_name }}!");
}
"#;

const LIB_RS: &str = r#"
pub fn hello() -> &'static str {
    "Hello from {{ project_name }}!"
}
"#;

const GITIGNORE: &str = r#"
target/
Cargo.lock
"#;

const README_MD: &str = r#"# {{ project_name }}

{{ description }}

## License

{{ author }}
"#;
