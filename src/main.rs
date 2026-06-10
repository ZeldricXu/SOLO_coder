use std::sync::Arc;

use actix_cors::Cors;
use actix_web::{middleware, web, App, HttpResponse, HttpServer};
use chrono::Local;
use tokio_cron_scheduler::{Job, JobScheduler};
use tracing::{error, info, warn};
use tracing_subscriber::{fmt, prelude::*, EnvFilter};
use uuid::Uuid;

use code_review_platform::config::Settings;
use code_review_platform::db::{create_pool, run_migrations, DbPool};
use code_review_platform::handlers::{
    configure_admin_routes, configure_ai_review_routes, configure_auth_routes,
    configure_checklist_routes, configure_comment_routes, configure_dashboard_routes,
    configure_issue_routes, configure_merge_request_routes, configure_notification_routes,
    configure_repo_routes, configure_stats_routes, configure_webhook_routes,
};
use code_review_platform::middleware::{AuthMiddleware, CsrfMiddleware, SessionMiddleware};
use code_review_platform::providers::{
    DingtalkClient, EmailClient, GitHubProvider, GiteeProvider, GitLabProvider, LlmClient,
    MinioClient, RedisClient, SlackClient,
};
use code_review_platform::repositories::{
    AiReviewRepository, ChecklistRepository, CommentRepository, IssueRepository,
    MergeRequestRepository, NotificationRepository, RepoRepository, StatsRepository,
    UserRepository,
};
use code_review_platform::services::{
    AiReviewService, AuthService, ChecklistService, CommentService, DiffService, IssueService,
    MergeRequestService, NotificationService, PermissionRepository, PermissionService,
    RepoService, StatsService, UserService, WebhookService,
};
use code_review_platform::utils::DiffParser;

#[derive(Clone)]
pub struct AppState {
    pub settings: Settings,
    pub db_pool: DbPool,
    pub redis_client: RedisClient,
    pub minio_client: MinioClient,
    pub user_service: UserService,
    pub auth_service: AuthService,
    pub permission_service: PermissionService,
    pub repo_service: RepoService,
    pub webhook_service: WebhookService<NotificationRepository>,
    pub merge_request_service: MergeRequestService,
    pub diff_service: DiffService,
    pub comment_service: CommentService,
    pub checklist_service: ChecklistService<PermissionRepository>,
    pub issue_service: IssueService,
    pub ai_review_service: AiReviewService,
    pub stats_service: StatsService,
    pub notification_service: NotificationService,
    pub diff_parser: DiffParser,
}

async fn send_daily_digest(state: AppState) {
    info!("开始发送每日摘要邮件");
    match state.notification_service.send_daily_digest().await {
        Ok(_) => info!("每日摘要邮件发送成功"),
        Err(e) => error!("每日摘要邮件发送失败: {}", e),
    }
}

async fn health_check() -> HttpResponse {
    HttpResponse::Ok().json(serde_json::json!({
        "status": "ok",
        "timestamp": Local::now().to_rfc3339()
    }))
}

#[actix_web::main]
async fn main() -> anyhow::Result<()> {
    dotenvy::dotenv().ok();

    let settings = Settings::new().expect("Failed to load configuration");

    let env_filter = EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| EnvFilter::new(settings.app.log_level.clone()));

    tracing_subscriber::registry()
        .with(env_filter)
        .with(
            fmt::layer()
                .with_target(true)
                .with_level(true)
                .with_thread_ids(true)
                .with_file(true)
                .with_line_number(true),
        )
        .init();

    info!("Starting {} v{}", settings.app.name, env!("CARGO_PKG_VERSION"));
    info!("Environment: {}", settings.app.environment);
    info!("Log level: {}", settings.app.log_level);

    info!("Connecting to database...");
    let db_pool = create_pool(&settings).await?;
    info!("Database connection established");

    info!("Running database migrations...");
    run_migrations(&db_pool).await?;
    info!("Database migrations completed");

    info!("Connecting to Redis...");
    let redis_client = RedisClient::new(&settings.redis).await?;
    info!("Redis connection established");

    info!("Connecting to MinIO...");
    let minio_client = MinioClient::new(&settings.minio).await?;
    info!("MinIO connection established");

    info!("Initializing providers...");

    let _github_provider = GitHubProvider::new("");
    let _gitlab_provider = GitLabProvider::new("");
    let _gitee_provider = GiteeProvider::new("");

    let llm_client = LlmClient::new(
        settings.llm.api_key.clone(),
        settings.llm.api_base_url.clone(),
        settings.llm.model.clone(),
        settings.llm.max_tokens,
        settings.llm.temperature,
        settings.llm.timeout_secs,
    );

    let slack_client = if settings.slack.webhook_url.is_empty() {
        warn!("Slack webhook URL not configured, Slack notifications disabled");
        None
    } else {
        Some(SlackClient::new(settings.slack.webhook_url.clone()))
    };

    let dingtalk_client = if settings.dingtalk.webhook_url.is_empty() {
        warn!("DingTalk webhook URL not configured, DingTalk notifications disabled");
        None
    } else {
        Some(DingtalkClient::new(
            settings.dingtalk.webhook_url.clone(),
            Some(settings.dingtalk.secret.clone()),
        ))
    };

    let email_client = if settings.email.smtp_host.is_empty() {
        warn!("Email SMTP host not configured, email notifications disabled");
        None
    } else {
        Some(EmailClient::new(
            settings.email.smtp_host.clone(),
            settings.email.smtp_port,
            settings.email.smtp_username.clone(),
            settings.email.smtp_password.clone(),
            settings.email.from_address.clone(),
            settings.email.from_name.clone(),
            settings.email.use_tls,
        ))
    };

    info!("Initializing repositories...");

    let user_repo = UserRepository::new(db_pool.clone());
    let repo_repo = RepoRepository::new(db_pool.clone());
    let mr_repo = MergeRequestRepository::new(db_pool.clone());
    let comment_repo = CommentRepository::new(db_pool.clone());
    let checklist_repo = ChecklistRepository::new(db_pool.clone());
    let issue_repo = IssueRepository::new(db_pool.clone());
    let ai_review_repo = AiReviewRepository::new(db_pool.clone());
    let notification_repo = NotificationRepository::new(db_pool.clone());
    let stats_repo = StatsRepository::new(db_pool.clone());

    info!("Initializing services...");

    let diff_parser = DiffParser::new();

    let permission_service = PermissionService::new(user_repo.clone());
    let notification_service = NotificationService::new(
        notification_repo.clone(),
        user_repo.clone(),
        stats_repo.clone(),
        slack_client,
        dingtalk_client,
        email_client,
    );
    let user_service = UserService::new(user_repo.clone(), notification_repo.clone());
    let auth_service = AuthService::new(
        user_repo.clone(),
        notification_repo.clone(),
        settings.clone(),
        redis_client.clone(),
    );
    let diff_service = DiffService::new(
        diff_parser.clone(),
        minio_client.clone(),
        repo_repo.clone(),
    );
    let comment_permission_repo = PermissionRepository::new();
    let comment_service = CommentService::new(
        comment_repo.clone(),
        notification_repo.clone(),
        comment_permission_repo.clone(),
        user_repo.clone(),
    );
    let checklist_service =
        ChecklistService::new(checklist_repo.clone(), comment_permission_repo.clone());
    let issue_service = IssueService::new(
        issue_repo.clone(),
        notification_repo.clone(),
        permission_service.clone(),
        stats_repo.clone(),
    );
    let ai_review_service = AiReviewService::new(
        ai_review_repo.clone(),
        llm_client,
        diff_service.clone(),
        issue_repo.clone(),
    );
    let stats_service = StatsService::new(stats_repo.clone(), minio_client.clone());
    let merge_request_service = MergeRequestService::new(
        mr_repo.clone(),
        repo_repo.clone(),
        user_repo.clone(),
        comment_repo.clone(),
        minio_client.clone(),
        diff_service.clone(),
    );
    let webhook_service = WebhookService::new(
        repo_repo.clone(),
        mr_repo.clone(),
        user_repo.clone(),
        redis_client.clone(),
        notification_repo.clone(),
    );
    let repo_service = RepoService::new(
        repo_repo.clone(),
        user_repo.clone(),
        redis_client.clone(),
        minio_client.clone(),
        Arc::new(GitHubProvider::new("")),
    );

    info!("Initializing application state...");

    let app_state = AppState {
        settings: settings.clone(),
        db_pool: db_pool.clone(),
        redis_client: redis_client.clone(),
        minio_client: minio_client.clone(),
        user_service: user_service.clone(),
        auth_service: auth_service.clone(),
        permission_service: permission_service.clone(),
        repo_service: repo_service.clone(),
        webhook_service: webhook_service.clone(),
        merge_request_service: merge_request_service.clone(),
        diff_service: diff_service.clone(),
        comment_service: comment_service.clone(),
        checklist_service: checklist_service.clone(),
        issue_service: issue_service.clone(),
        ai_review_service: ai_review_service.clone(),
        stats_service: stats_service.clone(),
        notification_service: notification_service.clone(),
        diff_parser: diff_parser.clone(),
    };

    info!("Starting cron scheduler...");
    let scheduler = JobScheduler::new().await?;

    let state_for_cron = app_state.clone();
    let cron_expr = settings.app.daily_digest_cron.clone();
    info!("Scheduling daily digest with cron: {}", cron_expr);

    let job = Job::new_async(&cron_expr, move |_uuid, _l| {
        let state = state_for_cron.clone();
        Box::pin(async move {
            send_daily_digest(state).await;
        })
    })?;

    scheduler.add(job).await?;
    scheduler.start().await?;
    info!("Cron scheduler started");

    let server_addr = settings.server_addr();
    info!("Starting HTTP server on {}", server_addr);

    let secret_key = settings.session.secret_key.clone();
    let session_ttl = settings.session_ttl();
    let cookie_name = settings.session.cookie_name.clone();
    let cookie_secure = settings.session.cookie_secure;
    let cookie_http_only = settings.session.cookie_http_only;

    let server = HttpServer::new(move || {
        let cors = Cors::default()
            .allowed_origin_fn(|origin, _req_head| {
                origin.as_bytes().ends_with(b"localhost:8080")
                    || origin.as_bytes().ends_with(b"127.0.0.1:8080")
            })
            .allowed_methods(vec!["GET", "POST", "PUT", "DELETE", "OPTIONS"])
            .allowed_headers(vec![
                actix_web::http::header::AUTHORIZATION,
                actix_web::http::header::CONTENT_TYPE,
                actix_web::http::header::ACCEPT,
            ])
            .max_age(3600);

        let session_middleware = SessionMiddleware::new(
            redis_client.clone(),
            secret_key.clone(),
            session_ttl,
            cookie_name.clone(),
            cookie_secure,
            cookie_http_only,
        );

        App::new()
            .app_data(web::Data::new(app_state.settings.clone()))
            .app_data(web::Data::new(app_state.db_pool.clone()))
            .app_data(web::Data::new(app_state.redis_client.clone()))
            .app_data(web::Data::new(app_state.minio_client.clone()))
            .app_data(web::Data::new(app_state.user_service.clone()))
            .app_data(web::Data::new(app_state.auth_service.clone()))
            .app_data(web::Data::new(app_state.permission_service.clone()))
            .app_data(web::Data::new(app_state.repo_service.clone()))
            .app_data(web::Data::new(app_state.webhook_service.clone()))
            .app_data(web::Data::new(app_state.merge_request_service.clone()))
            .app_data(web::Data::new(app_state.diff_service.clone()))
            .app_data(web::Data::new(app_state.comment_service.clone()))
            .app_data(web::Data::new(app_state.checklist_service.clone()))
            .app_data(web::Data::new(app_state.issue_service.clone()))
            .app_data(web::Data::new(app_state.ai_review_service.clone()))
            .app_data(web::Data::new(app_state.stats_service.clone()))
            .app_data(web::Data::new(app_state.notification_service.clone()))
            .app_data(web::Data::new(app_state.diff_parser.clone()))
            .wrap(middleware::Compress::default())
            .wrap(tracing_actix_web::TracingLogger::default())
            .wrap(cors)
            .wrap(session_middleware)
            .wrap(CsrfMiddleware::new())
            .wrap(AuthMiddleware::new(app_state.auth_service.clone()))
            .service(web::scope("/static").service(
                actix_web::fs::Files::new("", "./static")
                    .index_file("index.html")
                    .use_last_modified(true)
                    .use_etag(true),
            ))
            .route("/health", web::get().to(health_check))
            .configure(configure_webhook_routes::<NotificationRepository>)
            .configure(configure_auth_routes)
            .configure(configure_dashboard_routes)
            .configure(configure_repo_routes)
            .configure(configure_merge_request_routes)
            .configure(configure_admin_routes)
            .configure(configure_ai_review_routes)
            .configure(configure_checklist_routes)
            .configure(configure_comment_routes)
            .configure(configure_issue_routes)
            .configure(configure_notification_routes)
            .configure(configure_stats_routes)
            .default_service(web::route().to(|| async {
                HttpResponse::NotFound().json(serde_json::json!({
                    "error": "Not Found",
                    "message": "The requested resource was not found"
                }))
            }))
    })
    .bind(&server_addr)?
    .run();

    info!("Server is running. Press Ctrl+C to stop.");

    tokio::select! {
        result = server => {
            if let Err(e) = result {
                error!("Server error: {}", e);
            }
        }
        _ = tokio::signal::ctrl_c() => {
            info!("Received shutdown signal");
        }
    }

    info!("Shutting down cron scheduler...");
    scheduler.shutdown().await?;

    info!("Server shutdown complete");
    Ok(())
}
