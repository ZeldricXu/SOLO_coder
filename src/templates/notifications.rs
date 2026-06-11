use maud::{html, Markup};
use crate::templates::layout::{base_layout, LayoutContext};
use crate::templates::components::{
    card, button, modal, input_field, user_avatar, ButtonVariant,
};

pub enum NotificationType {
    NewReview,
    Comment,
    Mention,
    IssueAssigned,
    System,
}

pub struct Notification {
    pub id: String,
    pub type_: NotificationType,
    pub title: String,
    pub content: String,
    pub sender_name: Option<String>,
    pub sender_avatar_url: Option<String>,
    pub time: String,
    pub is_read: bool,
    pub link: String,
}

pub struct NotificationCategory {
    pub id: String,
    pub name: String,
    pub icon: String,
    pub unread_count: u32,
}

pub struct NotificationSettings {
    pub email_enabled: bool,
    pub email_test_sent: bool,
    pub slack_enabled: bool,
    pub slack_webhook_url: String,
    pub dingtalk_enabled: bool,
    pub dingtalk_webhook_url: String,
    pub dingtalk_sign_secret: String,
    pub events: NotificationEvents,
}

pub struct NotificationEvents {
    pub new_review_assigned: bool,
    pub mr_new_comment: bool,
    pub mentioned: bool,
    pub issue_assigned: bool,
    pub issue_status_changed: bool,
    pub mr_status_changed: bool,
    pub daily_digest: bool,
}

fn notification_icon(type_: &NotificationType) -> (&'static str, &'static str) {
    match type_ {
        NotificationType::NewReview => ("🔀", "text-[#3B82F6]"),
        NotificationType::Comment => ("💬", "text-[#10B981]"),
        NotificationType::Mention => ("@", "text-[#8B5CF6]"),
        NotificationType::IssueAssigned => ("🐛", "text-[#F59E0B]"),
        NotificationType::System => ("⚙️", "text-[#64748B]"),
    }
}

pub fn notifications_page(ctx: LayoutContext, notifications: &[Notification], categories: &[NotificationCategory], active_category: &str) -> Markup {
    let total_unread: u32 = categories.iter().map(|c| c.unread_count).sum();

    base_layout(ctx, html! {
        div class="flex gap-6 h-[calc(100vh-12rem)]" {
            div class="w-64 flex-shrink-0" {
                (card(None, html! {
                    div class="space-y-1" {
                        @for category in categories {
                            button
                                onclick={"filterNotifications('" (category.id) "')"}
                                class={
                                    "w-full flex items-center gap-3 px-4 py-3 rounded-lg transition-all duration-200 text-left"
                                    @if category.id == active_category { "bg-[#3B82F6]/20 text-white border-l-2 border-[#3B82F6]" }
                                    @else { "text-[#94A3B8] hover:bg-white/5 hover:text-white" }
                                }
                            {
                                span class="text-lg" { (category.icon) }
                                span class="flex-1" { (category.name) }
                                @if category.unread_count > 0 {
                                    span class="w-5 h-5 bg-red-500 rounded-full text-xs flex items-center justify-center text-white" {
                                        (category.unread_count)
                                    }
                                }
                            }
                        }
                    }
                }, None))
            }

            div class="flex-1 flex flex-col min-h-0" {
                div class="flex items-center justify-between mb-4" {
                    div {
                        h1 class="text-2xl font-bold text-white" { "通知中心" }
                        @if total_unread > 0 {
                            p class="text-[#94A3B8] text-sm" { "你有 " (total_unread) " 条未读通知" }
                        }
                    }
                    div class="flex gap-2" {
                        (button(ButtonVariant::Secondary, "✓ 全部标记已读", Some("markAllAsRead()"), false))
                        a href="/notifications/settings" {
                            (button(ButtonVariant::Ghost, "⚙️ 设置", None, false))
                        }
                    }
                }

                (card(None, html! {
                    @if notifications.is_empty() {
                        div class="text-center py-16" {
                            div class="text-6xl mb-4" { "🔔" }
                            h3 class="text-xl font-semibold text-white mb-2" { "暂无通知" }
                            p class="text-[#94A3B8]" { "你目前没有任何通知" }
                        }
                    } @else {
                        div class="divide-y divide-[#334155]/50 max-h-[calc(100vh-20rem)] overflow-y-auto" id="notificationList" {
                            @for notification in notifications {
                                @let (icon, icon_color) = notification_icon(&notification.type_);
                                a href=(notification.link) class={
                                    "block p-4 hover:bg-white/5 transition-colors cursor-pointer"
                                    @if !notification.is_read { "bg-[#3B82F6]/5" }
                                } {
                                    div class="flex gap-4" {
                                        div class="flex-shrink-0" {
                                            @if let Some(sender_name) = &notification.sender_name {
                                                (user_avatar(sender_name, notification.sender_avatar_url.as_deref(), 40))
                                            } @else {
                                                div class={
                                                    "w-10 h-10 rounded-full flex items-center justify-center text-lg bg-[#1E293B]"
                                                } {
                                                    span class=(icon_color) { (icon) }
                                                }
                                            }
                                        }
                                        div class="flex-1 min-w-0" {
                                            div class="flex items-start justify-between gap-4" {
                                                div class="flex-1" {
                                                    p class={
                                                        "mb-1"
                                                        @if !notification.is_read { "font-semibold text-white" }
                                                        @else { "text-[#CBD5E1]" }
                                                    } {
                                                        @if !notification.is_read {
                                                            span class="inline-block w-2 h-2 bg-[#3B82F6] rounded-full mr-2" {}
                                                        }
                                                        (notification.title)
                                                    }
                                                    p class="text-sm text-[#94A3B8] line-clamp-2" { (notification.content) }
                                                }
                                                div class="flex-shrink-0 text-right" {
                                                    p class="text-xs text-[#64748B] whitespace-nowrap" { (notification.time) }
                                                    div class={
                                                        "mt-1 inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded-full"
                                                        (icon_color) " bg-opacity-10"
                                                    } {
                                                        span class=(icon_color) { (icon) }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        div class="text-center py-4 border-t border-[#334155]/50" id="loadMoreContainer" {
                            button onclick="loadMoreNotifications()" class="text-[#94A3B8] hover:text-[#3B82F6] text-sm transition-colors" {
                                "加载更多"
                            }
                        }
                    }
                }, None))
            }
        }

        script {
            (maud::PreEscaped(r#"
                function filterNotifications(category) {
                    window.location.href = '/notifications?category=' + category;
                }
                function markAllAsRead() {
                    if (confirm('确定要将所有通知标记为已读吗？')) {
                        // TODO: 实现标记已读逻辑
                    }
                }
                function loadMoreNotifications() {
                    const container = document.getElementById('loadMoreContainer');
                    container.innerHTML = '<span class="text-[#94A3B8] text-sm">加载中...</span>';
                    // TODO: 实现无限滚动加载逻辑
                }

                const observer = new IntersectionObserver((entries) => {
                    entries.forEach(entry => {
                        if (entry.isIntersecting) {
                            // loadMoreNotifications();
                        }
                    });
                }, { threshold: 0.1 });

                const loadMore = document.getElementById('loadMoreContainer');
                if (loadMore) {
                    observer.observe(loadMore);
                }
            "#))
        }
    })
}

pub fn notifications_settings_page(ctx: LayoutContext, settings: &NotificationSettings) -> Markup {
    base_layout(ctx, html! {
        div class="space-y-6 max-w-4xl mx-auto" {
            div class="flex items-center justify-between" {
                div {
                    h1 class="text-2xl font-bold text-white" { "通知设置" }
                    p class="text-[#94A3B8]" { "管理你的通知偏好" }
                }
                a href="/notifications" {
                    (button(ButtonVariant::Ghost, "← 返回通知", None, false))
                }
            }

            form action="/notifications/settings" method="POST" class="space-y-6" {
                input type="hidden" name="csrf_token" value=(ctx.csrf_token);

                (card(Some("通知渠道"), html! {
                    div class="space-y-6" {
                        div class="flex items-center justify-between p-4 bg-[#0F172A] rounded-xl" {
                            div class="flex items-center gap-4" {
                                div class="w-12 h-12 bg-[#1E293B] rounded-xl flex items-center justify-center text-xl" {
                                    "🔔"
                                }
                                div {
                                    h4 class="font-medium text-white" { "站内通知" }
                                    p class="text-sm text-[#94A3B8]" { "网站内的实时通知（总是开启）" }
                                }
                            }
                            label class="relative inline-flex items-center cursor-pointer" {
                                input type="checkbox" checked disabled class="sr-only peer";
                                div class="w-11 h-6 bg-[#3B82F6] peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full after:content-[''] after:absolute after:top-0.5 after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all opacity-50 cursor-not-allowed" {}
                            }
                        }

                        div class="flex items-center justify-between p-4 bg-[#0F172A] rounded-xl" {
                            div class="flex items-center gap-4" {
                                div class="w-12 h-12 bg-[#1E293B] rounded-xl flex items-center justify-center text-xl" {
                                    "📧"
                                }
                                div class="flex-1" {
                                    h4 class="font-medium text-white" { "邮件通知" }
                                    p class="text-sm text-[#94A3B8]" { "通过邮件接收重要通知" }
                                }
                                button type="button" onclick="testEmail()" class={
                                    "px-3 py-1.5 rounded-lg text-sm transition-colors mr-2"
                                    @if settings.email_enabled { "bg-[#334155] text-white hover:bg-[#475569]" }
                                    @else { "bg-[#334155]/50 text-[#64748B] cursor-not-allowed" }
                                } disabled[!settings.email_enabled] {
                                    @if settings.email_test_sent { "✓ 已发送" } @else { "测试" }
                                }
                            }
                            label class="relative inline-flex items-center cursor-pointer" {
                                input type="checkbox" name="email_enabled" checked[settings.email_enabled] class="sr-only peer" onchange="toggleEmailSettings()";
                                div class="w-11 h-6 bg-[#334155] peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:bg-[#3B82F6] after:content-[''] after:absolute after:top-0.5 after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all" {}
                            }
                        }

                        div class="space-y-4 p-4 bg-[#0F172A] rounded-xl" id="slackSettings" {
                            div class="flex items-center justify-between" {
                                div class="flex items-center gap-4" {
                                    div class="w-12 h-12 bg-[#1E293B] rounded-xl flex items-center justify-center text-xl" {
                                        "💬"
                                    }
                                    div {
                                        h4 class="font-medium text-white" { "Slack 通知" }
                                        p class="text-sm text-[#94A3B8]" { "通过 Slack Webhook 发送通知" }
                                    }
                                }
                                label class="relative inline-flex items-center cursor-pointer" {
                                    input type="checkbox" name="slack_enabled" checked[settings.slack_enabled] class="sr-only peer" onchange="toggleSlackSettings()";
                                    div class="w-11 h-6 bg-[#334155] peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:bg-[#3B82F6] after:content-[''] after:absolute after:top-0.5 after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all" {}
                                }
                            }
                            div id="slackWebhookInput" class={ @if !settings.slack_enabled { "hidden" } } {
                                div class="space-y-1" {
                                    label class="text-sm text-[#94A3B8]" { "Webhook URL" }
                                    (input_field("slack_webhook_url", &settings.slack_webhook_url, "https://hooks.slack.com/services/...", "text", false))
                                }
                            }
                        }

                        div class="space-y-4 p-4 bg-[#0F172A] rounded-xl" id="dingtalkSettings" {
                            div class="flex items-center justify-between" {
                                div class="flex items-center gap-4" {
                                    div class="w-12 h-12 bg-[#1E293B] rounded-xl flex items-center justify-center text-xl" {
                                        "🔵"
                                    }
                                    div {
                                        h4 class="font-medium text-white" { "钉钉通知" }
                                        p class="text-sm text-[#94A3B8]" { "通过钉钉机器人 Webhook 发送通知" }
                                    }
                                }
                                label class="relative inline-flex items-center cursor-pointer" {
                                    input type="checkbox" name="dingtalk_enabled" checked[settings.dingtalk_enabled] class="sr-only peer" onchange="toggleDingtalkSettings()";
                                    div class="w-11 h-6 bg-[#334155] peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:bg-[#3B82F6] after:content-[''] after:absolute after:top-0.5 after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all" {}
                                }
                            }
                            div id="dingtalkInputs" class={ @if !settings.dingtalk_enabled { "hidden" } } {
                                div class="space-y-4" {
                                    div class="space-y-1" {
                                        label class="text-sm text-[#94A3B8]" { "Webhook URL" }
                                        (input_field("dingtalk_webhook_url", &settings.dingtalk_webhook_url, "https://oapi.dingtalk.com/robot/send?access_token=...", "text", false))
                                    }
                                    div class="space-y-1" {
                                        label class="text-sm text-[#94A3B8]" { "签名密钥（可选）" }
                                        (input_field("dingtalk_sign_secret", &settings.dingtalk_sign_secret, "输入签名密钥", "text", false))
                                    }
                                }
                            }
                        }
                    }
                }, None))

                (card(Some("通知事件"), html! {
                    div class="space-y-3" {
                        div class="flex items-center justify-between p-3 hover:bg-white/5 rounded-lg transition-colors" {
                            div class="flex items-center gap-3" {
                                span class="text-[#3B82F6]" { "🔀" }
                                div {
                                    p class="text-white" { "新评审分配给我" }
                                    p class="text-sm text-[#94A3B8]" { "当有新的 MR 分配给你评审时" }
                                }
                            }
                            label class="relative inline-flex items-center cursor-pointer" {
                                input type="checkbox" name="event_new_review" checked[settings.events.new_review_assigned] class="sr-only peer";
                                div class="w-11 h-6 bg-[#334155] peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:bg-[#3B82F6] after:content-[''] after:absolute after:top-0.5 after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all" {}
                            }
                        }

                        div class="flex items-center justify-between p-3 hover:bg-white/5 rounded-lg transition-colors" {
                            div class="flex items-center gap-3" {
                                span class="text-[#10B981]" { "💬" }
                                div {
                                    p class="text-white" { "我的 MR 有新评论" }
                                    p class="text-sm text-[#94A3B8]" { "当你的 MR 收到新评论时" }
                                }
                            }
                            label class="relative inline-flex items-center cursor-pointer" {
                                input type="checkbox" name="event_mr_comment" checked[settings.events.mr_new_comment] class="sr-only peer";
                                div class="w-11 h-6 bg-[#334155] peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:bg-[#3B82F6] after:content-[''] after:absolute after:top-0.5 after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all" {}
                            }
                        }

                        div class="flex items-center justify-between p-3 hover:bg-white/5 rounded-lg transition-colors" {
                            div class="flex items-center gap-3" {
                                span class="text-[#8B5CF6]" { "@" }
                                div {
                                    p class="text-white" { "有人 @ 我" }
                                    p class="text-sm text-[#94A3B8]" { "当在评论或描述中被 @ 提及时" }
                                }
                            }
                            label class="relative inline-flex items-center cursor-pointer" {
                                input type="checkbox" name="event_mentioned" checked[settings.events.mentioned] class="sr-only peer";
                                div class="w-11 h-6 bg-[#334155] peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:bg-[#3B82F6] after:content-[''] after:absolute after:top-0.5 after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all" {}
                            }
                        }

                        div class="flex items-center justify-between p-3 hover:bg-white/5 rounded-lg transition-colors" {
                            div class="flex items-center gap-3" {
                                span class="text-[#F59E0B]" { "🐛" }
                                div {
                                    p class="text-white" { "问题分配给我" }
                                    p class="text-sm text-[#94A3B8]" { "当有新的问题分配给你时" }
                                }
                            }
                            label class="relative inline-flex items-center cursor-pointer" {
                                input type="checkbox" name="event_issue_assigned" checked[settings.events.issue_assigned] class="sr-only peer";
                                div class="w-11 h-6 bg-[#334155] peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:bg-[#3B82F6] after:content-[''] after:absolute after:top-0.5 after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all" {}
                            }
                        }

                        div class="flex items-center justify-between p-3 hover:bg-white/5 rounded-lg transition-colors" {
                            div class="flex items-center gap-3" {
                                span class="text-[#F97316]" { "🔄" }
                                div {
                                    p class="text-white" { "问题状态变更" }
                                    p class="text-sm text-[#94A3B8]" { "当你关注的问题状态改变时" }
                                }
                            }
                            label class="relative inline-flex items-center cursor-pointer" {
                                input type="checkbox" name="event_issue_status" checked[settings.events.issue_status_changed] class="sr-only peer";
                                div class="w-11 h-6 bg-[#334155] peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:bg-[#3B82F6] after:content-[''] after:absolute after:top-0.5 after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all" {}
                            }
                        }

                        div class="flex items-center justify-between p-3 hover:bg-white/5 rounded-lg transition-colors" {
                            div class="flex items-center gap-3" {
                                span class="text-[#8B5CF6]" { "📦" }
                                div {
                                    p class="text-white" { "MR 状态变更" }
                                    p class="text-sm text-[#94A3B8]" { "当你的 MR 状态改变时（通过、合并、关闭等）" }
                                }
                            }
                            label class="relative inline-flex items-center cursor-pointer" {
                                input type="checkbox" name="event_mr_status" checked[settings.events.mr_status_changed] class="sr-only peer";
                                div class="w-11 h-6 bg-[#334155] peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:bg-[#3B82F6] after:content-[''] after:absolute after:top-0.5 after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all" {}
                            }
                        }

                        div class="flex items-center justify-between p-3 hover:bg-white/5 rounded-lg transition-colors" {
                            div class="flex items-center gap-3" {
                                span class="text-[#64748B]" { "📊" }
                                div {
                                    p class="text-white" { "每日摘要" }
                                    p class="text-sm text-[#94A3B8]" { "每天发送一次活动摘要邮件" }
                                }
                            }
                            label class="relative inline-flex items-center cursor-pointer" {
                                input type="checkbox" name="event_daily_digest" checked[settings.events.daily_digest] class="sr-only peer";
                                div class="w-11 h-6 bg-[#334155] peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:bg-[#3B82F6] after:content-[''] after:absolute after:top-0.5 after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all" {}
                            }
                        }
                    }
                }, None))

                div class="flex justify-end gap-2 pt-4" {
                    a href="/notifications" {
                        (button(ButtonVariant::Secondary, "取消", None, false))
                    }
                    (button(ButtonVariant::Primary, "保存设置", None, false))
                }
            }
        }

        (modal("testEmailModal", "测试邮件已发送", html! {
            div class="space-y-4" {
                div class="text-center py-4" {
                    div class="w-16 h-16 bg-emerald-500/20 rounded-full flex items-center justify-center mx-auto mb-4" {
                        span class="text-3xl text-emerald-400" { "✓" }
                    }
                    h3 class="text-lg font-semibold text-white mb-2" { "测试邮件已发送" }
                    p class="text-[#94A3B8]" { "请检查你的邮箱收件箱。如果没有收到，请检查垃圾邮件文件夹。" }
                }
                div class="flex justify-center" {
                    (button(ButtonVariant::Primary, "好的", Some("document.getElementById('testEmailModal').classList.add('hidden')"), false))
                }
            }
        }))

        script {
            (maud::PreEscaped(r#"
                function testEmail() {
                    document.getElementById('testEmailModal').classList.remove('hidden');
                }
                function toggleEmailSettings() {
                    // 邮件通知不需要额外的输入字段
                }
                function toggleSlackSettings() {
                    const input = document.getElementById('slackWebhookInput');
                    const checkbox = document.querySelector('input[name="slack_enabled"]');
                    if (checkbox.checked) {
                        input.classList.remove('hidden');
                    } else {
                        input.classList.add('hidden');
                    }
                }
                function toggleDingtalkSettings() {
                    const inputs = document.getElementById('dingtalkInputs');
                    const checkbox = document.querySelector('input[name="dingtalk_enabled"]');
                    if (checkbox.checked) {
                        inputs.classList.remove('hidden');
                    } else {
                        inputs.classList.add('hidden');
                    }
                }
            "#))
        }
    })
}
