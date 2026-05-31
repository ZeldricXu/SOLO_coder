use rusqlite::Connection;
use uuid::Uuid;
use anyhow::Result;
use serde_json::Value;
use std::collections::HashMap;
use crate::models::{ProjectTemplate, TemplateKind, GenerationRequest, GenerationResult, InteractiveQuestion};
use crate::template::TemplateManager;
use crate::generator::ProjectGenerator;
use crate::question::QuestionFlow;

pub fn add_template(conn: Connection, template: ProjectTemplate) -> Result<()> {
    let mut manager = TemplateManager::new(conn);
    manager.add_template(template)
}

pub fn get_template(conn: Connection, id: Uuid) -> Result<Option<ProjectTemplate>> {
    let manager = TemplateManager::new(conn);
    manager.get_template(id)
}

pub fn list_templates(conn: Connection, kind: Option<TemplateKind>) -> Result<Vec<ProjectTemplate>> {
    let manager = TemplateManager::new(conn);
    manager.list_templates(kind)
}

pub fn delete_template(conn: Connection, id: Uuid) -> Result<()> {
    let mut manager = TemplateManager::new(conn);
    manager.delete_template(id)
}

pub fn generate_project(template: &ProjectTemplate, request: &GenerationRequest) -> Result<GenerationResult> {
    ProjectGenerator::generate(template, request)
}

pub fn get_questions(template: &ProjectTemplate) -> Vec<InteractiveQuestion> {
    QuestionFlow::get_questions(template)
}

pub fn validate_answers(answers: &HashMap<String, Value>, params: &[crate::models::TemplateParameter]) -> Result<()> {
    QuestionFlow::validate_answers(answers, params)
}

pub fn init_schema(conn: Connection) -> Result<()> {
    let manager = TemplateManager::new(conn);
    manager.init_schema()
}
