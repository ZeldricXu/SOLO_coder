use actix_session::Session;
use actix_web::{web, HttpResponse, Responder};
use uuid::Uuid;

use crate::handlers::auth_handler::SESSION_ID_KEY;
use crate::models::AuthUser;
use crate::services::{AuthService, StatsService};
use crate::utils::{AppError, AppResult, ApiResponse};

async fn get_current_user(
    session: &Session,
    auth_service: &AuthService,
) -> AppResult<AuthUser> {
    let session_id = session
        .get::<String>(SESSION_ID_KEY)?
        .ok_or_else(|| AppError::Authentication("请先登录".to_string()))?;

    auth_service.get_current_user(&session_id).await
}

fn render_base_template(title: &str, user: &AuthUser, content: maud::Markup) -> maud::Markup {
    maud::html! {
        (maud::DOCTYPE)
        html {
            head {
                meta charset="utf-8";
                meta name="viewport" content="width=device-width, initial-scale=1.0";
                title { (title) " - 代码审查平台" }
                style {
                    r#"
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        background: #f5f7fa;
                        color: #333;
                        min-height: 100vh;
                        display: flex;
                    }
                    .sidebar {
                        width: 250px;
                        background: #1a1a2e;
                        color: white;
                        padding: 24px 0;
                        position: fixed;
                        height: 100vh;
                        overflow-y: auto;
                    }
                    .sidebar-logo {
                        padding: 0 24px 32px;
                        font-size: 20px;
                        font-weight: 600;
                        border-bottom: 1px solid #2d2d4a;
                        margin-bottom: 24px;
                    }
                    .nav-item {
                        display: flex;
                        align-items: center;
                        gap: 12px;
                        padding: 12px 24px;
                        color: #a0a0b8;
                        text-decoration: none;
                        transition: all 0.2s;
                        cursor: pointer;
                        border: none;
                        background: none;
                        width: 100%;
                        text-align: left;
                        font-size: 14px;
                    }
                    .nav-item:hover, .nav-item.active {
                        background: #2d2d4a;
                        color: white;
                    }
                    .nav-item svg { width: 20px; height: 20px; }
                    .main-content {
                        flex: 1;
                        margin-left: 250px;
                        padding: 32px;
                    }
                    .header {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        margin-bottom: 32px;
                    }
                    .page-title {
                        font-size: 28px;
                        font-weight: 600;
                        color: #1a1a2e;
                    }
                    .user-menu {
                        display: flex;
                        align-items: center;
                        gap: 16px;
                    }
                    .user-avatar {
                        width: 40px;
                        height: 40px;
                        border-radius: 50%;
                        background: #667eea;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        color: white;
                        font-weight: 600;
                    }
                    .stats-grid {
                        display: grid;
                        grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
                        gap: 24px;
                        margin-bottom: 32px;
                    }
                    .stat-card {
                        background: white;
                        border-radius: 12px;
                        padding: 24px;
                        box-shadow: 0 2px 8px rgba(0,0,0,0.05);
                    }
                    .stat-label {
                        font-size: 14px;
                        color: #666;
                        margin-bottom: 8px;
                    }
                    .stat-value {
                        font-size: 32px;
                        font-weight: 600;
                        color: #1a1a2e;
                    }
                    .stat-change {
                        font-size: 13px;
                        margin-top: 8px;
                    }
                    .stat-change.positive { color: #22c55e; }
                    .stat-change.negative { color: #ef4444; }
                    .section-title {
                        font-size: 18px;
                        font-weight: 600;
                        margin-bottom: 16px;
                        color: #1a1a2e;
                    }
                    .activity-list {
                        background: white;
                        border-radius: 12px;
                        box-shadow: 0 2px 8px rgba(0,0,0,0.05);
                        overflow: hidden;
                    }
                    .activity-item {
                        display: flex;
                        gap: 16px;
                        padding: 20px;
                        border-bottom: 1px solid #f0f0f0;
                        transition: background 0.2s;
                    }
                    .activity-item:hover { background: #fafafa; }
                    .activity-item:last-child { border-bottom: none; }
                    .activity-icon {
                        width: 40px;
                        height: 40px;
                        border-radius: 50%;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        flex-shrink: 0;
                        font-size: 18px;
                    }
                    .activity-icon.mr { background: #e0e7ff; color: #4f46e5; }
                    .activity-icon.comment { background: #fef3c7; color: #d97706; }
                    .activity-icon.approval { background: #d1fae5; color: #059669; }
                    .activity-icon.issue { background: #fee2e2; color: #dc2626; }
                    .activity-content { flex: 1; }
                    .activity-title {
                        font-weight: 500;
                        color: #1a1a2e;
                        margin-bottom: 4px;
                    }
                    .activity-desc {
                        font-size: 13px;
                        color: #666;
                        margin-bottom: 4px;
                    }
                    .activity-time {
                        font-size: 12px;
                        color: #999;
                    }
                    .loading {
                        text-align: center;
                        padding: 40px;
                        color: #999;
                    }
                    .logout-form { display: inline; }
                    .btn-logout {
                        background: none;
                        border: none;
                        color: inherit;
                        cursor: pointer;
                        font: inherit;
                    }
                    "#
                }
            }
            body {
                div class="sidebar" {
                    div class="sidebar-logo" { "🔍 代码审查平台" }
                    a href="/dashboard" class="nav-item active" {
                        svg viewBox="0 0 24 24" fill="currentColor" {
                            path d="M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z";
                        }
                        span { "仪表盘" }
                    }
                    a href="/repos" class="nav-item" {
                        svg viewBox="0 0 24 24" fill="currentColor" {
                            path d="M12 0c-6.626 0-12 5.373-12 12 0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23.957-.266 1.983-.399 3.003-.404 1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576 4.765-1.589 8.199-6.086 8.199-11.386 0-6.627-5.373-12-12-12z";
                        }
                        span { "仓库管理" }
                    }
                    a href="/merge-requests" class="nav-item" {
                        svg viewBox="0 0 24 24" fill="currentColor" {
                            path d="M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM14 13v4h-4v-4H7l5-5 5 5h-3z";
                        }
                        span { "合并请求" }
                    }
                    form method="POST" action="/logout" class="logout-form" {
                        button type="submit" class="nav-item btn-logout" {
                            svg viewBox="0 0 24 24" fill="currentColor" {
                                path d="M10.09 15.59L11.5 17l5-5-5-5-1.41 1.41L12.67 11H3v2h9.67l-2.58 2.59zM19 3H5c-1.11 0-2 .9-2 2v4h2V5h14v14H5v-4H3v4c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z";
                            }
                            span { "退出登录" }
                        }
                    }
                }
                div class="main-content" {
                    div class="header" {
                        h1 class="page-title" { (title) }
                        div class="user-menu" {
                            span { (user.username) }
                            div class="user-avatar" {
                                @if let Some(avatar) = &user.avatar_url {
                                    img src=(avatar) alt=(user.username) style="width: 100%; height: 100%; border-radius: 50%; object-fit: cover;";
                                } @else {
                                    (user.username.chars().next().unwrap_or('U').to_ascii_uppercase())
                                }
                            }
                        }
                    }
                    (content)
                }
            }
        }
    }
}

pub async fn dashboard_page(
    session: Session,
    auth_service: web::Data<AuthService>,
) -> AppResult<impl Responder> {
    let user = get_current_user(&session, &auth_service).await?;

    let content = maud::html! {
        div class="stats-grid" id="stats-grid" {
            div class="stat-card" {
                div class="stat-label" { "待处理评审" }
                div class="stat-value" { "..." }
                div class="stat-change" { "加载中..." }
            }
            div class="stat-card" {
                div class="stat-label" { "我负责的评审" }
                div class="stat-value" { "..." }
                div class="stat-change" { "加载中..." }
            }
            div class="stat-card" {
                div class="stat-label" { "团队评审覆盖率" }
                div class="stat-value" { "..." }
                div class="stat-change" { "加载中..." }
            }
            div class="stat-card" {
                div class="stat-label" { "平均响应时间" }
                div class="stat-value" { "..." }
                div class="stat-change" { "加载中..." }
            }
        }

        h2 class="section-title" { "最近活动" }
        div class="activity-list" id="activity-list" {
            div class="loading" { "加载活动数据..." }
        }

        script {
            r#"
            async function loadDashboardData() {
                try {
                    const [statsResponse, activityResponse] = await Promise.all([
                        fetch('/api/dashboard/stats'),
                        fetch('/api/dashboard/activity')
                    ]);
                    
                    const statsData = await statsResponse.json();
                    const activityData = await activityResponse.json();
                    
                    if (statsData.code === 200 && statsData.data) {
                        updateStats(statsData.data);
                    }
                    
                    if (activityData.code === 200 && activityData.data) {
                        updateActivity(activityData.data);
                    }
                } catch (error) {
                    console.error('Failed to load dashboard data:', error);
                }
            }
            
            function updateStats(stats) {
                const statsGrid = document.getElementById('stats-grid');
                statsGrid.innerHTML = `
                    <div class="stat-card">
                        <div class="stat-label">待处理评审</div>
                        <div class="stat-value">${stats.total_pending_reviews}</div>
                        <div class="stat-change positive">+${stats.my_pending_reviews} 个我负责的</div>
                    </div>
                    <div class="stat-card">
                        <div class="stat-label">待解决问题</div>
                        <div class="stat-value">${stats.my_open_issues}</div>
                        <div class="stat-change negative">${stats.issues_assigned_to_me} 个指派给我</div>
                    </div>
                    <div class="stat-card">
                        <div class="stat-label">团队评审覆盖率</div>
                        <div class="stat-value">${stats.team_review_coverage.toFixed(1)}%</div>
                        <div class="stat-change positive">较上周 +2.3%</div>
                    </div>
                    <div class="stat-card">
                        <div class="stat-label">平均响应时间</div>
                        <div class="stat-value">${stats.avg_response_time_hours.toFixed(1)}h</div>
                        <div class="stat-change positive">较上周 -0.5h</div>
                    </div>
                `;
            }
            
            function updateActivity(activities) {
                const activityList = document.getElementById('activity-list');
                
                if (activities.length === 0) {
                    activityList.innerHTML = '<div class="loading">暂无活动记录</div>';
                    return;
                }
                
                const typeIcons = {
                    'merge_request': '📥',
                    'comment': '💬',
                    'approval': '✅',
                    'issue': '🐛',
                    'review': '🔍'
                };
                
                const typeClasses = {
                    'merge_request': 'mr',
                    'comment': 'comment',
                    'approval': 'approval',
                    'issue': 'issue',
                    'review': 'mr'
                };
                
                activityList.innerHTML = activities.map(activity => {
                    const icon = typeIcons[activity.type_] || '📌';
                    const iconClass = typeClasses[activity.type_] || 'mr';
                    const time = new Date(activity.created_at).toLocaleString('zh-CN');
                    
                    return `
                        <div class="activity-item">
                            <div class="activity-icon ${iconClass}">${icon}</div>
                            <div class="activity-content">
                                <div class="activity-title">${activity.title}</div>
                                <div class="activity-desc">${activity.description}</div>
                                <div class="activity-time">${activity.username} · ${time}</div>
                            </div>
                        </div>
                    `;
                }).join('');
            }
            
            loadDashboardData();
            setInterval(loadDashboardData, 30000);
            "#
        }
    };

    let html = render_base_template("仪表盘", &user, content);
    Ok(HttpResponse::Ok().content_type("text/html; charset=utf-8").body(html.into_string()))
}

pub async fn dashboard_stats_api(
    session: Session,
    auth_service: web::Data<AuthService>,
    stats_service: web::Data<StatsService>,
) -> AppResult<impl Responder> {
    let user = get_current_user(&session, &auth_service).await?;
    
    let organization_id = user.organization_id.unwrap_or_else(Uuid::nil);
    let stats = stats_service.get_dashboard_stats(user.id, organization_id).await?;

    Ok(HttpResponse::Ok().json(ApiResponse::success(stats)))
}

pub async fn recent_activity_api(
    session: Session,
    auth_service: web::Data<AuthService>,
    stats_service: web::Data<StatsService>,
) -> AppResult<impl Responder> {
    let user = get_current_user(&session, &auth_service).await?;
    
    let organization_id = user.organization_id.unwrap_or_else(Uuid::nil);
    let activity = stats_service.get_recent_activity(organization_id, 20).await?;

    Ok(HttpResponse::Ok().json(ApiResponse::success(activity)))
}

pub async fn health_check() -> impl Responder {
    HttpResponse::Ok().json(serde_json::json!({
        "status": "ok",
        "timestamp": chrono::Utc::now().to_rfc3339(),
    }))
}
