pub mod admin_handler;
pub mod ai_review_handler;
pub mod ai_rule_handler;
pub mod attachment_handler;
pub mod auth_handler;
pub mod checklist_handler;
pub mod comment_handler;
pub mod dashboard_handler;
pub mod issue_handler;
pub mod merge_request_handler;
pub mod notification_handler;
pub mod repo_handler;
pub mod stats_handler;
pub mod webhook_handler;

pub use admin_handler::{
    organization_page, teams_page, team_members_page,
    create_team_api, add_member_api, update_role_api, remove_member_api,
};

pub use ai_review_handler::{
    ai_review_api, trigger_scan_api, act_on_suggestion_api,
};

pub use ai_rule_handler::{
    ai_rules_page, ai_rules_api, ai_rule_api,
    create_ai_rule_api, update_ai_rule_api, delete_ai_rule_api,
    set_default_rule_api, effective_rules_api,
};

pub use attachment_handler::{
    upload_attachment_api, list_attachments_api, delete_attachment_api,
    UploadAttachmentRequest,
};

pub use auth_handler::{
    login_page, oauth_login, oauth_callback, logout, get_current_user,
    get_auth_user_from_session, auth_middleware, OAuthCallbackQuery, SESSION_ID_KEY, STATE_KEY,
};

pub use checklist_handler::{
    checklists_page, checklist_detail_page,
    checklists_api, checklist_api, create_checklist_api, update_checklist_api, delete_checklist_api,
    check_item_api,
};

pub use comment_handler::{
    update_comment_api, delete_comment_api, resolve_comment_api,
};

pub use dashboard_handler::{
    dashboard_page, dashboard_stats_api, recent_activity_api,
};

pub use issue_handler::{
    issues_page, issue_detail_page,
    issues_api, issue_api, create_issue_api, update_issue_api, update_issue_status_api,
    assign_issue_api,
};

pub use merge_request_handler::{
    mrs_page, mr_detail_page,
    mrs_api, mr_api, mr_diff_api, create_comment_api, update_mr_status_api, assign_reviewer_api,
    UpdateMrStatusRequest, AssignReviewerRequest,
};

pub use notification_handler::{
    notifications_page, notifications_settings_page,
    notifications_api, mark_read_api, mark_all_read_api, settings_api, update_settings_api,
};

pub use repo_handler::{
    repos_page, repo_detail_page, repo_settings_page,
    repos_api, repo_api, create_repo_api, update_repo_api, delete_repo_api, sync_repo_api,
    UpdateRepositoryRequest, AssignReviewerRequest as RepoAssignReviewerRequest,
};

pub use stats_handler::{
    stats_page, org_stats_page,
    coverage_stats_api, heatmap_api, personal_stats_api, team_ranking_api, export_report_api,
    org_overview_api, org_repo_health_api, org_contributor_ranking_api, org_issue_trend_api, org_refresh_mv_api,
};

pub use webhook_handler::{
    handle_webhook, handle_webhook_public, configure_webhook_routes,
    extract_webhook_headers, normalize_event_type,
};

use actix_web::web;
use crate::services::NotificationService;

pub fn configure_auth_routes(cfg: &mut web::ServiceConfig) {
    cfg.service(
        web::resource("/login")
            .route(web::get().to(login_page)),
    )
    .service(
        web::resource("/auth/{provider}")
            .route(web::get().to(oauth_login)),
    )
    .service(
        web::resource("/auth/{provider}/callback")
            .route(web::get().to(oauth_callback)),
    )
    .service(
        web::resource("/logout")
            .route(web::post().to(logout)),
    )
    .service(
        web::resource("/api/user")
            .route(web::get().to(get_current_user)),
    );
}

pub fn configure_dashboard_routes(cfg: &mut web::ServiceConfig) {
    cfg.service(
        web::resource("/")
            .route(web::get().to(dashboard_page)),
    )
    .service(
        web::resource("/dashboard")
            .route(web::get().to(dashboard_page)),
    )
    .service(
        web::resource("/api/dashboard/stats")
            .route(web::get().to(dashboard_stats_api)),
    )
    .service(
        web::resource("/api/dashboard/activity")
            .route(web::get().to(recent_activity_api)),
    );
}

pub fn configure_repo_routes(cfg: &mut web::ServiceConfig) {
    cfg.service(
        web::resource("/repos")
            .route(web::get().to(repos_page)),
    )
    .service(
        web::resource("/repos/{id}")
            .route(web::get().to(repo_detail_page)),
    )
    .service(
        web::resource("/repos/{id}/settings")
            .route(web::get().to(repo_settings_page)),
    )
    .service(
        web::resource("/api/repos")
            .route(web::get().to(repos_api))
            .route(web::post().to(create_repo_api)),
    )
    .service(
        web::resource("/api/repos/{id}")
            .route(web::get().to(repo_api))
            .route(web::put().to(update_repo_api))
            .route(web::delete().to(delete_repo_api)),
    )
    .service(
        web::resource("/api/repos/{id}/sync")
            .route(web::post().to(sync_repo_api)),
    );
}

pub fn configure_merge_request_routes(cfg: &mut web::ServiceConfig) {
    cfg.service(
        web::resource("/merge-requests")
            .route(web::get().to(mrs_page)),
    )
    .service(
        web::resource("/merge-requests/{id}")
            .route(web::get().to(mr_detail_page)),
    )
    .service(
        web::resource("/api/merge-requests")
            .route(web::get().to(mrs_api)),
    )
    .service(
        web::resource("/api/merge-requests/{id}")
            .route(web::get().to(mr_api)),
    )
    .service(
        web::resource("/api/merge-requests/{id}/diff")
            .route(web::get().to(mr_diff_api)),
    )
    .service(
        web::resource("/api/merge-requests/{id}/comments")
            .route(web::post().to(create_comment_api)),
    )
    .service(
        web::resource("/api/merge-requests/{id}/status")
            .route(web::put().to(update_mr_status_api)),
    )
    .service(
        web::resource("/api/merge-requests/{id}/reviewers")
            .route(web::post().to(assign_reviewer_api)),
    );
}

pub fn configure_admin_routes(cfg: &mut web::ServiceConfig) {
    cfg.service(
        web::resource("/admin/organization")
            .route(web::get().to(organization_page)),
    )
    .service(
        web::resource("/admin/teams")
            .route(web::get().to(teams_page)),
    )
    .service(
        web::resource("/admin/teams/{id}/members")
            .route(web::get().to(team_members_page)),
    )
    .service(
        web::resource("/api/admin/teams")
            .route(web::post().to(create_team_api)),
    )
    .service(
        web::resource("/api/admin/teams/{id}/members")
            .route(web::post().to(add_member_api)),
    )
    .service(
        web::resource("/api/admin/teams/{team_id}/members/{user_id}")
            .route(web::put().to(update_role_api))
            .route(web::delete().to(remove_member_api)),
    );
}

pub fn configure_ai_review_routes(cfg: &mut web::ServiceConfig) {
    cfg.service(
        web::resource("/api/merge-requests/{id}/ai-review")
            .route(web::get().to(ai_review_api))
            .route(web::post().to(trigger_scan_api)),
    )
    .service(
        web::resource("/api/ai-review/{id}/suggestions")
            .route(web::post().to(act_on_suggestion_api)),
    );
}

pub fn configure_ai_rule_routes(cfg: &mut web::ServiceConfig) {
    cfg.service(
        web::resource("/ai-rules")
            .route(web::get().to(ai_rules_page)),
    )
    .service(
        web::resource("/api/ai-rules")
            .route(web::get().to(ai_rules_api))
            .route(web::post().to(create_ai_rule_api)),
    )
    .service(
        web::resource("/api/ai-rules/{id}")
            .route(web::get().to(ai_rule_api))
            .route(web::put().to(update_ai_rule_api))
            .route(web::delete().to(delete_ai_rule_api)),
    )
    .service(
        web::resource("/api/ai-rules/{id}/set-default")
            .route(web::post().to(set_default_rule_api)),
    )
    .service(
        web::resource("/api/repos/{repo_id}/effective-rules")
            .route(web::get().to(effective_rules_api)),
    );
}

pub fn configure_attachment_routes(cfg: &mut web::ServiceConfig) {
    cfg.service(
        web::resource("/api/attachments/{organization_id}/{attachment_type}/{target_id}")
            .route(web::post().to(upload_attachment_api))
            .route(web::get().to(list_attachments_api)),
    )
    .service(
        web::resource("/api/attachments/{id}")
            .route(web::delete().to(delete_attachment_api)),
    );
}

pub fn configure_checklist_routes(cfg: &mut web::ServiceConfig) {
    cfg.service(
        web::resource("/checklists")
            .route(web::get().to(checklists_page)),
    )
    .service(
        web::resource("/checklists/{id}")
            .route(web::get().to(checklist_detail_page)),
    )
    .service(
        web::resource("/api/checklists")
            .route(web::get().to(checklists_api))
            .route(web::post().to(create_checklist_api)),
    )
    .service(
        web::resource("/api/checklists/{id}")
            .route(web::get().to(checklist_api))
            .route(web::put().to(update_checklist_api))
            .route(web::delete().to(delete_checklist_api)),
    )
    .service(
        web::resource("/api/merge-requests/{mr_id}/checklist-items/{item_id}")
            .route(web::post().to(check_item_api)),
    );
}

pub fn configure_comment_routes(cfg: &mut web::ServiceConfig) {
    cfg.service(
        web::resource("/api/comments/{id}")
            .route(web::put().to(update_comment_api))
            .route(web::delete().to(delete_comment_api)),
    )
    .service(
        web::resource("/api/comments/{id}/resolve")
            .route(web::post().to(resolve_comment_api)),
    );
}

pub fn configure_issue_routes(cfg: &mut web::ServiceConfig) {
    cfg.service(
        web::resource("/issues")
            .route(web::get().to(issues_page)),
    )
    .service(
        web::resource("/issues/{id}")
            .route(web::get().to(issue_detail_page)),
    )
    .service(
        web::resource("/api/issues")
            .route(web::get().to(issues_api))
            .route(web::post().to(create_issue_api)),
    )
    .service(
        web::resource("/api/issues/{id}")
            .route(web::get().to(issue_api))
            .route(web::put().to(update_issue_api)),
    )
    .service(
        web::resource("/api/issues/{id}/status")
            .route(web::put().to(update_issue_status_api)),
    )
    .service(
        web::resource("/api/issues/{id}/assignee")
            .route(web::post().to(assign_issue_api)),
    );
}

pub fn configure_notification_routes(cfg: &mut web::ServiceConfig) {
    cfg.service(
        web::resource("/notifications")
            .route(web::get().to(notifications_page)),
    )
    .service(
        web::resource("/notifications/settings")
            .route(web::get().to(notifications_settings_page)),
    )
    .service(
        web::resource("/api/notifications")
            .route(web::get().to(notifications_api)),
    )
    .service(
        web::resource("/api/notifications/{id}/read")
            .route(web::post().to(mark_read_api)),
    )
    .service(
        web::resource("/api/notifications/read-all")
            .route(web::post().to(mark_all_read_api)),
    )
    .service(
        web::resource("/api/notifications/settings")
            .route(web::get().to(settings_api))
            .route(web::put().to(update_settings_api)),
    );
}

pub fn configure_stats_routes(cfg: &mut web::ServiceConfig) {
    cfg.service(
        web::resource("/stats")
            .route(web::get().to(stats_page)),
    )
    .service(
        web::resource("/api/stats/coverage")
            .route(web::get().to(coverage_stats_api)),
    )
    .service(
        web::resource("/api/stats/heatmap")
            .route(web::get().to(heatmap_api)),
    )
    .service(
        web::resource("/api/stats/personal")
            .route(web::get().to(personal_stats_api)),
    )
    .service(
        web::resource("/api/stats/team-ranking")
            .route(web::get().to(team_ranking_api)),
    )
    .service(
        web::resource("/api/stats/export")
            .route(web::post().to(export_report_api)),
    )
    .service(
        web::resource("/org-stats")
            .route(web::get().to(org_stats_page)),
    )
    .service(
        web::resource("/api/org-stats/{organization_id}/overview")
            .route(web::get().to(org_overview_api)),
    )
    .service(
        web::resource("/api/org-stats/{organization_id}/repo-health")
            .route(web::get().to(org_repo_health_api)),
    )
    .service(
        web::resource("/api/org-stats/{organization_id}/contributor-ranking")
            .route(web::get().to(org_contributor_ranking_api)),
    )
    .service(
        web::resource("/api/org-stats/{organization_id}/issue-trend")
            .route(web::get().to(org_issue_trend_api)),
    )
    .service(
        web::resource("/api/org-stats/{organization_id}/refresh-mv")
            .route(web::post().to(org_refresh_mv_api)),
    );
}

pub fn configure_all_routes<N: NotificationService + 'static>(cfg: &mut web::ServiceConfig) {
    configure_auth_routes(cfg);
    configure_dashboard_routes(cfg);
    configure_repo_routes(cfg);
    configure_merge_request_routes(cfg);
    configure_admin_routes(cfg);
    configure_ai_review_routes(cfg);
    configure_ai_rule_routes(cfg);
    configure_attachment_routes(cfg);
    configure_checklist_routes(cfg);
    configure_comment_routes(cfg);
    configure_issue_routes(cfg);
    configure_notification_routes(cfg);
    configure_stats_routes(cfg);
    configure_webhook_routes::<N>(cfg);
}

pub fn configure(cfg: &mut web::ServiceConfig) {
    configure_all_routes::<crate::repositories::NotificationRepository>(cfg);
}
