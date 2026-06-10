pub mod admin;
pub mod checklist;
pub mod components;
pub mod dashboard;
pub mod issues;
pub mod layout;
pub mod login;
pub mod merge_requests;
pub mod notifications;
pub mod repos;
pub mod review_detail;
pub mod stats;

pub use admin::{
    Organization, Team, TeamMember, Repo, TeamTreeNode, TeamNode,
    organization_page, teams_page, team_members_page,
};

pub use checklist::{
    ChecklistTemplate, ChecklistItem, ChecklistGroup, ChecklistDetail,
    checklists_page, checklist_detail_page,
};

pub use components::{
    StatCard, ChecklistItemData, ActivityItemData, PaginationData, TabData, ProgressBarData,
    stat_card, status_badge, severity_badge, role_badge, user_avatar, comment_bubble, diff_line,
    checklist_item, activity_item, pagination, modal, button, input_field, select_field,
    textarea_field, card, table, tabs, progress_bar,
};

pub use dashboard::dashboard_page;

pub use issues::{
    IssuesPageContext, IssueDetailPageContext, issues_page, issue_detail_page,
};

pub use layout::{
    FlashMessage, UserContext, LayoutContext, base_layout,
};

pub use login::{LoginContext, login_page};

pub use merge_requests::mrs_page;

pub use notifications::{
    Notification, NotificationCategory, NotificationSettings, NotificationEvents,
    notifications_page, notifications_settings_page,
};

pub use repos::{repos_page, repo_detail_page};

pub use review_detail::review_detail_page;

pub use stats::{StatsPageContext, stats_page};
