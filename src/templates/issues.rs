use maud::{html, Markup, PreEscaped};
use crate::models::issue::IssueWithDetails;
use crate::models::comment::CommentWithDetails;
use crate::templates::layout::LayoutContext;
use crate::templates::layout::base_layout;
use crate::templates::components::{
    stat_card, status_badge, severity_badge, user_avatar, card, table, tabs, 
    progress_bar, button, pagination, PaginationData, Severity, Status,
    select_field, input_field, ActivityItemData, activity_item,
};

pub struct IssuesPageContext {
    pub issues: Vec<IssueWithDetails>,
    pub pagination: PaginationData,
    pub current_severity: Option<String>,
    pub current_status: Option<String>,
    pub current_reporter: Option<String>,
    pub current_assignee: Option<String>,
    pub current_start_date: Option<String>,
    pub current_end_date: Option<String>,
    pub reporters: Vec<(String, String)>,
    pub assignees: Vec<(String, String)>,
}

pub struct IssueDetailPageContext {
    pub issue: IssueWithDetails,
    pub comments: Vec<CommentWithDetails>,
    pub timeline: Vec<ActivityItemData>,
    pub available_statuses: Vec<(String, String)>,
    pub assignee_options: Vec<(String, String)>,
    pub csrf_token: String,
}

fn get_severity_color(severity: &str) -> &'static str {
    match severity {
        "critical" => "#EF4444",
        "major" => "#F59E0B",
        "minor" => "#3B82F6",
        "info" => "#8B5CF6",
        _ => "#64748B",
    }
}

fn get_severity_enum(severity: &str) -> Severity {
    match severity {
        "critical" => Severity::Critical,
        "major" => Severity::High,
        "minor" => Severity::Medium,
        "info" => Severity::Info,
        _ => Severity::Low,
    }
}

fn get_status_enum(status: &str) -> Status {
    match status {
        "open" => Status::Open,
        "in_progress" => Status::ChangesRequested,
        "pending_review" => Status::Draft,
        "resolved" => Status::Approved,
        "closed" => Status::Closed,
        _ => Status::Draft,
    }
}

fn format_datetime(dt: &chrono::DateTime<chrono::Utc>) -> String {
    dt.format("%Y-%m-%d %H:%M").to_string()
}

fn code_snippet_preview(snippet: &Option<String>) -> Markup {
    html! {
        @if let Some(code) = snippet {
            div class="mt-3 bg-[#0F172A] rounded-lg overflow-hidden border border-[#334155]" {
                div class="px-3 py-2 bg-[#1E293B] border-b border-[#334155] flex items-center justify-between" {
                    span class="text-xs text-[#64748B] font-mono" { "代码预览" }
                    span class="text-xs text-[#64748B]" { "3 行" }
                }
                pre class="p-3 text-xs font-mono text-[#94A3B8] overflow-x-auto" {
                    @for (i, line) in code.lines().take(3).enumerate() {
                        div class="flex" {
                            span class="w-6 text-right pr-3 text-[#475569] select-none" { (i + 1) }
                            span class="flex-1 text-[#CBD5E1] whitespace-pre" { (line) }
                        }
                    }
                    @if code.lines().count() > 3 {
                        div class="text-[#64748B] italic mt-1" { "..." }
                    }
                }
            }
        }
    }
}

fn issue_card(issue: &IssueWithDetails) -> Markup {
    let severity_color = get_severity_color(&issue.severity);
    html! {
        a href=(format!("/issues/{}", issue.id)) class="group block bg-[#1E293B] border border-[#334155] rounded-xl overflow-hidden hover:border-[#3B82F6]/50 transition-all duration-300 hover:-translate-y-1 hover:shadow-xl hover:shadow-[#3B82F6]/10" {
            div class="flex" {
                div 
                    class="w-1 flex-shrink-0" 
                    style={"background-color: " (severity_color) ";"} 
                    title={"严重程度: " (issue.severity)}
                {}
                div class="flex-1 p-4 min-w-0" {
                    div class="flex items-start justify-between gap-3 mb-2" {
                        h3 class="font-semibold text-white truncate group-hover:text-[#3B82F6] transition-colors" {
                            (issue.title)
                        </h3>
                        (severity_badge(get_severity_enum(&issue.severity)))
                    }
                    div class="flex items-center gap-2 mb-3" {
                        span class="text-xs text-[#64748B] font-mono" { "#" (issue.id.to_string().split('-').next().unwrap_or("")) }
                        (status_badge(get_status_enum(&issue.status)))
                        @if let Some(repo) = &issue.repo_name {
                            span class="text-xs text-[#64748B]" { "📦 " (repo) }
                        }
                    }
                    (code_snippet_preview(&issue.code_snippet))
                    div class="flex items-center justify-between mt-3 pt-3 border-t border-[#334155]/50" {
                        div class="flex items-center gap-4" {
                            div class="flex items-center gap-2" {
                                (user_avatar(&issue.reporter_name, issue.reporter_avatar.as_deref(), 24))
                                span class="text-sm text-[#94A3B8]" { (issue.reporter_name) }
                            }
                            div class="flex items-center gap-2" {
                                span class="text-[#64748B] text-sm" { "→" }
                                @if let Some(assignee) = &issue.assignee_name {
                                    (user_avatar(assignee, issue.assignee_avatar.as_deref(), 24))
                                    span class="text-sm text-[#94A3B8]" { (assignee) }
                                } @else {
                                    span class="text-sm text-[#64748B] italic" { "未分配" }
                                }
                            }
                        }
                        span class="text-xs text-[#64748B]" { (format_datetime(&issue.created_at)) }
                    }
                    @if let (Some(mr_id), Some(mr_title)) = (issue.merge_request_id, &issue.merge_request_title) {
                        div class="mt-3 pt-3 border-t border-[#334155]/50" {
                            a 
                                href=(format!("/merge-requests/{}", mr_id)) 
                                class="inline-flex items-center gap-2 text-sm text-[#3B82F6] hover:text-[#60A5FA] transition-colors"
                                onclick="event.stopPropagation()"
                            {
                                "🔀"
                                span class="truncate max-w-[200px]" { (mr_title) }
                            }
                        }
                    }
                }
            }
        }
    }
}

pub fn issues_page(layout_ctx: LayoutContext, ctx: IssuesPageContext) -> Markup {
    base_layout(layout_ctx, html! {
        style { (issues_styles()) }
        
        div class="mb-6 flex items-center justify-between" {
            div {
                h1 class="text-2xl font-bold text-white mb-1" { "问题管理" }
                p class="text-[#94A3B8]" { "查看和管理代码审查中发现的所有问题" }
            }
            button 
                onclick="document.getElementById('createIssueModal').classList.remove('hidden')"
                class="px-4 py-2 bg-[#3B82F6] hover:bg-[#2563EB] text-white rounded-lg font-medium transition-colors flex items-center gap-2"
            {
                "+"
                "创建问题"
            }
        }

        div class="bg-[#1E293B] border border-[#334155] rounded-xl p-4 mb-6" {
            div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6 gap-4" {
                div {
                    label class="block text-sm text-[#94A3B8] mb-1" { "严重程度" }
                    (select_field(
                        "severity",
                        vec![
                            ("".to_string(), "全部".to_string()),
                            ("critical".to_string(), "严重".to_string()),
                            ("major".to_string(), "主要".to_string()),
                            ("minor".to_string(), "次要".to_string()),
                            ("info".to_string(), "提示".to_string()),
                        ],
                        ctx.current_severity.as_deref()
                    ))
                }
                div {
                    label class="block text-sm text-[#94A3B8] mb-1" { "状态" }
                    (select_field(
                        "status",
                        vec![
                            ("".to_string(), "全部".to_string()),
                            ("open".to_string(), "打开".to_string()),
                            ("in_progress".to_string(), "处理中".to_string()),
                            ("pending_review".to_string(), "待验证".to_string()),
                            ("resolved".to_string(), "已解决".to_string()),
                            ("closed".to_string(), "已关闭".to_string()),
                        ],
                        ctx.current_status.as_deref()
                    ))
                }
                div {
                    label class="block text-sm text-[#94A3B8] mb-1" { "报告人" }
                    (select_field("reporter_id", ctx.reporters, ctx.current_reporter.as_deref()))
                }
                div {
                    label class="block text-sm text-[#94A3B8] mb-1" { "处理人" }
                    (select_field("assignee_id", ctx.assignees, ctx.current_assignee.as_deref()))
                }
                div {
                    label class="block text-sm text-[#94A3B8] mb-1" { "开始日期" }
                    (input_field(
                        "start_date", 
                        ctx.current_start_date.as_deref().unwrap_or(""), 
                        "YYYY-MM-DD", 
                        "date", 
                        false
                    ))
                }
                div {
                    label class="block text-sm text-[#94A3B8] mb-1" { "结束日期" }
                    (input_field(
                        "end_date", 
                        ctx.current_end_date.as_deref().unwrap_or(""), 
                        "YYYY-MM-DD", 
                        "date", 
                        false
                    ))
                }
            }
            div class="flex justify-end gap-3 mt-4" {
                button type="reset" class="px-4 py-2 text-[#94A3B8] hover:text-white transition-colors" {
                    "重置"
                }
                button type="submit" class="px-4 py-2 bg-[#3B82F6] hover:bg-[#2563EB] text-white rounded-lg font-medium transition-colors" {
                    "🔍 筛选"
                }
            }
        }

        @if ctx.issues.is_empty() {
            div class="text-center py-16" {
                div class="text-6xl mb-4" { "🐛" }
                h3 class="text-xl font-semibold text-white mb-2" { "暂无问题" }
                p class="text-[#94A3B8] mb-4" { "还没有发现任何问题，继续保持！" }
                button 
                    onclick="document.getElementById('createIssueModal').classList.remove('hidden')"
                    class="px-4 py-2 bg-[#3B82F6] hover:bg-[#2563EB] text-white rounded-lg font-medium transition-colors"
                {
                    "创建第一个问题"
                }
            }
        } @else {
            div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4" {
                @for issue in &ctx.issues {
                    (issue_card(issue))
                }
            }
            (pagination(ctx.pagination))
        }

        div id="createIssueModal" class="fixed inset-0 z-50 hidden" {
            div class="absolute inset-0 bg-black/60 backdrop-blur-sm" onclick="document.getElementById('createIssueModal').classList.add('hidden')" {}
            div class="absolute inset-0 flex items-center justify-center p-4" onclick="event.stopPropagation()" {
                div class="bg-[#1E293B] border border-[#334155] rounded-2xl shadow-2xl w-full max-w-2xl max-h-[90vh] overflow-y-auto" {
                    div class="flex items-center justify-between p-6 border-b border-[#334155] sticky top-0 bg-[#1E293B] z-10" {
                        h3 class="text-lg font-semibold text-white" { "创建新问题" }
                        button onclick="document.getElementById('createIssueModal').classList.add('hidden')" class="p-2 text-[#64748B] hover:text-white hover:bg-white/10 rounded-lg transition-colors" {
                            "×"
                        }
                    }
                    form action="/issues" method="POST" class="p-6 space-y-4" {
                        input type="hidden" name="csrf_token" value=(PreEscaped(""));
                        div {
                            label class="block text-sm font-medium text-[#94A3B8] mb-1" { "标题 *" }
                            (input_field("title", "", "简要描述问题", "text", true))
                        }
                        div class="grid grid-cols-2 gap-4" {
                            div {
                                label class="block text-sm font-medium text-[#94A3B8] mb-1" { "严重程度 *" }
                                (select_field(
                                    "severity",
                                    vec![
                                        ("info".to_string(), "提示".to_string()),
                                        ("minor".to_string(), "次要".to_string()),
                                        ("major".to_string(), "主要".to_string()),
                                        ("critical".to_string(), "严重".to_string()),
                                    ],
                                    Some("minor")
                                ))
                            }
                            div {
                                label class="block text-sm font-medium text-[#94A3B8] mb-1" { "处理人" }
                                (select_field("assignee_id", ctx.assignees.clone(), None))
                            }
                        }
                        div {
                            label class="block text-sm font-medium text-[#94A3B8] mb-1" { "详细描述 *" }
                            textarea 
                                name="description" 
                                rows="6" 
                                required
                                placeholder="详细描述问题，包括复现步骤、预期行为等..."
                                class="w-full px-4 py-2.5 bg-[#0F172A] border border-[#334155] rounded-lg text-white placeholder-[#64748B] focus:outline-none focus:border-[#3B82F6] focus:ring-1 focus:ring-[#3B82F6] transition-all resize-y mono"
                            {}
                        }
                        div {
                            label class="block text-sm font-medium text-[#94A3B8] mb-1" { "代码片段" }
                            textarea 
                                name="code_snippet" 
                                rows="4" 
                                placeholder="粘贴相关代码片段..."
                                class="w-full px-4 py-2.5 bg-[#0F172A] border border-[#334155] rounded-lg text-white placeholder-[#64748B] focus:outline-none focus:border-[#3B82F6] focus:ring-1 focus:ring-[#3B82F6] transition-all resize-y mono"
                            {}
                        }
                        div class="grid grid-cols-2 gap-4" {
                            div {
                                label class="block text-sm font-medium text-[#94A3B8] mb-1" { "文件路径" }
                                (input_field("file_path", "", "src/file.rs:123", "text", false))
                            }
                            div {
                                label class="block text-sm font-medium text-[#94A3B8] mb-1" { "关联 MR" }
                                (input_field("merge_request_id", "", "MR ID (可选)", "text", false))
                            }
                        }
                        div class="flex justify-end gap-3 pt-4" {
                            button type="button" onclick="document.getElementById('createIssueModal').classList.add('hidden')" class="px-4 py-2 text-[#94A3B8] hover:text-white transition-colors" {
                                "取消"
                            }
                            button type="submit" class="px-4 py-2 bg-[#3B82F6] hover:bg-[#2563EB] text-white rounded-lg font-medium transition-colors" {
                                "创建问题"
                            }
                        }
                    </form>
                }
            }
        }
    })
}

pub fn issue_detail_page(layout_ctx: LayoutContext, ctx: IssueDetailPageContext) -> Markup {
    let severity_color = get_severity_color(&ctx.issue.severity);
    base_layout(layout_ctx, html! {
        style { (issues_styles()) }
        
        div class="flex items-center gap-2 text-sm text-[#94A3B8] mb-4" {
            a href="/issues" class="text-[#3B82F6] hover:text-[#60A5FA] transition-colors" { "问题" }
            span { "/" }
            span class="text-[#64748B] font-mono" { "#" (ctx.issue.id.to_string().split('-').next().unwrap_or("")) }
        }

        div class="grid grid-cols-1 lg:grid-cols-3 gap-6" {
            div class="lg:col-span-2 space-y-6" {
                div class="bg-[#1E293B] border border-[#334155] rounded-xl overflow-hidden" {
                    div class="flex" {
                        div class="w-2 flex-shrink-0" style={"background-color: " (severity_color) ";"} {}
                        div class="flex-1 p-6" {
                            div class="flex items-start justify-between gap-4 mb-4" {
                                div class="flex-1 min-w-0" {
                                    h1 class="text-2xl font-bold text-white mb-3" { (ctx.issue.title) }
                                    div class="flex flex-wrap items-center gap-3" {
                                        (severity_badge(get_severity_enum(&ctx.issue.severity)))
                                        (status_badge(get_status_enum(&ctx.issue.status)))
                                        @if let Some(repo) = &ctx.issue.repo_name {
                                            span class="inline-flex items-center gap-1 text-sm text-[#94A3B8]" {
                                                "📦" (repo)
                                            }
                                        }
                                        @if let (Some(file), Some(line)) = (&ctx.issue.file_path, &ctx.issue.line_no) {
                                            span class="inline-flex items-center gap-1 text-sm text-[#94A3B8] font-mono" {
                                                "📍" (file) ":" (line)
                                            }
                                        }
                                    }
                                }
                            }

                            div class="prose prose-invert max-w-none mt-6" {
                                h3 class="text-sm font-semibold text-[#94A3B8] uppercase tracking-wider mb-3" { "问题描述" }
                                div class="text-[#CBD5E1] whitespace-pre-wrap leading-relaxed" {
                                    (ctx.issue.description)
                                }
                            }

                            @if let Some(code) = &ctx.issue.code_snippet {
                                div class="mt-6" {
                                    h3 class="text-sm font-semibold text-[#94A3B8] uppercase tracking-wider mb-3" { "代码上下文" }
                                    div class="bg-[#0F172A] border border-[#334155] rounded-lg overflow-hidden" {
                                        @if let Some(file) = &ctx.issue.file_path {
                                            div class="px-4 py-2 bg-[#1E293B] border-b border-[#334155] flex items-center justify-between" {
                                                span class="text-sm text-[#94A3B8] font-mono" { (file) }
                                                @if let Some(line) = &ctx.issue.line_no {
                                                    span class="text-sm text-[#64748B]" { "行 " (line) }
                                                }
                                            }
                                        }
                                        pre class="p-4 text-sm font-mono overflow-x-auto" {
                                            @for (i, line) in code.lines().enumerate() {
                                                div class="flex hover:bg-white/5 -mx-4 px-4 transition-colors" {
                                                    span class="w-10 text-right pr-4 text-[#475569] select-none border-r border-[#334155] mr-4" {
                                                        (i + ctx.issue.line_no.unwrap_or(1) as usize)
                                                    }
                                                    span class="flex-1 text-[#E2E8F0] whitespace-pre" { (line) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                div class="bg-[#1E293B] border border-[#334155] rounded-xl p-6" {
                    h3 class="text-lg font-semibold text-white mb-4 flex items-center gap-2" {
                        "⏱️"
                        "状态流转"
                    }
                    div class="space-y-1" {
                        @for item in &ctx.timeline {
                            (activity_item(item.clone()))
                        }
                    }
                }

                div class="bg-[#1E293B] border border-[#334155] rounded-xl p-6" {
                    h3 class="text-lg font-semibold text-white mb-4 flex items-center gap-2" {
                        "💬"
                        "讨论区"
                        span class="text-sm font-normal text-[#94A3B8]" { "(" (ctx.comments.len()) ")" }
                    }

                    form action=(format!("/issues/{}/comments", ctx.issue.id)) method="POST" class="mb-6" {
                        input type="hidden" name="csrf_token" value=(ctx.csrf_token);
                        div class="bg-[#0F172A] border border-[#334155] rounded-lg p-4 focus-within:border-[#3B82F6] transition-colors" {
                            textarea 
                                name="content" 
                                rows="3" 
                                required
                                placeholder="添加评论...支持 Markdown 格式"
                                class="w-full bg-transparent text-white placeholder-[#64748B] focus:outline-none resize-none mono"
                            {}
                            div class="flex items-center justify-between mt-3 pt-3 border-t border-[#334155]/50" {
                                div class="flex gap-1" {
                                    button type="button" class="p-1.5 text-[#64748B] hover:text-white hover:bg-white/10 rounded transition-colors" title="粗体" { "B" }
                                    button type="button" class="p-1.5 text-[#64748B] hover:text-white hover:bg-white/10 rounded transition-colors" title="斜体" { "I" }
                                    button type="button" class="p-1.5 text-[#64748B] hover:text-white hover:bg-white/10 rounded transition-colors" title="代码" { "`" }
                                    button type="button" class="p-1.5 text-[#64748B] hover:text-white hover:bg-white/10 rounded transition-colors" title="链接" { "🔗" }
                                }
                                button type="submit" class="px-4 py-2 bg-[#3B82F6] hover:bg-[#2563EB] text-white rounded-lg font-medium transition-colors text-sm" {
                                    "发表评论"
                                }
                            }
                        }
                    </form>

                    @if ctx.comments.is_empty() {
                        div class="text-center py-8" {
                            div class="text-4xl mb-2" { "💭" }
                            p class="text-[#94A3B8]" { "还没有评论，来发表第一条评论吧" }
                        }
                    } @else {
                        div class="space-y-4" {
                            @for comment in &ctx.comments {
                                div class="flex gap-3" {
                                    (user_avatar(&comment.author_name, comment.author_avatar.as_deref(), 36))
                                    div class="flex-1 min-w-0" {
                                        div class="bg-[#0F172A] border border-[#334155] rounded-xl p-4" {
                                            div class="flex items-center justify-between mb-2" {
                                                div class="flex items-center gap-2" {
                                                    span class="font-medium text-white" { (comment.author_name) }
                                                    span class="text-xs text-[#64748B]" { (format_datetime(&comment.created_at)) }
                                                </div>
                                                @if comment.resolved {
                                                    span class="text-xs px-2 py-0.5 bg-emerald-500/20 text-emerald-400 rounded-full" { "已解决" }
                                                }
                                            }
                                            p class="text-[#CBD5E1] whitespace-pre-wrap" { (comment.content) }
                                            div class="flex items-center gap-4 mt-3 pt-3 border-t border-[#334155]/50" {
                                                button class="text-xs text-[#64748B] hover:text-[#3B82F6] transition-colors" { "回复" }
                                                @if !comment.resolved {
                                                    button class="text-xs text-emerald-400 hover:text-emerald-300 transition-colors" { "✓ 标记解决" }
                                                }
                                            }
                                        }
                                    </div>
                                </div>
                            }
                        }
                    }
                </div>
            </div>

            div class="space-y-6" {
                div class="bg-[#1E293B] border border-[#334155] rounded-xl p-6" {
                    h3 class="text-sm font-semibold text-[#94A3B8] uppercase tracking-wider mb-4" { "基本信息" }
                    div class="space-y-4" {
                        div class="flex items-center gap-3" {
                            span class="text-[#64748B] w-20 flex-shrink-0" { "报告人" }
                            div class="flex items-center gap-2" {
                                (user_avatar(&ctx.issue.reporter_name, ctx.issue.reporter_avatar.as_deref(), 28))
                                span class="text-white" { (ctx.issue.reporter_name) }
                            }
                        }
                        div class="flex items-center gap-3" {
                            span class="text-[#64748B] w-20 flex-shrink-0" { "处理人" }
                            div class="flex items-center gap-2" {
                                @if let Some(assignee) = &ctx.issue.assignee_name {
                                    (user_avatar(assignee, ctx.issue.assignee_avatar.as_deref(), 28))
                                    span class="text-white" { (assignee) }
                                } @else {
                                    span class="text-[#64748B] italic" { "未分配" }
                                }
                                button 
                                    onclick="document.getElementById('assignModal').classList.remove('hidden')"
                                    class="ml-auto text-xs text-[#3B82F6] hover:text-[#60A5FA] transition-colors"
                                {
                                    "分配"
                                }
                            }
                        }
                        div class="flex items-center gap-3" {
                            span class="text-[#64748B] w-20 flex-shrink-0" { "创建时间" }
                            span class="text-white text-sm" { (format_datetime(&ctx.issue.created_at)) }
                        }
                        div class="flex items-center gap-3" {
                            span class="text-[#64748B] w-20 flex-shrink-0" { "更新时间" }
                            span class="text-white text-sm" { (format_datetime(&ctx.issue.updated_at)) }
                        }
                    }
                </div>

                @if let (Some(mr_id), Some(mr_title)) = (ctx.issue.merge_request_id, &ctx.issue.merge_request_title) {
                    div class="bg-[#1E293B] border border-[#334155] rounded-xl p-6" {
                        h3 class="text-sm font-semibold text-[#94A3B8] uppercase tracking-wider mb-4" { "关联 MR/PR" }
                        a href=(format!("/merge-requests/{}", mr_id)) class="flex items-center gap-3 p-3 bg-[#0F172A] rounded-lg hover:bg-[#334155]/50 transition-colors" {
                            span class="text-[#3B82F6] text-xl" { "🔀" }
                            div class="flex-1 min-w-0" {
                                span class="text-white truncate block" { (mr_title) }
                                span class="text-xs text-[#64748B]" { "!" (mr_id.to_string().split('-').next().unwrap_or("")) }
                            }
                        }
                    }
                }

                @if ctx.issue.file_path.is_some() {
                    div class="bg-[#1E293B] border border-[#334155] rounded-xl p-6" {
                        h3 class="text-sm font-semibold text-[#94A3B8] uppercase tracking-wider mb-4" { "关联文件" }
                        div class="space-y-2" {
                            div class="flex items-center gap-3 p-3 bg-[#0F172A] rounded-lg" {
                                span class="text-[#F59E0B] text-lg" { "📄" }
                                div class="flex-1 min-w-0" {
                                    span class="text-white text-sm font-mono truncate block" { (ctx.issue.file_path.as_deref().unwrap_or("")) }
                                    @if let Some(line) = ctx.issue.line_no {
                                        span class="text-xs text-[#64748B]" { "行 " (line) }
                                    }
                                }
                            }
                        }
                    }
                }

                div class="bg-[#1E293B] border border-[#334155] rounded-xl p-6" {
                    h3 class="text-sm font-semibold text-[#94A3B8] uppercase tracking-wider mb-4" { "操作" }
                    div class="space-y-3" {
                        form action=(format!("/issues/{}/status", ctx.issue.id)) method="POST" class="space-y-3" {
                            input type="hidden" name="csrf_token" value=(ctx.csrf_token);
                            div {
                                label class="block text-sm text-[#94A3B8] mb-1" { "变更状态" }
                                (select_field("status", ctx.available_statuses.clone(), Some(ctx.issue.status.as_str())))
                            }
                            button type="submit" class="w-full px-4 py-2 bg-[#3B82F6] hover:bg-[#2563EB] text-white rounded-lg font-medium transition-colors text-sm" {
                                "更新状态"
                            }
                        }
                        div class="grid grid-cols-2 gap-2" {
                            button 
                                onclick="document.getElementById('editIssueModal').classList.remove('hidden')"
                                class="px-4 py-2 bg-[#334155] hover:bg-[#475569] text-white rounded-lg font-medium transition-colors text-sm"
                            {
                                "✏️ 编辑"
                            }
                            form action=(format!("/issues/{}/delete", ctx.issue.id)) method="POST" onsubmit="return confirm('确定要删除这个问题吗？');" {
                                input type="hidden" name="csrf_token" value=(ctx.csrf_token);
                                button type="submit" class="w-full px-4 py-2 bg-red-500/20 hover:bg-red-500/30 text-red-400 rounded-lg font-medium transition-colors text-sm" {
                                    "🗑️ 删除"
                                }
                            }
                        }
                    }
                </div>
            </div>
        }

        div id="assignModal" class="fixed inset-0 z-50 hidden" {
            div class="absolute inset-0 bg-black/60 backdrop-blur-sm" onclick="document.getElementById('assignModal').classList.add('hidden')" {}
            div class="absolute inset-0 flex items-center justify-center p-4" onclick="event.stopPropagation()" {
                div class="bg-[#1E293B] border border-[#334155] rounded-2xl shadow-2xl w-full max-w-md" {
                    div class="flex items-center justify-between p-6 border-b border-[#334155]" {
                        h3 class="text-lg font-semibold text-white" { "分配处理人" }
                        button onclick="document.getElementById('assignModal').classList.add('hidden')" class="p-2 text-[#64748B] hover:text-white hover:bg-white/10 rounded-lg transition-colors" {
                            "×"
                        }
                    }
                    form action=(format!("/issues/{}/assign", ctx.issue.id)) method="POST" class="p-6" {
                        input type="hidden" name="csrf_token" value=(ctx.csrf_token);
                        div class="mb-4" {
                            label class="block text-sm font-medium text-[#94A3B8] mb-2" { "选择处理人" }
                            (select_field("assignee_id", ctx.assignee_options.clone(), ctx.issue.assignee_id.map(|id| id.to_string()).as_deref()))
                        }
                        div class="flex justify-end gap-3" {
                            button type="button" onclick="document.getElementById('assignModal').classList.add('hidden')" class="px-4 py-2 text-[#94A3B8] hover:text-white transition-colors" {
                                "取消"
                            }
                            button type="submit" class="px-4 py-2 bg-[#3B82F6] hover:bg-[#2563EB] text-white rounded-lg font-medium transition-colors" {
                                "确认分配"
                            }
                        }
                    }
                </div>
            }
        }

        div id="editIssueModal" class="fixed inset-0 z-50 hidden" {
            div class="absolute inset-0 bg-black/60 backdrop-blur-sm" onclick="document.getElementById('editIssueModal').classList.add('hidden')" {}
            div class="absolute inset-0 flex items-center justify-center p-4" onclick="event.stopPropagation()" {
                div class="bg-[#1E293B] border border-[#334155] rounded-2xl shadow-2xl w-full max-w-2xl max-h-[90vh] overflow-y-auto" {
                    div class="flex items-center justify-between p-6 border-b border-[#334155] sticky top-0 bg-[#1E293B] z-10" {
                        h3 class="text-lg font-semibold text-white" { "编辑问题" }
                        button onclick="document.getElementById('editIssueModal').classList.add('hidden')" class="p-2 text-[#64748B] hover:text-white hover:bg-white/10 rounded-lg transition-colors" {
                            "×"
                        }
                    }
                    form action=(format!("/issues/{}", ctx.issue.id)) method="POST" class="p-6 space-y-4" {
                        input type="hidden" name="csrf_token" value=(ctx.csrf_token);
                        input type="hidden" name="_method" value="PUT";
                        div {
                            label class="block text-sm font-medium text-[#94A3B8] mb-1" { "标题 *" }
                            (input_field("title", &ctx.issue.title, "简要描述问题", "text", true))
                        }
                        div class="grid grid-cols-2 gap-4" {
                            div {
                                label class="block text-sm font-medium text-[#94A3B8] mb-1" { "严重程度 *" }
                                (select_field(
                                    "severity",
                                    vec![
                                        ("info".to_string(), "提示".to_string()),
                                        ("minor".to_string(), "次要".to_string()),
                                        ("major".to_string(), "主要".to_string()),
                                        ("critical".to_string(), "严重".to_string()),
                                    ],
                                    Some(&ctx.issue.severity)
                                ))
                            }
                            div {
                                label class="block text-sm font-medium text-[#94A3B8] mb-1" { "处理人" }
                                (select_field("assignee_id", ctx.assignee_options.clone(), ctx.issue.assignee_id.map(|id| id.to_string()).as_deref()))
                            }
                        }
                        div {
                            label class="block text-sm font-medium text-[#94A3B8] mb-1" { "详细描述 *" }
                            textarea 
                                name="description" 
                                rows="6" 
                                required
                                class="w-full px-4 py-2.5 bg-[#0F172A] border border-[#334155] rounded-lg text-white placeholder-[#64748B] focus:outline-none focus:border-[#3B82F6] focus:ring-1 focus:ring-[#3B82F6] transition-all resize-y mono"
                            { (ctx.issue.description) }
                        </div>
                        div {
                            label class="block text-sm font-medium text-[#94A3B8] mb-1" { "代码片段" }
                            textarea 
                                name="code_snippet" 
                                rows="4" 
                                class="w-full px-4 py-2.5 bg-[#0F172A] border border-[#334155] rounded-lg text-white placeholder-[#64748B] focus:outline-none focus:border-[#3B82F6] focus:ring-1 focus:ring-[#3B82F6] transition-all resize-y mono"
                            { (ctx.issue.code_snippet.as_deref().unwrap_or("")) }
                        }
                        div class="flex justify-end gap-3 pt-4" {
                            button type="button" onclick="document.getElementById('editIssueModal').classList.add('hidden')" class="px-4 py-2 text-[#94A3B8] hover:text-white transition-colors" {
                                "取消"
                            }
                            button type="submit" class="px-4 py-2 bg-[#3B82F6] hover:bg-[#2563EB] text-white rounded-lg font-medium transition-colors" {
                                "保存修改"
                            }
                        }
                    </form>
                </div>
            }
        }
    })
}

fn issues_styles() -> &'static str {
    r#"
    .issue-card:hover .severity-bar {
        opacity: 1;
    }
    
    .code-line:hover {
        background: rgba(59, 130, 246, 0.05);
    }
    
    .timeline-item::before {
        content: '';
        position: absolute;
        left: 20px;
        top: 40px;
        bottom: -16px;
        width: 2px;
        background: #334155;
    }
    
    .timeline-item:last-child::before {
        display: none;
    }
    
    select, input, textarea {
        color-scheme: dark;
    }
    
    input[type="date"]::-webkit-calendar-picker-indicator {
        filter: invert(1);
        cursor: pointer;
    }
    
    .prose h3 {
        color: #94A3B8 !important;
        font-size: 0.875rem !important;
        font-weight: 600 !important;
        text-transform: uppercase !important;
        letter-spacing: 0.05em !important;
        margin-top: 0 !important;
        margin-bottom: 0.75rem !important;
    }
    
    .prose p {
        color: #CBD5E1 !important;
        line-height: 1.75 !important;
    }
    
    .modal-enter {
        animation: fadeIn 0.2s ease-out;
    }
    
    @keyframes fadeIn {
        from { opacity: 0; transform: scale(0.95); }
        to { opacity: 1; transform: scale(1); }
    }
    "#
}
