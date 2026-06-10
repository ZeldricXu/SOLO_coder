use regex::Regex;
use uuid::Uuid;

use crate::models::comment::Comment;
use crate::models::issue::Issue;
use crate::models::merge_request::MergeRequest;
use crate::models::notification::{
    Notification, NotificationQuery, NotificationSettings, NotificationType,
    UpdateNotificationSettingsRequest,
};
use crate::models::stats::{DashboardStats, ReviewStats};
use crate::models::user::User;
use crate::providers::{DingtalkClient, EmailClient, SlackClient};
use crate::repositories::{NotificationRepository, StatsRepository, UserRepository};
use crate::utils::{AppError, AppResult, PaginatedResult};

#[derive(Clone)]
pub struct NotificationService {
    notification_repo: NotificationRepository,
    user_repo: UserRepository,
    stats_repo: StatsRepository,
    slack_client: Option<SlackClient>,
    dingtalk_client: Option<DingtalkClient>,
    email_client: Option<EmailClient>,
}

impl NotificationService {
    pub fn new(
        notification_repo: NotificationRepository,
        user_repo: UserRepository,
        stats_repo: StatsRepository,
        slack_client: Option<SlackClient>,
        dingtalk_client: Option<DingtalkClient>,
        email_client: Option<EmailClient>,
    ) -> Self {
        Self {
            notification_repo,
            user_repo,
            stats_repo,
            slack_client,
            dingtalk_client,
            email_client,
        }
    }

    pub async fn create_notification(
        &self,
        user_id: Uuid,
        notification_type: NotificationType,
        title: &str,
        content: &str,
        related_url: Option<&str>,
    ) -> AppResult<Notification> {
        let settings = self.get_or_create_settings(user_id).await?;

        if !self.should_send_notification(&settings, &notification_type) {
            return Err(AppError::Validation(
                "User has disabled this notification type".to_string(),
            ));
        }

        let notification = self
            .notification_repo
            .create(user_id, notification_type.as_str(), title, content, related_url)
            .await?;

        let user = self.user_repo.get_by_id(user_id).await?.ok_or_else(|| {
            AppError::NotFound(format!("User with id {} not found", user_id))
        })?;

        if settings.email_enabled {
            if let Some(email_client) = &self.email_client {
                let _ = self
                    .send_email_notification(email_client, &user, title, content, related_url)
                    .await;
            }
        }

        if settings.slack_enabled {
            if let Some(webhook_url) = &settings.slack_webhook_url {
                let slack_client = SlackClient::new(webhook_url.clone());
                let _ = self.send_im_notification(&slack_client, title, content).await;
            } else if let Some(slack_client) = &self.slack_client {
                let _ = self.send_im_notification(slack_client, title, content).await;
            }
        }

        if settings.dingtalk_enabled {
            if let Some(webhook_url) = &settings.dingtalk_webhook_url {
                let dingtalk_client = DingtalkClient::new(webhook_url.clone(), None);
                let _ = self
                    .send_dingtalk_notification(&dingtalk_client, title, content)
                    .await;
            } else if let Some(dingtalk_client) = &self.dingtalk_client {
                let _ = self
                    .send_dingtalk_notification(dingtalk_client, title, content)
                    .await;
            }
        }

        Ok(notification)
    }

    pub async fn get_notifications(
        &self,
        user_id: Uuid,
        query: NotificationQuery,
    ) -> AppResult<PaginatedResult<Notification>> {
        let page = query.page.unwrap_or(1);
        let per_page = query.per_page.unwrap_or(20);

        let (notifications, total) = self
            .notification_repo
            .list_by_user(
                user_id,
                query.type_.as_deref(),
                query.read,
                page,
                per_page,
            )
            .await?;

        Ok(PaginatedResult::new(notifications, page, per_page, total))
    }

    pub async fn mark_read(&self, notification_id: Uuid, user_id: Uuid) -> AppResult<Notification> {
        self.notification_repo
            .mark_read(notification_id, user_id)
            .await
    }

    pub async fn mark_all_read(&self, user_id: Uuid) -> AppResult<u64> {
        self.notification_repo.mark_all_read(user_id).await
    }

    pub async fn get_unread_count(&self, user_id: Uuid) -> AppResult<i64> {
        self.notification_repo.get_unread_count(user_id).await
    }

    pub async fn get_settings(&self, user_id: Uuid) -> AppResult<NotificationSettings> {
        self.get_or_create_settings(user_id).await
    }

    pub async fn update_settings(
        &self,
        user_id: Uuid,
        req: &UpdateNotificationSettingsRequest,
    ) -> AppResult<NotificationSettings> {
        self.get_or_create_settings(user_id).await?;
        self.notification_repo.update_settings(user_id, req).await
    }

    pub async fn send_im_notification(
        &self,
        slack_client: &SlackClient,
        title: &str,
        content: &str,
    ) -> AppResult<()> {
        let message = format!("*{}\n\n{}", title, content);
        slack_client.send_markdown(&message).await
    }

    pub async fn send_dingtalk_notification(
        &self,
        dingtalk_client: &DingtalkClient,
        title: &str,
        content: &str,
    ) -> AppResult<()> {
        dingtalk_client
            .send_markdown(title, content, None, false)
            .await
    }

    pub async fn send_email_notification(
        &self,
        email_client: &EmailClient,
        user: &User,
        title: &str,
        content: &str,
        related_url: Option<&str>,
    ) -> AppResult<()> {
        let html_body = format!(
            r#"<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <style>
        body {{ font-family: Arial, sans-serif; line-height: 1.6; color: #333; }}
        .container {{ max-width: 600px; margin: 0 auto; padding: 20px; }}
        .header {{ background: #16213e; color: white; padding: 15px; border-radius: 5px; }}
        .content {{ margin: 20px 0; white-space: pre-wrap; }}
        .button {{ display: inline-block; padding: 10px 20px; background: #0f3460; color: white; text-decoration: none; border-radius: 5px; }}
        .footer {{ margin-top: 30px; padding-top: 20px; border-top: 1px solid #ddd; color: #999; font-size: 12px; }}
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h3>{title}</h3>
        </div>
        <div class="content">{content}</div>
        {button}
        <div class="footer">
            此邮件由 Code Review Platform 自动发送，请勿直接回复。<br>
            您可以在通知设置中调整接收偏好。
        </div>
    </div>
</body>
</html>"#,
            title = title,
            content = content,
            button = if let Some(url) = related_url {
                format!(
                    "<p><a href=\"{}\" class=\"button\">查看详情</a></p>",
                    url
                )
            } else {
                String::new()
            }
        );

        let text_body = format!(
            "{}\n\n{}\n\n{}",
            title,
            content,
            related_url.unwrap_or("")
        );

        email_client
            .send_html_email(&[user.email.clone()], title, &html_body, Some(&text_body))
            .await
    }

    pub async fn notify_new_review(
        &self,
        merge_request: &MergeRequest,
        reviewer_id: Uuid,
        merge_request_url: &str,
    ) -> AppResult<()> {
        let author = self
            .user_repo
            .get_by_id(merge_request.author_id)
            .await?
            .ok_or_else(|| {
                AppError::NotFound(format!(
                    "User with id {} not found",
                    merge_request.author_id
                ))
            })?;

        let reviewer = self
            .user_repo
            .get_by_id(reviewer_id)
            .await?
            .ok_or_else(|| {
                AppError::NotFound(format!("User with id {} not found", reviewer_id))
            })?;

        let title = "新的代码评审请求".to_string();
        let content = format!(
            "{} 邀请您评审 MR「{}」\n\n目标分支: {} → {}",
            author.username, merge_request.title, merge_request.source_branch, merge_request.target_branch
        );

        let _ = self
            .create_notification(
                reviewer_id,
                NotificationType::NewReview,
                &title,
                &content,
                Some(merge_request_url),
            )
            .await;

        Ok(())
    }

    pub async fn notify_new_comment(
        &self,
        comment: &Comment,
        merge_request: &MergeRequest,
        merge_request_url: &str,
    ) -> AppResult<()> {
        let author = self
            .user_repo
            .get_by_id(comment.author_id)
            .await?
            .ok_or_else(|| {
                AppError::NotFound(format!(
                    "User with id {} not found",
                    comment.author_id
                ))
            })?;

        let mentioned_users = self.extract_mentions(&comment.content);

        let mut notified_users = std::collections::HashSet::new();

        for username in &mentioned_users {
            if let Ok(Some(user)) = self.user_repo.get_by_username(username).await {
                if user.id != comment.author_id && !notified_users.contains(&user.id) {
                    notified_users.insert(user.id);

                    let title = format!("{} 提到了你", author.username);
                    let content = format!(
                        "{} 在 MR「{}」中提到了您：\n\n{}",
                        author.username, merge_request.title, comment.content
                    );

                    let url = format!("{}#comment-{}", merge_request_url, comment.id);

                    let _ = self
                        .create_notification(
                            user.id,
                            NotificationType::Mention,
                            &title,
                            &content,
                            Some(&url),
                        )
                        .await;
                }
            }
        }

        if merge_request.author_id != comment.author_id
            && !notified_users.contains(&merge_request.author_id)
        {
            let title = format!("{} 发表了评论", author.username);
            let content = format!(
                "{} 在您的 MR「{}」中发表了评论：\n\n{}",
                author.username, merge_request.title, comment.content
            );

            let url = format!("{}#comment-{}", merge_request_url, comment.id);

            let _ = self
                .create_notification(
                    merge_request.author_id,
                    NotificationType::NewComment,
                    &title,
                    &content,
                    Some(&url),
                )
                .await;
        }

        Ok(())
    }

    pub async fn notify_issue_assigned(
        &self,
        issue: &Issue,
        merge_request: Option<&MergeRequest>,
        issue_url: &str,
    ) -> AppResult<()> {
        let assignee_id = issue.assignee_id.ok_or_else(|| {
            AppError::Validation("Issue has no assignee".to_string())
        })?;

        let reporter = self
            .user_repo
            .get_by_id(issue.reporter_id)
            .await?
            .ok_or_else(|| {
                AppError::NotFound(format!(
                    "User with id {} not found",
                    issue.reporter_id
                ))
            })?;

        let title = "问题已分配给您".to_string();
        let content = if let Some(mr) = merge_request {
            format!(
                "{} 将问题「{}」分配给您处理\n\n所属 MR: {}\n严重程度: {}",
                reporter.username, issue.title, mr.title, issue.severity
            )
        } else {
            format!(
                "{} 将问题「{}」分配给您处理\n\n严重程度: {}",
                reporter.username, issue.title, issue.severity
            )
        };

        let _ = self
            .create_notification(
                assignee_id,
                NotificationType::IssueAssigned,
                &title,
                &content,
                Some(issue_url),
            )
            .await;

        Ok(())
    }

    pub async fn notify_issue_status_changed(
        &self,
        issue: &Issue,
        old_status: &str,
        changer_id: Uuid,
        issue_url: &str,
    ) -> AppResult<()> {
        let changer = self
            .user_repo
            .get_by_id(changer_id)
            .await?
            .ok_or_else(|| {
                AppError::NotFound(format!("User with id {} not found", changer_id))
            })?;

        let status_text = match issue.status.as_str() {
            "open" => "已打开",
            "in_progress" => "正在处理",
            "pending_review" => "待验证",
            "resolved" => "已解决",
            "closed" => "已关闭",
            _ => "状态已变更",
        };

        let old_status_text = match old_status {
            "open" => "打开",
            "in_progress" => "处理中",
            "pending_review" => "待验证",
            "resolved" => "已解决",
            "closed" => "已关闭",
            _ => "未知",
        };

        let title = format!("问题状态变更: {}", status_text);
        let content = format!(
            "{} 将问题「{}」的状态从「{}」更新为「{}」",
            changer.username, issue.title, old_status_text, status_text
        );

        if let Some(assignee_id) = issue.assignee_id {
            if assignee_id != changer_id {
                let _ = self
                    .create_notification(
                        assignee_id,
                        NotificationType::IssueStatusChanged,
                        &title,
                        &content,
                        Some(issue_url),
                    )
                    .await;
            }
        }

        if issue.reporter_id != changer_id {
            let _ = self
                .create_notification(
                    issue.reporter_id,
                    NotificationType::IssueStatusChanged,
                    &title,
                    &content,
                    Some(issue_url),
                )
                .await;
        }

        Ok(())
    }

    pub async fn notify_mr_status_changed(
        &self,
        merge_request: &MergeRequest,
        old_status: &str,
        changer_id: Uuid,
        merge_request_url: &str,
    ) -> AppResult<()> {
        let changer = self
            .user_repo
            .get_by_id(changer_id)
            .await?
            .ok_or_else(|| {
                AppError::NotFound(format!("User with id {} not found", changer_id))
            })?;

        let status_text = match merge_request.status.as_str() {
            "open" => "已打开",
            "reviewing" => "评审中",
            "approved" => "已批准",
            "changes_requested" => "需要修改",
            "merged" => "已合并",
            "closed" => "已关闭",
            _ => "状态已变更",
        };

        let old_status_text = match old_status {
            "open" => "打开",
            "reviewing" => "评审中",
            "approved" => "已批准",
            "changes_requested" => "需要修改",
            "merged" => "已合并",
            "closed" => "已关闭",
            _ => "未知",
        };

        let title = format!("MR 状态变更: {}", status_text);
        let content = format!(
            "{} 将 MR「{}」的状态从「{}」更新为「{}」",
            changer.username, merge_request.title, old_status_text, status_text
        );

        if merge_request.author_id != changer_id {
            let _ = self
                .create_notification(
                    merge_request.author_id,
                    NotificationType::MrStatusChanged,
                    &title,
                    &content,
                    Some(merge_request_url),
                )
                .await;
        }

        Ok(())
    }

    pub async fn send_daily_digest(&self, user_id: Uuid, organization_id: Uuid) -> AppResult<()> {
        let settings = self.get_or_create_settings(user_id).await?;

        if !settings.daily_digest {
            return Ok(());
        }

        let user = self.user_repo.get_by_id(user_id).await?.ok_or_else(|| {
            AppError::NotFound(format!("User with id {} not found", user_id))
        })?;

        let today = chrono::Utc::now();
        let start_date = Some(today - chrono::Duration::days(1));
        let end_date = Some(today);

        let review_stats = self
            .stats_repo
            .get_review_stats(start_date, end_date, None, None, organization_id)
            .await?;

        let dashboard = self
            .stats_repo
            .get_dashboard_stats(user_id, organization_id)
            .await?;

        let title = format!("📊 每日摘要 - {}", today.format("%Y-%m-%d"));
        let content = self.generate_daily_digest_content(&review_stats, &dashboard);

        if settings.email_enabled {
            if let Some(email_client) = &self.email_client {
                let html_body = self.generate_daily_digest_html(&review_stats, &dashboard, &today);
                let _ = email_client
                    .send_html_email(
                        &[user.email.clone()],
                        &title,
                        &html_body,
                        Some(&content),
                    )
                    .await;
            }
        }

        if settings.slack_enabled {
            if let Some(webhook_url) = &settings.slack_webhook_url {
                let slack_client = SlackClient::new(webhook_url.clone());
                if let Some(slack_client) = &self.slack_client {
                    let _ = self.send_im_notification(slack_client, &title, &content).await;
                }
            } else if let Some(slack_client) = &self.slack_client {
                let _ = self.send_im_notification(slack_client, &title, &content).await;
            }
        }

        if settings.dingtalk_enabled {
            if let Some(webhook_url) = &settings.dingtalk_webhook_url {
                let dingtalk_client = DingtalkClient::new(webhook_url.clone(), None);
                let _ = self
                    .send_dingtalk_notification(&dingtalk_client, &title, &content)
                    .await;
            } else if let Some(dingtalk_client) = &self.dingtalk_client {
                let _ = self
                    .send_dingtalk_notification(dingtalk_client, &title, &content)
                    .await;
            }
        }

        let _ = self
            .notification_repo
            .create(
                user_id,
                NotificationType::DailyDigest.as_str(),
                &title,
                &content,
                None,
            )
            .await;

        Ok(())
    }

    pub fn should_send_notification(
        &self,
        settings: &NotificationSettings,
        notification_type: &NotificationType,
    ) -> bool {
        match notification_type {
            NotificationType::NewReview => settings.on_new_review,
            NotificationType::NewComment => settings.on_comment,
            NotificationType::Mention => settings.on_mention,
            NotificationType::IssueAssigned => settings.on_issue_assigned,
            NotificationType::IssueStatusChanged => settings.on_comment,
            NotificationType::MrStatusChanged => settings.on_new_review,
            NotificationType::ChecklistCompleted => settings.on_comment,
            NotificationType::DailyDigest => settings.daily_digest,
            NotificationType::System => true,
        }
    }

    async fn get_or_create_settings(&self, user_id: Uuid) -> AppResult<NotificationSettings> {
        if let Some(settings) = self.notification_repo.get_settings(user_id).await? {
            return Ok(settings);
        }

        self.notification_repo
            .create_default_settings(user_id)
            .await
    }

    fn extract_mentions(&self, content: &str) -> Vec<String> {
        let re = Regex::new(r"@(\w+)").unwrap();
        re.captures_iter(content)
            .filter_map(|cap| cap.get(1).map(|m| m.as_str().to_string()))
            .collect()
    }

    fn generate_daily_digest_content(
        &self,
        stats: &ReviewStats,
        dashboard: &DashboardStats,
    ) -> String {
        format!(
            r#"📊 今日代码评审统计

团队概览:
• 评审覆盖率: {:.1}%
• 平均响应时间: {:.1} 小时
• 今日 MR 数: {}
• 今日问题数: {}

您的待办:
• 待我评审: {} 个 MR
• 分配给我的问题: {} 个
• 我创建的问题: {} 个

--
Code Review Platform"#,
            stats.coverage_rate,
            stats.avg_response_time_hours,
            stats.total_mrs,
            stats.total_issues,
            dashboard.my_pending_reviews,
            dashboard.issues_assigned_to_me,
            dashboard.my_open_issues
        )
    }

    fn generate_daily_digest_html(
        &self,
        stats: &ReviewStats,
        dashboard: &DashboardStats,
        today: &chrono::DateTime<chrono::Utc>,
    ) -> String {
        format!(
            r#"<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <style>
        body {{ font-family: Arial, sans-serif; line-height: 1.6; color: #333; }}
        .container {{ max-width: 600px; margin: 0 auto; padding: 20px; }}
        .header {{ background: linear-gradient(135deg, #16213e, #0f3460); color: white; padding: 20px; border-radius: 8px; text-align: center; }}
        .section {{ margin: 25px 0; }}
        .section-title {{ font-size: 18px; font-weight: bold; color: #16213e; margin-bottom: 15px; border-bottom: 2px solid #e5e7eb; padding-bottom: 8px; }}
        .stats-grid {{ display: grid; grid-template-columns: repeat(2, 1fr); gap: 15px; }}
        .stat-card {{ background: #f8f9fa; padding: 15px; border-radius: 8px; text-align: center; }}
        .stat-value {{ font-size: 28px; font-weight: bold; color: #0f3460; }}
        .stat-label {{ font-size: 13px; color: #6b7280; margin-top: 5px; }}
        .todo-list {{ background: #fffbeb; padding: 15px; border-radius: 8px; border-left: 4px solid #f59e0b; }}
        .todo-item {{ padding: 8px 0; border-bottom: 1px solid #fef3c7; }}
        .todo-item:last-child {{ border-bottom: none; }}
        .todo-count {{ font-weight: bold; color: #d97706; }}
        .footer {{ margin-top: 30px; padding-top: 20px; border-top: 1px solid #e5e7eb; color: #9ca3af; font-size: 12px; text-align: center; }}
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>📊 每日代码评审摘要</h1>
            <div>{date}</div>
        </div>

        <div class="section">
            <div class="section-title">📈 团队概览</div>
            <div class="stats-grid">
                <div class="stat-card">
                    <div class="stat-value">{coverage:.1}%</div>
                    <div class="stat-label">评审覆盖率</div>
                </div>
                <div class="stat-card">
                    <div class="stat-value">{response_time:.1}h</div>
                    <div class="stat-label">平均响应时间</div>
                </div>
                <div class="stat-card">
                    <div class="stat-value">{total_mrs}</div>
                    <div class="stat-label">今日 MR 数</div>
                </div>
                <div class="stat-card">
                    <div class="stat-value">{total_issues}</div>
                    <div class="stat-label">今日问题数</div>
                </div>
            </div>
        </div>

        <div class="section">
            <div class="section-title">📋 您的待办事项</div>
            <div class="todo-list">
                <div class="todo-item">
                    <span class="todo-count">{pending_reviews}</span> 个 MR 待您评审
                </div>
                <div class="todo-item">
                    <span class="todo-count">{assigned_issues}</span> 个问题分配给您
                </div>
                <div class="todo-item">
                    <span class="todo-count">{my_issues}</span> 个您创建的问题待处理
                </div>
            </div>
        </div>

        <div class="footer">
            此邮件由 Code Review Platform 自动发送，请勿直接回复。<br>
            您可以在通知设置中调整接收偏好。
        </div>
    </div>
</body>
</html>"#,
            date = today.format("%Y年%m月%d日"),
            coverage = stats.coverage_rate,
            response_time = stats.avg_response_time_hours,
            total_mrs = stats.total_mrs,
            total_issues = stats.total_issues,
            pending_reviews = dashboard.my_pending_reviews,
            assigned_issues = dashboard.issues_assigned_to_me,
            my_issues = dashboard.my_open_issues
        )
    }
}
