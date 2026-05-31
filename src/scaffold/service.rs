use crate::scaffold::domain::{
    ScaffoldConfig, GeneratedFile, ScaffoldResult, Language, ProjectType
};
use crate::scaffold::engine::{
    TemplateEngine, ProjectWriter, InteractivePrompter, TeraTemplateEngine,
    BuiltinTemplates, FilesystemWriter, DialoguerPrompter
};
use crate::utils::error::Result;
use std::sync::Arc;
use std::path::Path;

pub struct ScaffoldServiceImpl<E, W, P>
where
    E: TemplateEngine,
    W: ProjectWriter,
    P: InteractivePrompter,
{
    engine: E,
    writer: W,
    prompter: P,
}

impl<E, W, P> ScaffoldServiceImpl<E, W, P>
where
    E: TemplateEngine,
    W: ProjectWriter,
    P: InteractivePrompter,
{
    pub fn new(engine: E, writer: W, prompter: P) -> Self {
        Self { engine, writer, prompter }
    }
}

pub type ScaffoldService = ScaffoldServiceImpl<
    TeraTemplateEngine,
    FilesystemWriter,
    DialoguerPrompter
>;

impl ScaffoldService {
    pub fn new() -> Self {
        let templates = Arc::new(BuiltinTemplates::new());
        let engine = TeraTemplateEngine::new(templates);
        let writer = FilesystemWriter::new();
        let prompter = DialoguerPrompter::new();
        Self::new(engine, writer, prompter)
    }
}

impl Default for ScaffoldService {
    fn default() -> Self {
        Self::new()
    }
}

impl<E, W, P> ScaffoldServiceImpl<E, W, P>
where
    E: TemplateEngine,
    W: ProjectWriter,
    P: InteractivePrompter,
{
    pub async fn generate(
        &self,
        config: &ScaffoldConfig,
        output_dir: &Path,
    ) -> Result<ScaffoldResult> {
        self.writer.create_dir_all(output_dir).await?;
        
        let files = self.render_files(config)?;
        self.writer.write(output_dir, &files).await?;
        
        Ok(ScaffoldResult {
            project_name: config.project_name.clone(),
            output_dir: output_dir.to_path_buf(),
            files,
        })
    }

    pub async fn interactive_generate(&self, output_dir: &Path) -> Result<ScaffoldResult> {
        let config = self.collect_interactive().await?;
        self.generate(&config, output_dir).await
    }

    fn render_files(&self, config: &ScaffoldConfig) -> Result<Vec<GeneratedFile>> {
        let mut files = Vec::new();
        
        match config.language {
            Language::Rust => {
                files.push(GeneratedFile {
                    path: "Cargo.toml".into(),
                    content: self.engine.render("Cargo.toml", config)?,
                });
                
                match config.project_type {
                    ProjectType::Library => {
                        files.push(GeneratedFile {
                            path: "src/lib.rs".into(),
                            content: self.engine.render("lib.rs", config)?,
                        });
                    }
                    _ => {
                        files.push(GeneratedFile {
                            path: "src/main.rs".into(),
                            content: self.engine.render("main.rs", config)?,
                        });
                    }
                }
            }
            Language::Python | Language::Go | Language::JavaScript | Language::TypeScript => {
                files.push(GeneratedFile {
                    path: "README.md".into(),
                    content: self.engine.render("README.md", config)?,
                });
            }
        }

        if config.use_git {
            files.push(GeneratedFile {
                path: ".gitignore".into(),
                content: self.engine.render(".gitignore", config)?,
            });
        }

        Ok(files)
    }

    async fn collect_interactive(&self) -> Result<ScaffoldConfig> {
        let project_name = self.prompter.prompt_string(
            "Project name",
            Some("my-project"),
        ).await?;

        let description = self.prompter.prompt_string(
            "Project description",
            Some("A Rust project"),
        ).await?;

        let author = self.prompter.prompt_string(
            "Author",
            Some("Anonymous"),
        ).await?;

        let version = self.prompter.prompt_string(
            "Version",
            Some("0.1.0"),
        ).await?;

        let languages = [Language::Rust, Language::Python, Language::Go, Language::JavaScript, Language::TypeScript];
        let language = self.prompter.prompt_select(
            "Language",
            &languages,
            0,
        ).await?;

        let types = [ProjectType::Library, ProjectType::Service, ProjectType::Cli, ProjectType::Web];
        let project_type = self.prompter.prompt_select(
            "Project type",
            &types,
            0,
        ).await?;

        let use_git = self.prompter.prompt_bool("Initialize Git", true).await?;
        let include_license = self.prompter.prompt_bool("Include license", true).await?;

        Ok(ScaffoldConfig {
            project_name,
            language,
            project_type,
            author,
            description,
            version,
            use_git,
            include_license,
            extra_vars: std::collections::HashMap::new(),
        })
    }
}
