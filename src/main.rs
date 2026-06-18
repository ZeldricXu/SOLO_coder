use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::collections::HashMap;
use std::time::Duration;

use axum::{
    extract::{FromRef, Path, Query, State, WebSocketUpgrade},
    extract::ws::{Message, WebSocket},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Json, Response},
    routing::{get, post},
    Router,
};
use dashmap::DashMap;
use futures::{SinkExt, StreamExt};
use metrics::{counter, gauge, histogram};
use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;
use uuid::Uuid;

use realtime_collab_engine::*;
use realtime_collab_engine::auth::{AuthService, JwtClaims, Role};
use realtime_collab_engine::broadcast::{BroadcastEvent, StreamPublisher};
use realtime_collab_engine::crdt::Op;
use realtime_collab_engine::health::{HealthChecker, HealthCheckResponse, ReadyCheckResponse};
use realtime_collab_engine::presence::{CursorPosition, SelectionRange, PresenceUpdate};
use realtime_collab_engine::storage::{OplogRepository, QueryOplogParams};
use realtime_collab_engine::ws::{ConnectionManager, Room, RoomUser, WsMessage};

#[derive(Clone)]
struct AppRouterState {
    app: Arc<AppState>,
    auth: Arc<AuthService>,
    health: Arc<HealthChecker>,
    broadcaster: Arc<StreamPublisher>,
    node_id: String,
}

impl FromRef<AppRouterState> for Arc<AppState> {
    fn from_ref(state: &AppRouterState) -> Self {
        state.app.clone()
    }
}

impl FromRef<AppRouterState> for Arc<AuthService> {
    fn from_ref(state: &AppRouterState) -> Self {
        state.auth.clone()
    }
}

impl FromRef<AppRouterState> for Arc<HealthChecker> {
    fn from_ref(state: &AppRouterState) -> Self {
        state.health.clone()
    }
}

impl FromRef<AppRouterState> for Arc<StreamPublisher> {
    fn from_ref(state: &AppRouterState) -> Self {
        state.broadcaster.clone()
    }
}

#[derive(Debug, Deserialize)]
struct CreateDocumentRequest {
    title: Option<String>,
    initial_content: Option<String>,
}

#[derive(Debug, Serialize)]
struct CreateDocumentResponse {
    id: Uuid,
    title: String,
    created_at: chrono::DateTime<chrono::Utc>,
    owner_id: String,
}

#[derive(Debug, Deserialize)]
struct ShareRequest {
    role: String,
    expires_at: Option<chrono::DateTime<chrono::Utc>>,
}

#[derive(Debug, Serialize)]
struct ShareResponse {
    token: String,
    share_id: Uuid,
    expires_at: Option<chrono::DateTime<chrono::Utc>>,
    role: String,
}

#[derive(Debug, Deserialize)]
struct OplogQueryParams {
    from_time: Option<chrono::DateTime<chrono::Utc>>,
    to_time: Option<chrono::DateTime<chrono::Utc>>,
    user_id: Option<String>,
    seq_from: Option<i64>,
    seq_to: Option<i64>,
    limit: Option<i64>,
    offset: Option<i64>,
}

#[derive(Debug, Serialize)]
struct OplogEntryResponse {
    sequence: i64,
    op_type: String,
    user_id: String,
    timestamp: chrono::DateTime<chrono::Utc>,
    payload: serde_json::Value,
}

fn main() {
    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(num_cpus::get())
        .enable_all()
        .build()
        .unwrap();

    rt.block_on(async_main());
}

async fn async_main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info,collab_engine=debug,tower_http=warn,sqlx=warn".into()),
        )
        .with_target(true)
        .json()
        .init();

    init_metrics();

    let config = AppConfig::from_env();
    tracing::info!("Starting collab-engine on {}:{}", config.server.host, config.server.port);

    let node_id = format!("node-{}", Uuid::new_v4().simple());
    tracing::info!("Node ID: {}", node_id);

    let db_pool = setup_database(&config).await;
    let redis_pool = setup_redis(&config).await;

    let auth_service = Arc::new(AuthService::new(config.auth.clone()));
    let repo = OplogRepository::new(db_pool.clone());
    repo.init_schema().await.expect("Failed to init DB schema");

    let ws_manager = ConnectionManager::new(config.clone());
    let presence = presence::PresenceTracker::new();
    let rate_limiter = ratelimit::RateLimiter::new(config.ratelimit.clone());
    let snapshot_service = snapshot::SnapshotService::new(config.clone(), repo);

    let stream_prefix = config.redis.pubsub_channel_prefix.clone();
    let broadcaster = Arc::new(StreamPublisher::new(redis_pool.clone(), stream_prefix));

    let (consumer, subscribe_tx) = broadcast::StreamConsumer::new(
        redis_pool.clone(),
        config.redis.pubsub_channel_prefix.clone(),
        node_id.clone(),
        ws_manager.clone(),
    );
    tokio::spawn(async move {
        consumer.consume_loop().await;
    });

    let app_state = Arc::new(AppState {
        config: config.clone(),
        db_pool: db_pool.clone(),
        redis_pool: redis_pool.clone(),
        ws_manager: ws_manager.clone(),
        presence_tracker: presence.clone(),
        rate_limiter: rate_limiter.clone(),
        snapshot_service: snapshot_service.clone(),
        broadcaster: broadcaster.clone(),
        active_connections: std::sync::atomic::AtomicUsize::new(0),
        started_at: chrono::Utc::now(),
    });

    let health_checker = Arc::new(
        HealthChecker::new()
            .with_db(db_pool.clone())
            .with_redis(redis_pool.clone())
            .with_ws(ws_manager.clone())
            .with_presence(presence.clone())
            .with_ratelimit(rate_limiter.clone())
            .with_state(app_state.clone())
    );

    let router_state = AppRouterState {
        app: app_state.clone(),
        auth: auth_service.clone(),
        health: health_checker.clone(),
        broadcaster: broadcaster.clone(),
        node_id: node_id.clone(),
    };

    start_background_tasks(
        config.clone(),
        ws_manager.clone(),
        presence.clone(),
        rate_limiter.clone(),
        snapshot_service.clone(),
        app_state.clone(),
        subscribe_tx.clone(),
    );

    drop(broadcaster);

    let app = build_router(router_state.clone());

    let addr = format!("{}:{}", config.server.host, config.server.port);
    let listener = tokio::net::TcpListener::bind(&addr).await
        .expect("Failed to bind TCP listener");
    tracing::info!("Listening on http://{}", addr);

    axum::serve(listener, app)
        .await
        .expect("Server failed");
}

fn build_router(state: AppRouterState) -> Router {
    Router::new()
        .route("/health", get(health_handler))
        .route("/ready", get(ready_handler))
        .route("/metrics", get(metrics_handler))
        .route("/ws/:document_id", get(ws_upgrade_handler))
        .route("/api/v1/documents", post(create_document_handler))
        .route("/api/v1/documents/:id/share", post(share_document_handler))
        .route("/api/v1/documents/:id/oplog", get(oplog_query_handler))
        .route("/api/v1/documents/:id/versions", get(version_list_handler))
        .route("/api/v1/documents/:id/versions/:version", get(version_restore_handler))
        .with_state(state)
}

async fn setup_database(config: &AppConfig) -> sqlx::PgPool {
    loop {
        let opts = sqlx::postgres::PgPoolOptions::new()
            .max_connections(config.database.max_connections)
            .min_connections(config.database.min_connections)
            .acquire_timeout(Duration::from_secs(config.database.acquire_timeout_secs));
        match opts.connect(&config.database.url).await {
            Ok(pool) => {
                tracing::info!("Connected to PostgreSQL");
                return pool;
            }
            Err(e) => {
                tracing::warn!("DB connection failed: {}, retrying in 3s...", e);
                tokio::time::sleep(Duration::from_secs(3)).await;
            }
        }
    }
}

async fn setup_redis(config: &AppConfig) -> bb8::Pool<bb8_redis::RedisConnectionManager> {
    loop {
        match bb8_redis::RedisConnectionManager::new(config.redis.url.as_str()) {
            Ok(manager) => {
                match bb8::Pool::builder()
                    .max_size(config.redis.max_connections)
                    .build(manager)
                    .await
                {
                    Ok(pool) => {
                        tracing::info!("Connected to Redis");
                        return pool;
                    }
                    Err(e) => {
                        tracing::warn!("Redis pool create failed: {}, retrying in 3s...", e);
                        tokio::time::sleep(Duration::from_secs(3)).await;
                    }
                }
            }
            Err(e) => {
                tracing::warn!("Redis manager failed: {}, retrying in 3s...", e);
                tokio::time::sleep(Duration::from_secs(3)).await;
            }
        }
    }
}

fn extract_claims(headers: &HeaderMap, auth: &AuthService) -> Result<JwtClaims, AppError> {
    let auth_header = headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|h| h.to_str().ok())
        .ok_or_else(|| AppError::Auth(auth::AuthError::InvalidToken("Missing Authorization header".into())))?;

    let token = AuthService::extract_bearer_token(auth_header)?;
    let claims = auth.verify_token(token)?;
    Ok(claims)
}

async fn health_handler(
    State(health): State<Arc<HealthChecker>>,
) -> impl IntoResponse {
    let h = health.check_health().await;
    let status = match h.status_code {
        200 => StatusCode::OK,
        503 => StatusCode::SERVICE_UNAVAILABLE,
        _ => StatusCode::OK,
    };
    (status, Json(h))
}

async fn ready_handler(
    State(health): State<Arc<HealthChecker>>,
    State(app): State<Arc<AppState>>,
) -> impl IntoResponse {
    let r = health.check_ready().await;
    let mut status = if r.ready { StatusCode::OK } else { StatusCode::SERVICE_UNAVAILABLE };

    if !app.can_accept_connection() {
        status = StatusCode::SERVICE_UNAVAILABLE;
    }

    let mut resp = Json(r).into_response();
    *resp.status_mut() = status;

    if status == StatusCode::SERVICE_UNAVAILABLE {
        resp.headers_mut().insert(
            axum::http::header::RETRY_AFTER,
            "30".parse().unwrap(),
        );
    }
    resp
}

async fn metrics_handler() -> impl IntoResponse {
    use metrics_exporter_prometheus::PrometheusHandle;
    // Metrics are exported by the recorder's own HTTP listener on :9000.
    // Return info here too for convenience.
    StatusCode::SEE_OTHER
}

async fn create_document_handler(
    State(app): State<Arc<AppState>>,
    State(auth): State<Arc<AuthService>>,
    headers: HeaderMap,
    Json(req): Json<CreateDocumentRequest>,
) -> Result<impl IntoResponse, AppError> {
    let claims = extract_claims(&headers, &auth)?;
    let id = Uuid::new_v4();
    let title = req.title.unwrap_or_else(|| "Untitled".to_string());
    let owner_id = claims.user_id.clone();

    let repo = OplogRepository::new(app.db_pool.clone());
    let meta = repo.create_document(id, &title, &owner_id).await?;

    if let Some(init) = req.initial_content.as_ref() {
        let client_id = rand::random::<u64>();
        let room = app.ws_manager.get_or_create_room(id, client_id);
        let mut doc = room.document.write();
        for ch in init.chars() {
            let pos = doc.content_length();
            if let Ok(_op) = doc.insert_local(pos, ch) {}
        }
        drop(doc);
    }

    counter!("collab_documents_created_total").increment(1);

    Ok(Json(CreateDocumentResponse {
        id: meta.id,
        title: meta.title,
        created_at: meta.created_at,
        owner_id: meta.owner_id,
    }))
}

async fn share_document_handler(
    State(app): State<Arc<AppState>>,
    State(auth): State<Arc<AuthService>>,
    Path(id): Path<Uuid>,
    headers: HeaderMap,
    Json(req): Json<ShareRequest>,
) -> Result<impl IntoResponse, AppError> {
    let claims = extract_claims(&headers, &auth)?;
    AuthService::check_document_permission(&claims, &id, &Role::Owner)
        .map_err(AppError::Auth)?;

    let role = Role::from_str(&req.role)
        .ok_or_else(|| AppError::InvalidInput(format!("Unknown role: {}", req.role)))?;

    let (token, share_claims) = auth.generate_share_token(
        id,
        claims.user_id.clone(),
        role.clone(),
        req.expires_at,
    ).map_err(AppError::Auth)?;

    counter!("collab_shares_created_total", "role" => role.to_str().to_string()).increment(1);

    Ok(Json(ShareResponse {
        token,
        share_id: share_claims.share_id,
        expires_at: req.expires_at,
        role: role.to_str().to_string(),
    }))
}

async fn oplog_query_handler(
    State(app): State<Arc<AppState>>,
    State(auth): State<Arc<AuthService>>,
    Path(id): Path<Uuid>,
    headers: HeaderMap,
    Query(params): Query<OplogQueryParams>,
) -> Result<impl IntoResponse, AppError> {
    let claims = extract_claims(&headers, &auth)?;
    AuthService::check_document_permission(&claims, &id, &Role::Viewer)
        .map_err(AppError::Auth)?;

    let repo = OplogRepository::new(app.db_pool.clone());
    let entries = repo.query_oplogs(QueryOplogParams {
        document_id: id,
        from_time: params.from_time,
        to_time: params.to_time,
        user_id: params.user_id,
        sequence_from: params.seq_from,
        sequence_to: params.seq_to,
        limit: Some(params.limit.unwrap_or(1000)),
        offset: params.offset,
    }).await?;

    let resp: Vec<OplogEntryResponse> = entries.into_iter().map(|e| OplogEntryResponse {
        sequence: e.sequence,
        op_type: e.op_type,
        user_id: e.user_id,
        timestamp: e.timestamp,
        payload: e.op_payload,
    }).collect();

    Ok(Json(resp))
}

async fn version_list_handler(
    State(app): State<Arc<AppState>>,
    State(auth): State<Arc<AuthService>>,
    Path(id): Path<Uuid>,
    headers: HeaderMap,
) -> Result<impl IntoResponse, AppError> {
    let claims = extract_claims(&headers, &auth)?;
    AuthService::check_document_permission(&claims, &id, &Role::Viewer)
        .map_err(AppError::Auth)?;

    let repo = OplogRepository::new(app.db_pool.clone());
    let snaps = repo.get_snapshots(id, 50).await?;

    Ok(Json(snaps))
}

async fn version_restore_handler(
    State(app): State<Arc<AppState>>,
    State(auth): State<Arc<AuthService>>,
    Path((id, version)): Path<(Uuid, i64)>,
    headers: HeaderMap,
) -> Result<impl IntoResponse, AppError> {
    let claims = extract_claims(&headers, &auth)?;
    AuthService::check_document_permission(&claims, &id, &Role::Editor)
        .map_err(AppError::Auth)?;

    let snapshot = app.snapshot_service.restore_version(id, version).await?;
    counter!("collab_versions_restored_total").increment(1);

    Ok(Json(serde_json::json!({
        "document_id": snapshot.document_id,
        "created_at": snapshot.created_at,
        "ops_count": snapshot.ops_count,
        "vector_clock": snapshot.vector_clock,
    })))
}

async fn ws_upgrade_handler(
    State(state): State<AppRouterState>,
    Path(document_id): Path<Uuid>,
    headers: HeaderMap,
    ws: WebSocketUpgrade,
) -> Result<impl IntoResponse, AppError> {
    let auth_header = headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|h| h.to_str().ok());

    let claims = if let Some(header) = auth_header {
        let token = AuthService::extract_bearer_token(header)?;
        match state.auth.verify_token(token) {
            Ok(c) => c,
            Err(e) => {
                match state.auth.verify_share_token(token) {
                    Ok(share) => {
                        let role = Role::from_str(&share.role).unwrap_or(Role::Viewer);
                        JwtClaims {
                            sub: format!("share:{}", share.share_id),
                            user_id: format!("guest:{}", &share.share_id.to_string()[..8]),
                            email: None,
                            name: Some("Guest".to_string()),
                            iss: state.auth.config.jwt_issuer.clone(),
                            aud: "collab-engine".to_string(),
                            exp: share.exp,
                            iat: share.iat,
                            nbf: Some(share.iat),
                            jti: share.jti,
                            scope: Some("collab:read".to_string()),
                            document_permissions: vec![auth::DocumentPermission {
                                document_id,
                                role,
                                granted_by: Some(share.created_by),
                                granted_at: share.iat,
                                expires_at: Some(share.exp),
                            }],
                        }
                    }
                    Err(_) => return Err(AppError::Auth(e)),
                }
            }
        }
    } else {
        return Err(AppError::Auth(auth::AuthError::InvalidToken("Missing token".into())));
    };

    let _has_write = AuthService::check_document_permission(&claims, &document_id, &Role::Viewer)
        .map_err(AppError::Auth)
        .map(|_| {
            AuthService::check_document_permission(&claims, &document_id, &Role::Editor).is_ok()
        })?;

    if !state.app.can_accept_connection() {
        tracing::warn!("Connection rejected: server at capacity");
        return Err(AppError::ServiceUnavailable("Server at capacity, retry later".into()));
    }

    let node_id = state.node_id.clone();
    let app = state.app.clone();

    Ok(ws.on_upgrade(move |socket| handle_websocket(socket, document_id, claims, app, node_id)))
}

async fn handle_websocket(
    socket: WebSocket,
    document_id: Uuid,
    claims: JwtClaims,
    app: Arc<AppState>,
    node_id: String,
) {
    let session_id = Uuid::new_v4();
    let client_id: u64 = rand::random();
    let user_id = claims.user_id.clone();
    let can_write = AuthService::check_document_permission(&claims, &document_id, &Role::Editor).is_ok();

    let (tx, mut rx) = mpsc::unbounded_channel::<WsMessage>();
    let info = app.ws_manager.register_connection(
        session_id,
        client_id,
        user_id.clone(),
        document_id,
        tx,
    );

    let room = app.ws_manager.get_or_create_room(document_id, client_id);
    room.connections.insert(session_id);

    app.increment_connections();
    counter!("collab_ws_connections_total").increment(1);
    gauge!("collab_active_documents").set(app.ws_manager.total_rooms() as f64);

    let color = format!("#{:06x}", rand::random::<u32>() & 0xffffff);
    app.presence_tracker.user_joined(
        document_id,
        user_id.clone(),
        claims.name.clone(),
        color.clone(),
        None,
        session_id,
    );

    let users = app.ws_manager.room_users(&document_id);
    let _ = info.sender.send(WsMessage::Welcome {
        session_id,
        client_id,
        server_time: chrono::Utc::now().timestamp_millis(),
        vector_clock: room.document.read().vector_clock().iter().map(|(k, v)| (*k, *v)).collect(),
        content: Some(room.document.write().get_content().to_string()),
        missing_ops: Vec::new(),
    });

    let _ = info.sender.send(WsMessage::RoomJoined {
        document_id,
        users,
    });

    let user = RoomUser {
        user_id: user_id.clone(),
        client_id,
        joined_at: info.connected_at.timestamp_millis(),
        session_id,
    };

    app.ws_manager.broadcast_to_room(
        &document_id,
        WsMessage::UserJoined {
            user: user.clone(),
        },
        Some(&session_id),
    );

    let presence_update = PresenceUpdate::Joined {
        user_id: user_id.clone(),
        display_name: claims.name.clone(),
        color: color.clone(),
        avatar: None,
        session_id,
    };

    app.ws_manager.broadcast_to_room(
        &document_id,
        WsMessage::Presence {
            user_id: user_id.clone(),
            update: presence_update.clone(),
        },
        None,
    );

    {
        let app_clone = app.clone();
        let node_id_str = node_id.clone();
        let uid = user_id.clone();
        let user_clone = user.clone();
        let update_clone = presence_update.clone();
        tokio::spawn(async move {
            let _ = app_clone.broadcaster.publish(
                document_id,
                &node_id_str,
                &BroadcastEvent::UserJoined { user: user_clone },
            ).await;
            let _ = app_clone.broadcaster.publish(
                document_id,
                &node_id_str,
                &BroadcastEvent::Presence {
                    user_id: uid,
                    update: update_clone,
                },
            ).await;
        });
    }

    let (mut ws_sender, mut ws_receiver) = socket.split();

    let mut heartbeat_interval = tokio::time::interval(app.config.heartbeat_interval());
    let send_loop_running = Arc::new(AtomicBool::new(true));
    let send_running = send_loop_running.clone();

    let app_send = app.clone();
    let user_id_send = user_id.clone();
    let doc_id_send = document_id;
    let session_id_send = session_id;
    let send_task = tokio::spawn(async move {
        while send_running.load(std::sync::atomic::Ordering::SeqCst) {
            tokio::select! {
                _ = heartbeat_interval.tick() => {
                    let msg = WsMessage::Ping {
                        timestamp: chrono::Utc::now().timestamp_millis(),
                    };
                    let ws_msg = match serde_json::to_string(&msg) {
                        Ok(j) => Message::Text(j),
                        Err(_) => continue,
                    };
                    if ws_sender.send(ws_msg).await.is_err() {
                        break;
                    }
                }
                Some(msg) = rx.recv() => {
                    let ws_msg = match serde_json::to_string(&msg) {
                        Ok(j) => Message::Text(j),
                        Err(_) => continue,
                    };
                    if ws_sender.send(ws_msg).await.is_err() {
                        break;
                    }
                }
            }
        }
        let _ = ws_sender.close().await;
        let _ = app_send.ws_manager.remove_connection(&session_id_send);
        app_send.presence_tracker.user_left(doc_id_send, &user_id_send, &session_id_send);
        app_send.decrement_connections();
    });

    let app_recv = app.clone();
    let user_id_recv = user_id.clone();
    let session_id_recv = session_id;
    let doc_id_recv = document_id;
    let node_id_recv = node_id.clone();
    let running = send_loop_running.clone();

    let recv_task = tokio::spawn(async move {
        loop {
            tokio::select! {
                frame = ws_receiver.next() => {
                    let frame = match frame {
                        Some(Ok(f)) => f,
                        _ => break,
                    };

                    match frame {
                        Message::Close(_) => break,
                        Message::Ping(_) | Message::Pong(_) => {
                            app_recv.ws_manager.update_pong(&session_id_recv);
                            continue;
                        }
                        Message::Binary(_) => continue,
                        Message::Text(text) => {
                            let parsed: Result<WsMessage, _> = serde_json::from_str(&text);
                            let msg = match parsed {
                                Ok(m) => m,
                                Err(e) => {
                                    let _ = app_recv.ws_manager.send_to_session(
                                        &session_id_recv,
                                        WsMessage::Error {
                                            code: "PARSE_ERROR".into(),
                                            message: e.to_string(),
                                        },
                                    );
                                    continue;
                                }
                            };

                            handle_incoming_ws(
                                &app_recv,
                                doc_id_recv,
                                &user_id_recv,
                                &session_id_recv,
                                client_id,
                                can_write,
                                msg,
                                &node_id_recv,
                            ).await;
                        }
                    }
                }
            }
        }
        running.store(false, std::sync::atomic::Ordering::SeqCst);
    });

    let _ = tokio::join!(send_task, recv_task);

    app.presence_tracker.user_left(document_id, &user_id, &session_id);
    app.ws_manager.broadcast_to_room(
        &document_id,
        WsMessage::UserLeft {
            user_id: user_id.clone(),
            reason: "disconnected".into(),
        },
        Some(&session_id),
    );

    {
        let app_clone = app.clone();
        let node_id_str = node_id.clone();
        let uid = user_id.clone();
        tokio::spawn(async move {
            let _ = app_clone.broadcaster.publish(
                document_id,
                &node_id_str,
                &BroadcastEvent::UserLeft {
                    user_id: uid,
                    reason: "disconnected".into(),
                },
            ).await;
        });
    }

    if let Some(room) = app.ws_manager.get_room(&document_id) {
        if room.connections.is_empty() {
            if app.snapshot_service.should_snapshot(&room) {
                app.snapshot_service.schedule_snapshot(document_id);
            }
        }
    }

    counter!("collab_ws_disconnections_total").increment(1);
}

async fn handle_incoming_ws(
    app: &Arc<AppState>,
    document_id: Uuid,
    user_id: &str,
    session_id: &Uuid,
    client_id: u64,
    can_write: bool,
    msg: WsMessage,
    node_id: &str,
) {
    match msg {
        WsMessage::Ping { timestamp } => {
            let _ = app.ws_manager.send_to_session(
                session_id,
                WsMessage::Pong {
                    timestamp,
                    server_time: chrono::Utc::now().timestamp_millis(),
                },
            );
            app.ws_manager.update_pong(session_id);
        }
        WsMessage::Pong { .. } => {
            app.ws_manager.update_pong(session_id);
        }
        WsMessage::Op { sequence, op } if can_write => {
            let start = std::time::Instant::now();
            if let Err((_, retry)) = app.rate_limiter.check_both(*session_id, document_id, 1) {
                let _ = app.ws_manager.send_to_session(
                    session_id,
                    WsMessage::Error {
                        code: "RATE_LIMITED".into(),
                        message: format!("Retry after {:.0}s", retry.ceil()),
                    },
                );
                return;
            }

            let room = app.ws_manager.get_or_create_room(document_id, client_id);
            let seq = {
                let mut doc = room.document.write();
                doc.set_client_id(op.client_id);
                if let Err(e) = doc.apply_op(&op) {
                    tracing::warn!("Apply op failed: {:?}", e);
                    let _ = app.ws_manager.send_to_session(
                        session_id,
                        WsMessage::Ack { sequence, applied: false },
                    );
                    return;
                }
                let seq = room.add_op(op.clone());
                seq
            };

            app.track_op(match &op.op_type {
                crate::crdt::OpType::Insert(_) => "insert",
                crate::crdt::OpType::Delete(_) => "delete",
                crate::crdt::OpType::Format(_) => "format",
            });

            let _ = app.ws_manager.send_to_session(
                session_id,
                WsMessage::Ack { sequence, applied: true },
            );

            app.ws_manager.broadcast_to_room(
                &document_id,
                WsMessage::Op { sequence: seq, op: op.clone() },
                Some(session_id),
            );

            let app_clone = app.clone();
            let node_id_str = node_id.to_string();
            let user = user_id.to_string();
            let sess = *session_id;
            let op_clone = op.clone();
            let op_for_stream = op.clone();
            tokio::spawn(async move {
                let event = BroadcastEvent::Op {
                    sequence: seq,
                    op: op_for_stream,
                };
                let _ = app_clone.broadcaster.publish(document_id, &node_id_str, &event).await;

                let repo = OplogRepository::new(app_clone.db_pool.clone());
                let _ = repo.append_op(&op_clone, seq, Some(sess), &user).await;
                let prev = if let Ok(Some(m)) = repo.get_document(document_id).await {
                    m.current_version
                } else { 0 };
                if seq as i64 > prev {
                    let content_preview = {
                        let r = app_clone.ws_manager.get_or_create_room(document_id, client_id);
                        let mut d = r.document.write();
                        let c = d.get_content().to_string();
                        if c.len() > 200 { c[..200].to_string() } else { c }
                    };
                    let _ = repo.update_document_version(
                        document_id,
                        seq as i64,
                        &user,
                        Some(&content_preview),
                    ).await;
                }
            });

            histogram!("collab_op_latency_seconds").record(start.elapsed().as_secs_f64());
        }
        WsMessage::BatchOps { sequence, ops } if can_write => {
            if let Err((_, retry)) = app.rate_limiter.check_both(*session_id, document_id, ops.len() as u64) {
                let _ = app.ws_manager.send_to_session(
                    session_id,
                    WsMessage::Error {
                        code: "RATE_LIMITED".into(),
                        message: format!("Retry after {:.0}s", retry.ceil()),
                    },
                );
                return;
            }

            let room = app.ws_manager.get_or_create_room(document_id, client_id);
            let mut applied: Vec<(u64, Op)> = Vec::new();
            {
                let mut doc = room.document.write();
                for op in ops {
                    doc.set_client_id(op.client_id);
                    if doc.apply_op(&op).is_ok() {
                        let seq = room.add_op(op.clone());
                        applied.push((seq, op));
                    }
                }
            }

            for (seq, op) in &applied {
                app.ws_manager.broadcast_to_room(
                    &document_id,
                    WsMessage::Op { sequence: *seq, op: op.clone() },
                    Some(session_id),
                );
            }

            let _ = app.ws_manager.send_to_session(
                session_id,
                WsMessage::Ack { sequence, applied: true },
            );

            if !applied.is_empty() {
                let app_clone = app.clone();
                let node_id_str = node_id.to_string();
                let user = user_id.to_string();
                let sess = *session_id;
                let applied_clone = applied.clone();
                tokio::spawn(async move {
                    let repo = OplogRepository::new(app_clone.db_pool.clone());
                    for (seq, op) in &applied_clone {
                        let event = BroadcastEvent::Op {
                            sequence: *seq,
                            op: op.clone(),
                        };
                        let _ = app_clone.broadcaster.publish(document_id, &node_id_str, &event).await;
                        let _ = repo.append_op(op, *seq, Some(sess), &user).await;
                    }
                    let prev = if let Ok(Some(m)) = repo.get_document(document_id).await {
                        m.current_version
                    } else { 0 };
                    let last_seq = applied_clone.last().map(|(s, _)| *s).unwrap_or(0);
                    if last_seq as i64 > prev {
                        let content_preview = {
                            let r = app_clone.ws_manager.get_or_create_room(document_id, client_id);
                            let mut d = r.document.write();
                            let c = d.get_content().to_string();
                            if c.len() > 200 { c[..200].to_string() } else { c }
                        };
                        let _ = repo.update_document_version(
                            document_id,
                            last_seq as i64,
                            &user,
                            Some(&content_preview),
                        ).await;
                    }
                });
            }
        }
        WsMessage::Cursor { position, .. } => {
            app.presence_tracker.update_cursor(document_id, user_id, position.clone());
            app.ws_manager.broadcast_to_room(
                &document_id,
                WsMessage::Cursor {
                    user_id: user_id.to_string(),
                    position: position.clone(),
                },
                Some(session_id),
            );

            let app_clone = app.clone();
            let node_id_str = node_id.to_string();
            let uid = user_id.to_string();
            tokio::spawn(async move {
                let event = BroadcastEvent::Cursor {
                    user_id: uid,
                    position,
                };
                let _ = app_clone.broadcaster.publish(document_id, &node_id_str, &event).await;
            });
        }
        WsMessage::Selection { range, .. } => {
            app.presence_tracker.update_selection(document_id, user_id, range.clone());
            app.ws_manager.broadcast_to_room(
                &document_id,
                WsMessage::Selection {
                    user_id: user_id.to_string(),
                    range: range.clone(),
                },
                Some(session_id),
            );

            let app_clone = app.clone();
            let node_id_str = node_id.to_string();
            let uid = user_id.to_string();
            tokio::spawn(async move {
                let event = BroadcastEvent::Selection {
                    user_id: uid,
                    range,
                };
                let _ = app_clone.broadcaster.publish(document_id, &node_id_str, &event).await;
            });
        }
        WsMessage::Presence { update, .. } => {
            app.ws_manager.broadcast_to_room(
                &document_id,
                WsMessage::Presence {
                    user_id: user_id.to_string(),
                    update: update.clone(),
                },
                Some(session_id),
            );

            let app_clone = app.clone();
            let node_id_str = node_id.to_string();
            let uid = user_id.to_string();
            tokio::spawn(async move {
                let event = BroadcastEvent::Presence {
                    user_id: uid,
                    update,
                };
                let _ = app_clone.broadcaster.publish(document_id, &node_id_str, &event).await;
            });
        }
        WsMessage::SnapshotRequest { from_version } => {
            let room = app.ws_manager.get_or_create_room(document_id, client_id);
            let ops = if let Some(from) = from_version {
                room.get_ops_since(from).into_iter().map(|(_, op)| op).collect()
            } else {
                Vec::new()
            };
            let _ = app.ws_manager.send_to_session(
                session_id,
                WsMessage::SnapshotResponse {
                    version: *room.current_version.lock(),
                    ops,
                    full_snapshot: from_version.is_none(),
                },
            );
        }
        WsMessage::SyncRequest { vector_clock } => {
            let room = app.ws_manager.get_or_create_room(document_id, client_id);
            let server_version = *room.current_version.lock();
            let server_vc: HashMap<u64, u32> = {
                let doc = room.document.read();
                doc.vector_clock().clone().into_iter().collect()
            };
            let client_missing_ops = room.get_ops_for_clients(&vector_clock);
            let server_missing_clients = room.missing_clients(&vector_clock);

            let _ = app.ws_manager.send_to_session(
                session_id,
                WsMessage::SyncResponse {
                    server_version,
                    server_vector_clock: server_vc,
                    client_missing_ops,
                    server_missing_clients,
                },
            );
        }
        WsMessage::BatchSubmit { ops } if can_write => {
            let start = std::time::Instant::now();
            let total = ops.len() as u64;
            if let Err((_, retry)) = app.rate_limiter.check_both(*session_id, document_id, total) {
                let _ = app.ws_manager.send_to_session(
                    session_id,
                    WsMessage::Error {
                        code: "RATE_LIMITED".into(),
                        message: format!("Retry after {:.0}s", retry.ceil()),
                    },
                );
                return;
            }

            let room = app.ws_manager.get_or_create_room(document_id, client_id);
            let mut applied = 0u64;
            let mut duplicates = 0u64;

            {
                let mut doc = room.document.write();
                for op in &ops {
                    let key = op.dedup_key();
                    if room.has_op(key.0, key.1) {
                        duplicates += 1;
                        continue;
                    }
                    if doc.apply_op(op).is_ok() {
                        room.add_op(op.clone());
                        applied += 1;
                    }
                }
            }

            if applied > 0 {
                histogram!("collab_batch_size").record(applied as f64);
            }
            if duplicates > 0 {
                counter!("collab_dedup_skipped_total").increment(duplicates);
            }

            let server_version = *room.current_version.lock();
            let _ = app.ws_manager.send_to_session(
                session_id,
                WsMessage::BatchAck {
                    applied,
                    duplicates,
                    server_version,
                },
            );

            for op in ops.iter() {
                let key = op.dedup_key();
                if room.has_op(key.0, key.1) {
                    continue;
                }
                app.ws_manager.broadcast_to_room(
                    &document_id,
                    WsMessage::Op { sequence: 0, op: op.clone() },
                    Some(session_id),
                );
            }

            let app_clone = app.clone();
            let node_id_str = node_id.to_string();
            let user = user_id.to_string();
            let sess = *session_id;
            tokio::spawn(async move {
                let repo = OplogRepository::new(app_clone.db_pool.clone());
                for op in ops.iter() {
                    let key = op.dedup_key();
                    if app_clone.ws_manager.get_room(&document_id)
                        .map(|r| r.has_op(key.0, key.1))
                        .unwrap_or(false)
                    {
                        continue;
                    }
                    let event = BroadcastEvent::Op {
                        sequence: 0,
                        op: op.clone(),
                    };
                    let _ = app_clone.broadcaster.publish(document_id, &node_id_str, &event).await;
                    let _ = repo.append_op(op, 0, Some(sess), &user).await;
                }
            });

            histogram!("collab_batch_latency_seconds").record(start.elapsed().as_secs_f64());
        }
        _ => {}
    }
}

fn start_background_tasks(
    config: AppConfig,
    ws_manager: ConnectionManager,
    presence: presence::PresenceTracker,
    rate_limiter: ratelimit::RateLimiter,
    snapshot_service: snapshot::SnapshotService,
    app: Arc<AppState>,
    subscribe_tx: mpsc::UnboundedSender<Uuid>,
) {
    {
        let ws = ws_manager.clone();
        let tx = subscribe_tx.clone();
        let interval = Duration::from_secs(config.websocket.heartbeat_interval_secs);
        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(interval * 2);
            loop {
                ticker.tick().await;
                let stale = ws.check_stale_connections();
                for sid in stale {
                    ws.remove_connection(&sid);
                }
                ws.cleanup_expired_sessions();
                presence.cleanup_stale();
                gauge!("collab_active_connections").set(ws.total_connections() as f64);
                gauge!("collab_active_documents").set(ws.total_rooms() as f64);

                for entry in ws.rooms.iter() {
                    let _ = tx.send(*entry.key());
                }
            }
        });
    }

    {
        let rl = rate_limiter.clone();
        let interval = Duration::from_secs(config.ratelimit.cleanup_interval_secs);
        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(interval);
            loop {
                ticker.tick().await;
                rl.cleanup_stale(Duration::from_secs(3600));
            }
        });
    }

    {
        let snap = snapshot_service.clone();
        let ws = ws_manager.clone();
        let interval = Duration::from_secs(config.snapshot.interval_secs);
        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(interval);
            loop {
                ticker.tick().await;
                snap.process_pending_snapshots(&ws.rooms);
                for entry in ws.rooms.iter() {
                    let room = entry.value();
                    if snap.should_snapshot(room) {
                        snap.schedule_snapshot(*entry.key());
                    }
                }
            }
        });
    }
}

async fn redis_pubsub_loop(state: AppRouterState, config: AppConfig) {
    let prefix = config.redis.pubsub_channel_prefix.clone();
    let channel = format!("{}op-broadcast", prefix);

    loop {
        let conn = match state.app.redis_pool.get().await {
            Ok(c) => c,
            Err(e) => {
                tracing::warn!("Redis pubsub connection failed: {:?}", e);
                tokio::time::sleep(Duration::from_secs(3)).await;
                continue;
            }
        };

        let result: redis::RedisResult<()> = async {
            use futures::StreamExt;
            tracing::info!("Attempting subscribe to Redis pubsub channel: {}", channel);

            let client = redis::Client::open(config.redis.url.as_str())
                .map_err(|e| redis::RedisError::from((redis::ErrorKind::ClientError, "redis open", e.to_string())))?;

            let mut pubsub_conn = client.get_async_pubsub().await
                .map_err(|e| redis::RedisError::from((redis::ErrorKind::ClientError, "pubsub open", e.to_string())))?;

            pubsub_conn.subscribe(&channel).await?;
            tracing::info!("Subscribed to Redis pubsub channel: {}", channel);

            loop {
                let msg = match pubsub_conn.on_message().next().await {
                    Some(m) => m,
                    None => break,
                };

                if let Ok(payload) = msg.get_payload::<Vec<u8>>() {
                    if let Ok(broadcast) = serde_json::from_slice::<PubSubBroadcast>(&payload) {
                        if broadcast.node_id == state.node_id {
                            continue;
                        }
                        match broadcast.kind {
                            PubSubKind::Op { sequence, op } => {
                                state.app.ws_manager.broadcast_to_room(
                                    &broadcast.document_id,
                                    WsMessage::Op { sequence, op },
                                    None,
                                );
                            }
                            PubSubKind::Presence { user_id, update } => {
                                state.app.ws_manager.broadcast_to_room(
                                    &broadcast.document_id,
                                    WsMessage::Presence { user_id, update },
                                    None,
                                );
                            }
                            PubSubKind::UserJoined { user } => {
                                state.app.ws_manager.broadcast_to_room(
                                    &broadcast.document_id,
                                    WsMessage::UserJoined { user },
                                    None,
                                );
                            }
                            PubSubKind::UserLeft { user_id, reason } => {
                                state.app.ws_manager.broadcast_to_room(
                                    &broadcast.document_id,
                                    WsMessage::UserLeft { user_id, reason },
                                    None,
                                );
                            }
                        }
                    }
                }
            }
            drop(conn);
            Ok(())
        }.await;

        if let Err(e) = result {
            tracing::warn!("Redis pubsub error: {:?}, reconnecting", e);
        }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct PubSubBroadcast {
    node_id: String,
    document_id: Uuid,
    kind: PubSubKind,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
enum PubSubKind {
    Op { sequence: u64, op: Op },
    Presence { user_id: String, update: PresenceUpdate },
    UserJoined { user: RoomUser },
    UserLeft { user_id: String, reason: String },
}
