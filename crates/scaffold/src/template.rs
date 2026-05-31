use rusqlite::{params, Connection, OptionalExtension, Rows};
use uuid::Uuid;
use chrono::{DateTime, Utc};
use serde_json;
use crate::models::{ProjectTemplate, FileTemplate, TemplateParameter, TemplateKind, ParamType};
use anyhow::Result;

pub struct TemplateManager {
    conn: Connection,
}

impl TemplateManager {
    pub fn new(conn: Connection) -> Self {
        TemplateManager { conn }
    }

    pub fn init_schema(&self) -> Result<()> {
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS project_templates (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL,
                kind TEXT NOT NULL,
                created_at TEXT NOT NULL
            )",
            [],
        )?;

        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS file_templates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                template_id TEXT NOT NULL,
                path TEXT NOT NULL,
                content_template TEXT NOT NULL,
                FOREIGN KEY (template_id) REFERENCES project_templates(id)
            )",
            [],
        )?;

        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS template_parameters (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                template_id TEXT NOT NULL,
                name TEXT NOT NULL,
                display_name TEXT NOT NULL,
                description TEXT NOT NULL,
                param_type TEXT NOT NULL,
                default_value TEXT,
                required INTEGER NOT NULL,
                choices TEXT,
                FOREIGN KEY (template_id) REFERENCES project_templates(id)
            )",
            [],
        )?;

        Ok(())
    }

    pub fn add_template(&mut self, template: ProjectTemplate) -> Result<()> {
        let tx = self.conn.transaction()?;

        tx.execute(
            "INSERT INTO project_templates (id, name, description, kind, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![
                template.id.to_string(),
                template.name,
                template.description,
                kind_to_string(&template.kind),
                template.created_at.to_rfc3339(),
            ],
        )?;

        for file_template in &template.file_templates {
            tx.execute(
                "INSERT INTO file_templates (template_id, path, content_template)
                 VALUES (?1, ?2, ?3)",
                params![
                    template.id.to_string(),
                    file_template.path,
                    file_template.content_template,
                ],
            )?;
        }

        for param in &template.parameters {
            tx.execute(
                "INSERT INTO template_parameters (
                    template_id, name, display_name, description,
                    param_type, default_value, required, choices
                ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
                params![
                    template.id.to_string(),
                    param.name,
                    param.display_name,
                    param.description,
                    param_type_to_string(&param.param_type),
                    param.default_value.as_ref().map(|v| v.to_string()),
                    param.required as i32,
                    param.choices.as_ref().map(|c| serde_json::to_string(c).unwrap()),
                ],
            )?;
        }

        tx.commit()?;
        Ok(())
    }

    pub fn get_template(&self, id: Uuid) -> Result<Option<ProjectTemplate>> {
        let id_str = id.to_string();

        let template_row = self.conn.query_row(
            "SELECT id, name, description, kind, created_at FROM project_templates WHERE id = ?1",
            params![id_str],
            |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, String>(3)?,
                    row.get::<_, String>(4)?,
                ))
            },
        ).optional()?;

        match template_row {
            None => Ok(None),
            Some((id, name, description, kind_str, created_at_str)) => {
                let file_templates = self.load_file_templates(&id)?;
                let parameters = self.load_parameters(&id)?;

                Ok(Some(ProjectTemplate {
                    id: Uuid::parse_str(&id)?,
                    name,
                    description,
                    kind: string_to_kind(&kind_str),
                    file_templates,
                    parameters,
                    created_at: DateTime::parse_from_rfc3339(&created_at_str)?.with_timezone(&Utc),
                }))
            }
        }
    }

    pub fn list_templates(&self, kind: Option<TemplateKind>) -> Result<Vec<ProjectTemplate>> {
        let sql = match kind {
            Some(_) => "SELECT id, name, description, kind, created_at FROM project_templates WHERE kind = ?1",
            None => "SELECT id, name, description, kind, created_at FROM project_templates",
        };

        let mut stmt = self.conn.prepare(sql)?;

        let rows: Rows;
        if let Some(k) = kind {
            rows = stmt.query(params![kind_to_string(&k)])?;
        } else {
            rows = stmt.query([])?;
        }

        self.rows_to_templates(rows)
    }

    pub fn delete_template(&mut self, id: Uuid) -> Result<()> {
        let id_str = id.to_string();
        let tx = self.conn.transaction()?;

        tx.execute(
            "DELETE FROM template_parameters WHERE template_id = ?1",
            params![id_str],
        )?;

        tx.execute(
            "DELETE FROM file_templates WHERE template_id = ?1",
            params![id_str],
        )?;

        tx.execute(
            "DELETE FROM project_templates WHERE id = ?1",
            params![id_str],
        )?;

        tx.commit()?;
        Ok(())
    }

    fn rows_to_templates(&self, mut rows: Rows) -> Result<Vec<ProjectTemplate>> {
        let mut templates = Vec::new();

        while let Some(row) = rows.next()? {
            let id: String = row.get(0)?;
            let name: String = row.get(1)?;
            let description: String = row.get(2)?;
            let kind_str: String = row.get(3)?;
            let created_at_str: String = row.get(4)?;

            let file_templates = self.load_file_templates(&id)?;
            let parameters = self.load_parameters(&id)?;

            templates.push(ProjectTemplate {
                id: Uuid::parse_str(&id)?,
                name,
                description,
                kind: string_to_kind(&kind_str),
                file_templates,
                parameters,
                created_at: DateTime::parse_from_rfc3339(&created_at_str)?.with_timezone(&Utc),
            });
        }

        Ok(templates)
    }

    fn load_file_templates(&self, template_id: &str) -> Result<Vec<FileTemplate>> {
        let mut stmt = self.conn.prepare(
            "SELECT path, content_template FROM file_templates WHERE template_id = ?1"
        )?;

        let rows = stmt.query_map(params![template_id], |row| {
            Ok(FileTemplate {
                path: row.get(0)?,
                content_template: row.get(1)?,
            })
        })?;

        let mut file_templates = Vec::new();
        for row in rows {
            file_templates.push(row?);
        }

        Ok(file_templates)
    }

    fn load_parameters(&self, template_id: &str) -> Result<Vec<TemplateParameter>> {
        let mut stmt = self.conn.prepare(
            "SELECT name, display_name, description, param_type, default_value, required, choices
             FROM template_parameters WHERE template_id = ?1"
        )?;

        let rows = stmt.query_map(params![template_id], |row| {
            let choices_str: Option<String> = row.get(6)?;
            let choices = choices_str.and_then(|s| serde_json::from_str(&s).ok());

            Ok(TemplateParameter {
                name: row.get(0)?,
                display_name: row.get(1)?,
                description: row.get(2)?,
                param_type: string_to_param_type(&row.get::<_, String>(3)?),
                default_value: row.get::<_, Option<String>>(4)?.and_then(|s| serde_json::from_str(&s).ok()),
                required: row.get::<_, i32>(5)? != 0,
                choices,
            })
        })?;

        let mut params = Vec::new();
        for row in rows {
            params.push(row?);
        }

        Ok(params)
    }
}

fn kind_to_string(kind: &TemplateKind) -> &'static str {
    match kind {
        TemplateKind::Rust => "rust",
        TemplateKind::TypeScript => "typescript",
        TemplateKind::Python => "python",
        TemplateKind::Go => "go",
        TemplateKind::Java => "java",
    }
}

fn string_to_kind(s: &str) -> TemplateKind {
    match s.to_lowercase().as_str() {
        "rust" => TemplateKind::Rust,
        "typescript" => TemplateKind::TypeScript,
        "python" => TemplateKind::Python,
        "go" => TemplateKind::Go,
        "java" => TemplateKind::Java,
        _ => TemplateKind::Rust,
    }
}

fn param_type_to_string(param_type: &ParamType) -> &'static str {
    match param_type {
        ParamType::String => "string",
        ParamType::Boolean => "boolean",
        ParamType::Number => "number",
        ParamType::Choice => "choice",
    }
}

fn string_to_param_type(s: &str) -> ParamType {
    match s.to_lowercase().as_str() {
        "string" => ParamType::String,
        "boolean" => ParamType::Boolean,
        "number" => ParamType::Number,
        "choice" => ParamType::Choice,
        _ => ParamType::String,
    }
}
