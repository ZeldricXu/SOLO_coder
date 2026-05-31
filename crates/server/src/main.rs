use std::sync::Arc;

use axum::{
    extract::State,
    http::StatusCode,
    response::IntoResponse,
    routing::{get, post},
    Json, Router,
};
use serde::Serialize;
use tokio::sync::Mutex;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

type Shared<T> = Arc<Mutex<T>>;

#[derive(Clone)]
struct AppState {
    catalog_registry: Shared<catalog::registry::CatalogRegistry>,
    quality_manager: Shared<quality_gate::rules::RuleManager>,
    quality_checker: Shared<quality_gate::checker::QualityChecker>,
    vuln_db: Shared<vuln_analysis::vulnerability::VulnerabilityDatabase>,
    provisioner: Shared<env_provision::provisioner::EnvironmentProvisioner>,
    toggle_manager: Shared<feature_toggle::toggle::ToggleManager>,
    contract_conn: Shared<rusqlite::Connection>,
    template_manager: Shared<scaffold::template::TemplateManager>,
    doc_conn: Shared<rusqlite::Connection>,
}

#[derive(Serialize)]
struct ApiResponse<T> {
    success: bool,
    data: Option<T>,
    error: Option<String>,
}

impl<T> ApiResponse<T> {
    fn ok(data: T) -> Self {
        Self { success: true, data: Some(data), error: None }
    }
    fn err(msg: String) -> Self {
        Self { success: false, data: None, error: Some(msg) }
    }
}

async fn health_check() -> impl IntoResponse {
    Json(ApiResponse::ok(serde_json::json!({ "status": "ok" })))
}

fn create_db(path: &str) -> rusqlite::Connection {
    rusqlite::Connection::open(path).expect("Failed to open database")
}

async fn register_service(
    State(state): State<AppState>,
    Json(entry): Json<catalog::models::ServiceEntry>,
) -> impl IntoResponse {
    let registry = state.catalog_registry.lock().await;
    match catalog::handlers::register_service(&registry, entry) {
        Ok(()) => (StatusCode::OK, Json(ApiResponse::ok(()))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(ApiResponse::<()>::err(e.to_string()))),
    }
}

async fn list_services(State(state): State<AppState>) -> impl IntoResponse {
    let registry = state.catalog_registry.lock().await;
    match catalog::handlers::list_services(&registry) {
        Ok(items) => (StatusCode::OK, Json(ApiResponse::ok(items))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(ApiResponse::<Vec<catalog::models::ServiceEntry>>::err(e.to_string()))),
    }
}

async fn add_rule(
    State(state): State<AppState>,
    Json(rule): Json<quality_gate::models::RuleDefinition>,
) -> impl IntoResponse {
    let manager = state.quality_manager.lock().await;
    match manager.add_rule(rule.clone()) {
        Ok(()) => (StatusCode::OK, Json(ApiResponse::ok(rule))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(ApiResponse::<quality_gate::models::RuleDefinition>::err(e.to_string()))),
    }
}

async fn list_rules(State(state): State<AppState>) -> impl IntoResponse {
    let manager = state.quality_manager.lock().await;
    match manager.list_rules(None) {
        Ok(items) => (StatusCode::OK, Json(ApiResponse::ok(items))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(ApiResponse::<Vec<quality_gate::models::RuleDefinition>>::err(e.to_string()))),
    }
}

async fn run_check(
    State(state): State<AppState>,
    Json(req): Json<quality_gate::models::CheckRequest>,
) -> impl IntoResponse {
    let manager = state.quality_manager.lock().await;
    let checker = state.quality_checker.lock().await;
    match checker.check(req, &manager) {
        Ok(report) => (StatusCode::OK, Json(ApiResponse::ok(report))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(ApiResponse::<quality_gate::models::QualityReport>::err(e.to_string()))),
    }
}

async fn parse_sbom(Json(sbom_json): Json<String>) -> impl IntoResponse {
    match vuln_analysis::sbom::SbomParser::parse(&sbom_json) {
        Ok(doc) => (StatusCode::OK, Json(ApiResponse::ok(doc))),
        Err(e) => (StatusCode::BAD_REQUEST, Json(ApiResponse::<vuln_analysis::models::SbomDocument>::err(e.to_string()))),
    }
}

async fn analyze_sbom(
    State(state): State<AppState>,
    Json(sbom_json): Json<String>,
) -> impl IntoResponse {
    let db = state.vuln_db.lock().await;
    match vuln_analysis::sbom::SbomParser::parse(&sbom_json) {
        Ok(doc) => {
            let matches = db.match_vulnerabilities(&doc);
            let recommendations = vuln_analysis::recommendation::RecommendationEngine::generate_recommendations(&matches);
            let risk_score = vuln_analysis::recommendation::RecommendationEngine::calculate_risk_score(&matches);
            let report = vuln_analysis::models::AnalysisReport {
                id: uuid::Uuid::new_v4(),
                sbom_name: doc.name,
                analyzed_at: chrono::Utc::now(),
                total_packages: doc.packages.len(),
                vulnerable_packages: matches.len(),
                matches,
                recommendations,
                risk_score,
            };
            (StatusCode::OK, Json(ApiResponse::ok(report)))
        },
        Err(e) => (StatusCode::BAD_REQUEST, Json(ApiResponse::<vuln_analysis::models::AnalysisReport>::err(e.to_string()))),
    }
}

async fn create_toggle(
    State(state): State<AppState>,
    Json(toggle): Json<feature_toggle::models::FeatureToggle>,
) -> impl IntoResponse {
    let manager = state.toggle_manager.lock().await;
    match manager.create_toggle(&toggle) {
        Ok(()) => (StatusCode::OK, Json(ApiResponse::ok(()))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(ApiResponse::<()>::err(e.to_string()))),
    }
}

async fn list_toggles(State(state): State<AppState>) -> impl IntoResponse {
    let manager = state.toggle_manager.lock().await;
    match manager.list_toggles() {
        Ok(items) => (StatusCode::OK, Json(ApiResponse::ok(items))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(ApiResponse::<Vec<feature_toggle::models::FeatureToggle>>::err(e.to_string()))),
    }
}

async fn evaluate_toggle(
    State(state): State<AppState>,
    Json(req): Json<feature_toggle::models::EvaluationRequest>,
) -> impl IntoResponse {
    let manager = state.toggle_manager.lock().await;
    match manager.get_toggle(req.toggle_id) {
        Ok(Some(toggle)) => {
            let result = feature_toggle::rollout::RolloutEngine::evaluate(&toggle, &req.user_ctx);
            (StatusCode::OK, Json(ApiResponse::ok(result)))
        },
        Ok(None) => (StatusCode::NOT_FOUND, Json(ApiResponse::<feature_toggle::models::EvaluationResult>::err("Toggle not found".to_string()))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(ApiResponse::<feature_toggle::models::EvaluationResult>::err(e.to_string()))),
    }
}

async fn provision_env(
    State(state): State<AppState>,
    Json(req): Json<env_provision::models::ProvisionRequest>,
) -> impl IntoResponse {
    let provisioner = state.provisioner.lock().await;
    match provisioner.provision(req) {
        Ok(env) => (StatusCode::OK, Json(ApiResponse::ok(env))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(ApiResponse::<env_provision::models::PreviewEnvironment>::err(e.to_string()))),
    }
}

async fn list_envs(State(state): State<AppState>) -> impl IntoResponse {
    let provisioner = state.provisioner.lock().await;
    match provisioner.list_environments(None) {
        Ok(items) => (StatusCode::OK, Json(ApiResponse::ok(items))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(ApiResponse::<Vec<env_provision::models::PreviewEnvironment>>::err(e.to_string()))),
    }
}

async fn terminate_env(
    State(state): State<AppState>,
    Json(env_id): Json<uuid::Uuid>,
) -> impl IntoResponse {
    let provisioner = state.provisioner.lock().await;
    match provisioner.terminate(env_id) {
        Ok(()) => (StatusCode::OK, Json(ApiResponse::ok(()))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(ApiResponse::<()>::err(e.to_string()))),
    }
}

async fn add_template(
    State(state): State<AppState>,
    Json(template): Json<scaffold::models::ProjectTemplate>,
) -> impl IntoResponse {
    let mut manager = state.template_manager.lock().await;
    match manager.add_template(template) {
        Ok(()) => (StatusCode::OK, Json(ApiResponse::ok(()))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(ApiResponse::<()>::err(e.to_string()))),
    }
}

async fn list_templates(State(state): State<AppState>) -> impl IntoResponse {
    let manager = state.template_manager.lock().await;
    match manager.list_templates(None) {
        Ok(items) => (StatusCode::OK, Json(ApiResponse::ok(items))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(ApiResponse::<Vec<scaffold::models::ProjectTemplate>>::err(e.to_string()))),
    }
}

async fn generate_project(
    State(state): State<AppState>,
    Json(req): Json<scaffold::models::GenerationRequest>,
) -> impl IntoResponse {
    let manager = state.template_manager.lock().await;
    match manager.get_template(req.template_id) {
        Ok(Some(template)) => {
            match scaffold::generator::ProjectGenerator::generate(&template, &req) {
                Ok(result) => (StatusCode::OK, Json(ApiResponse::ok(result))),
                Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(ApiResponse::<scaffold::models::GenerationResult>::err(e.to_string()))),
            }
        },
        Ok(None) => (StatusCode::NOT_FOUND, Json(ApiResponse::<scaffold::models::GenerationResult>::err("Template not found".to_string()))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(ApiResponse::<scaffold::models::GenerationResult>::err(e.to_string()))),
    }
}

async fn upload_contract(
    State(state): State<AppState>,
    Json(contract): Json<api_contract::models::ApiContract>,
) -> impl IntoResponse {
    let conn = state.contract_conn.lock().await;
    match conn.execute(
        "INSERT INTO contracts (id, name, schema_type, schema_content, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        rusqlite::params![
            contract.id.to_string(),
            contract.name,
            serde_json::to_string(&contract.schema_type).unwrap(),
            contract.schema_content,
            contract.version,
            contract.created_at.to_rfc3339(),
            contract.updated_at.to_rfc3339(),
        ],
    ) {
        Ok(_) => (StatusCode::OK, Json(ApiResponse::ok(()))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(ApiResponse::<()>::err(e.to_string()))),
    }
}

async fn validate_contract(
    Json(req): Json<api_contract::models::ValidationRequest>,
) -> impl IntoResponse {
    let result = api_contract::validator::ContractValidator::validate_openapi(&req.content, api_contract::models::ValidationLevel::Strict);
    (StatusCode::OK, Json(ApiResponse::ok(result)))
}

async fn index_document(
    State(state): State<AppState>,
    Json(doc): Json<doc_index::models::Document>,
) -> impl IntoResponse {
    let conn = state.doc_conn.lock().await;
    match conn.execute(
        "INSERT INTO documents (id, title, content, source, source_url, author, team_owner, permissions, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        rusqlite::params![
            doc.id.to_string(),
            doc.title,
            doc.content,
            serde_json::to_string(&doc.source).unwrap(),
            doc.source_url,
            doc.author,
            doc.team_owner,
            serde_json::to_string(&doc.permissions).unwrap(),
            doc.created_at.to_rfc3339(),
            doc.updated_at.to_rfc3339(),
        ],
    ) {
        Ok(_) => (StatusCode::OK, Json(ApiResponse::ok(()))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(ApiResponse::<()>::err(e.to_string()))),
    }
}

async fn search_documents(
    State(state): State<AppState>,
    Json(_query): Json<doc_index::models::SearchQuery>,
) -> impl IntoResponse {
    let conn = state.doc_conn.lock().await;
    let result: anyhow::Result<Vec<doc_index::models::SearchResult>> = (|| {
        let mut stmt = conn.prepare("SELECT id, title, content, source, source_url, author, team_owner, permissions, created_at, updated_at FROM documents")?;
        let docs: Vec<doc_index::models::Document> = stmt.query_map([], |row| {
            let id_str: String = row.get(0)?;
            let source_str: String = row.get(3)?;
            let perm_str: String = row.get(7)?;
            let created_str: String = row.get(8)?;
            let updated_str: String = row.get(9)?;
            Ok(doc_index::models::Document {
                id: uuid::Uuid::parse_str(&id_str).unwrap_or_default(),
                title: row.get(1)?,
                content: row.get(2)?,
                source: serde_json::from_str(&source_str).unwrap_or(doc_index::models::DocumentSource::Markdown),
                source_url: row.get(4)?,
                author: row.get(5)?,
                team_owner: row.get(6)?,
                permissions: serde_json::from_str(&perm_str).unwrap_or(doc_index::models::PermissionConfig {
                    read_teams: vec![],
                    read_users: vec![],
                    is_public: true,
                }),
                tags: vec![],
                created_at: chrono::DateTime::parse_from_rfc3339(&created_str).unwrap_or_default().with_timezone(&chrono::Utc),
                updated_at: chrono::DateTime::parse_from_rfc3339(&updated_str).unwrap_or_default().with_timezone(&chrono::Utc),
            })
        })?.filter_map(|r| r.ok()).collect();
        let results: Vec<doc_index::models::SearchResult> = docs.into_iter().map(|d| doc_index::models::SearchResult {
            document: d,
            score: 1.0,
        }).collect();
        Ok(results)
    })();
    match result {
        Ok(results) => (StatusCode::OK, Json(ApiResponse::ok(results))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(ApiResponse::<Vec<doc_index::models::SearchResult>>::err(e.to_string()))),
    }
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::registry()
        .with(tracing_subscriber::EnvFilter::new("info"))
        .with(tracing_subscriber::fmt::layer())
        .init();

    let catalog_conn = create_db("catalog.db");
    let catalog_registry = catalog::registry::CatalogRegistry::new(catalog_conn);
    let _ = catalog_registry.init_schema();
    let catalog_registry = Arc::new(Mutex::new(catalog_registry));

    let quality_conn = create_db("quality.db");
    let quality_manager = quality_gate::rules::RuleManager::new(quality_conn);
    let _ = quality_manager.init_schema();
    let quality_manager = Arc::new(Mutex::new(quality_manager));

    let quality_checker = quality_gate::checker::QualityChecker::with_default_strategy();
    let quality_checker = Arc::new(Mutex::new(quality_checker));

    let vuln_conn = create_db("vuln.db");
    let vuln_db = vuln_analysis::vulnerability::VulnerabilityDatabase::new(vuln_conn);
    let _ = vuln_db.init_schema();
    let vuln_db = Arc::new(Mutex::new(vuln_db));

    let provision_conn = create_db("provision.db");
    let provisioner = env_provision::provisioner::EnvironmentProvisioner::new(provision_conn);
    let _ = provisioner.init_schema();
    let provisioner = Arc::new(Mutex::new(provisioner));

    let feature_conn = create_db("feature.db");
    let toggle_manager = feature_toggle::toggle::ToggleManager::new(feature_conn);
    let _ = toggle_manager.init_schema();
    let toggle_manager = Arc::new(Mutex::new(toggle_manager));

    let contract_conn = create_db("contract.db");
    let _ = contract_conn.execute(
        "CREATE TABLE IF NOT EXISTS contracts (id TEXT PRIMARY KEY, name TEXT, schema_type TEXT, schema_content TEXT, version TEXT, created_at TEXT, updated_at TEXT)",
        [],
    );
    let contract_conn = Arc::new(Mutex::new(contract_conn));

    let scaffold_conn = create_db("scaffold.db");
    let template_manager = scaffold::template::TemplateManager::new(scaffold_conn);
    let _ = template_manager.init_schema();
    let template_manager = Arc::new(Mutex::new(template_manager));

    let doc_conn = create_db("doc.db");
    let _ = doc_conn.execute(
        "CREATE TABLE IF NOT EXISTS documents (id TEXT PRIMARY KEY, title TEXT, content TEXT, source TEXT, source_url TEXT, author TEXT, team_owner TEXT, permissions TEXT, created_at TEXT, updated_at TEXT)",
        [],
    );
    let doc_conn = Arc::new(Mutex::new(doc_conn));

    let state = AppState {
        catalog_registry,
        quality_manager,
        quality_checker,
        vuln_db,
        provisioner,
        toggle_manager,
        contract_conn,
        template_manager,
        doc_conn,
    };

    let state_clone = state.clone();
    let catalog_routes = Router::new()
        .route("/services", post(register_service).get(list_services))
        .with_state(state_clone);

    let state_clone = state.clone();
    let quality_routes = Router::new()
        .route("/rules", post(add_rule).get(list_rules))
        .route("/check", post(run_check))
        .with_state(state_clone);

    let state_clone = state.clone();
    let vuln_routes = Router::new()
        .route("/sbom/parse", post(parse_sbom))
        .route("/sbom/analyze", post(analyze_sbom))
        .with_state(state_clone);

    let state_clone = state.clone();
    let feature_routes = Router::new()
        .route("/toggles", post(create_toggle).get(list_toggles))
        .route("/toggles/evaluate", post(evaluate_toggle))
        .with_state(state_clone);

    let state_clone = state.clone();
    let env_routes = Router::new()
        .route("/envs", post(provision_env).get(list_envs))
        .route("/envs/terminate", post(terminate_env))
        .with_state(state_clone);

    let state_clone = state.clone();
    let scaffold_routes = Router::new()
        .route("/templates", post(add_template).get(list_templates))
        .route("/generate", post(generate_project))
        .with_state(state_clone);

    let state_clone = state.clone();
    let contract_routes = Router::new()
        .route("/contracts", post(upload_contract))
        .route("/contracts/validate", post(validate_contract))
        .with_state(state_clone);

    let state_clone = state.clone();
    let doc_routes = Router::new()
        .route("/documents", post(index_document))
        .route("/documents/search", post(search_documents))
        .with_state(state_clone);

    let app = Router::new()
        .route("/health", get(health_check))
        .nest("/api/catalog", catalog_routes)
        .nest("/api/quality", quality_routes)
        .nest("/api/vuln", vuln_routes)
        .nest("/api/feature", feature_routes)
        .nest("/api/env", env_routes)
        .nest("/api/scaffold", scaffold_routes)
        .nest("/api/contract", contract_routes)
        .nest("/api/doc", doc_routes);

    let listener = tokio::net::TcpListener::bind("0.0.0.0:8080").await?;
    tracing::info!("Server listening on http://0.0.0.0:8080");
    axum::serve(listener, app).await?;

    Ok(())
}
