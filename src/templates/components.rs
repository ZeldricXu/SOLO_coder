use maud::{html, Markup, PreEscaped};
use uuid::Uuid;
use crate::models::AttachmentWithDetails;

pub enum Status {
    Open,
    Approved,
    ChangesRequested,
    Merged,
    Closed,
    Draft,
}

pub enum Severity {
    Critical,
    High,
    Medium,
    Low,
    Info,
}

pub enum Role {
    Owner,
    Maintainer,
    Reviewer,
    Developer,
}

pub enum ButtonVariant {
    Primary,
    Secondary,
    Danger,
    Ghost,
}

pub enum DiffType {
    Added,
    Removed,
    Context,
}

pub struct StatCard {
    pub title: String,
    pub value: String,
    pub trend: Option<(bool, String)>,
    pub gradient_from: String,
    pub gradient_to: String,
    pub icon: String,
}

pub struct ChecklistItemData {
    pub id: String,
    pub label: String,
    pub checked: bool,
    pub description: Option<String>,
    pub reviewer: Option<String>,
}

pub struct ActivityItemData {
    pub icon: String,
    pub icon_color: String,
    pub title: String,
    pub description: Option<String>,
    pub time: String,
}

pub struct PaginationData {
    pub current_page: u32,
    pub total_pages: u32,
    pub base_url: String,
}

pub struct TabData {
    pub id: String,
    pub label: String,
    pub active: bool,
}

pub struct ProgressBarData {
    pub value: u32,
    pub max: u32,
    pub label: Option<String>,
    pub color: Option<String>,
}

pub fn stat_card(card: StatCard) -> Markup {
    html! {
        div class="group relative overflow-hidden bg-[#1E293B] border border-[#334155] rounded-xl p-6 hover:border-[#3B82F6]/50 transition-all duration-300 hover:-translate-y-1 hover:shadow-xl hover:shadow-[#3B82F6]/10" {
            div class={
                "absolute -right-10 -top-10 w-32 h-32 rounded-full opacity-20 transition-transform duration-500 group-hover:scale-110"
                " bg-gradient-to-br from-[" (card.gradient_from) "] to-[" (card.gradient_to) "]"
            } {}
            div class="relative" {
                div class="flex items-start justify-between mb-4" {
                    div class={
                        "w-12 h-12 rounded-xl flex items-center justify-center text-2xl"
                        " bg-gradient-to-br from-[" (card.gradient_from) "] to-[" (card.gradient_to) "]"
                    } {
                        (card.icon)
                    }
                    @if let Some((is_positive, trend_text)) = &card.trend {
                        span class={
                            "flex items-center gap-1 text-sm px-2 py-1 rounded-lg"
                            @if *is_positive { "text-emerald-400 bg-emerald-500/20" }
                            @else { "text-red-400 bg-red-500/20" }
                        } {
                            @if *is_positive { "↑" } @else { "↓" }
                            (trend_text)
                        }
                    }
                }
                div class="text-3xl font-bold text-white mb-1" { (card.value) }
                div class="text-[#94A3B8] text-sm" { (card.title) }
            }
        }
    }
}

pub fn status_badge(status: Status) -> Markup {
    let (label, class) = match status {
        Status::Open => ("打开", "bg-blue-500/20 text-blue-400 border-blue-500/30"),
        Status::Approved => ("已批准", "bg-emerald-500/20 text-emerald-400 border-emerald-500/30"),
        Status::ChangesRequested => ("需要修改", "bg-amber-500/20 text-amber-400 border-amber-500/30"),
        Status::Merged => ("已合并", "bg-purple-500/20 text-purple-400 border-purple-500/30"),
        Status::Closed => ("已关闭", "bg-red-500/20 text-red-400 border-red-500/30"),
        Status::Draft => ("草稿", "bg-gray-500/20 text-gray-400 border-gray-500/30"),
    };
    html! {
        span class={"inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium border " (class)} {
            (label)
        }
    }
}

pub fn severity_badge(severity: Severity) -> Markup {
    let (label, class) = match severity {
        Severity::Critical => ("严重", "bg-red-500/20 text-red-400 border-red-500/30"),
        Severity::High => ("高", "bg-orange-500/20 text-orange-400 border-orange-500/30"),
        Severity::Medium => ("中", "bg-amber-500/20 text-amber-400 border-amber-500/30"),
        Severity::Low => ("低", "bg-blue-500/20 text-blue-400 border-blue-500/30"),
        Severity::Info => ("提示", "bg-gray-500/20 text-gray-400 border-gray-500/30"),
    };
    html! {
        span class={"inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium border " (class)} {
            (label)
        }
    }
}

pub fn role_badge(role: Role) -> Markup {
    let (label, class) = match role {
        Role::Owner => ("Owner", "bg-purple-500/20 text-purple-400 border-purple-500/30"),
        Role::Maintainer => ("Maintainer", "bg-blue-500/20 text-blue-400 border-blue-500/30"),
        Role::Reviewer => ("Reviewer", "bg-emerald-500/20 text-emerald-400 border-emerald-500/30"),
        Role::Developer => ("Developer", "bg-gray-500/20 text-gray-400 border-gray-500/30"),
    };
    html! {
        span class={"inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium border " (class)} {
            (label)
        }
    }
}

pub fn user_avatar(name: &str, avatar_url: Option<&str>, size: u32) -> Markup {
    let initial = name.chars().next().unwrap_or('U').to_ascii_uppercase();
    let size_class = match size {
        s if s <= 24 => "w-6 h-6 text-xs",
        s if s <= 32 => "w-8 h-8 text-sm",
        s if s <= 40 => "w-10 h-10 text-base",
        _ => "w-12 h-12 text-lg",
    };
    html! {
        div class={
            (size_class) " rounded-full bg-gradient-to-br from-[#3B82F6] to-[#8B5CF6] flex items-center justify-center text-white font-semibold overflow-hidden flex-shrink-0"
        } {
            @if let Some(url) = avatar_url {
                img src=(url) alt=(name) class="w-full h-full object-cover";
            } @else {
                (initial)
            }
        }
    }
}

pub fn comment_bubble(
    author: &str,
    avatar_url: Option<&str>,
    content: &str,
    time: &str,
    is_resolved: bool,
    indent_level: u32,
) -> Markup {
    let margin_left = indent_level * 32;
    html! {
        div style={"margin-left: " (margin_left) "px"} class="flex gap-3 my-3" {
            (user_avatar(author, avatar_url, 32))
            div class="flex-1 min-w-0" {
                div class="bg-[#1E293B] border border-[#334155] rounded-xl p-4" {
                    div class="flex items-center justify-between mb-2" {
                        div class="flex items-center gap-2" {
                            span class="font-medium text-white" { (author) }
                            @if is_resolved {
                                span class="text-xs px-2 py-0.5 bg-emerald-500/20 text-emerald-400 rounded-full" { "已解决" }
                            }
                        }
                        span class="text-xs text-[#64748B]" { (time) }
                    }
                    p class="text-[#CBD5E1] whitespace-pre-wrap" { (content) }
                    div class="flex items-center gap-4 mt-3 pt-3 border-t border-[#334155]/50" {
                        button class="text-xs text-[#64748B] hover:text-[#3B82F6] transition-colors" { "回复" }
                        @if !is_resolved {
                            button class="text-xs text-emerald-400 hover:text-emerald-300 transition-colors" { "✓ 标记解决" }
                        }
                        button class="text-xs text-[#64748B] hover:text-[#EF4444] transition-colors" { "···" }
                    }
                }
            }
        }
    }
}

pub fn diff_line(diff_type: DiffType, line_number_old: Option<u32>, line_number_new: Option<u32>, content: &str) -> Markup {
    let (bg_class, prefix, line_class) = match diff_type {
        DiffType::Added => ("bg-emerald-500/10 hover:bg-emerald-500/20", "+", "border-l-2 border-emerald-500"),
        DiffType::Removed => ("bg-red-500/10 hover:bg-red-500/20", "-", "border-l-2 border-red-500"),
        DiffType::Context => ("hover:bg-white/5", " ", ""),
    };
    html! {
        div class={
            "group flex font-mono text-sm transition-colors"
            " " (bg_class) " " (line_class)
        } {
            span class="w-14 py-1 px-2 text-right text-[#64748B] select-none bg-[#0F172A]/50 border-r border-[#334155]/50" {
                @if let Some(n) = line_number_old { (n) }
            }
            span class="w-14 py-1 px-2 text-right text-[#64748B] select-none bg-[#0F172A]/50 border-r border-[#334155]/50" {
                @if let Some(n) = line_number_new { (n) }
            }
            span class="w-8 py-1 px-2 text-center text-[#64748B] select-none" {
                (prefix)
            }
            pre class="flex-1 py-1 px-2 text-[#E2E8F0] whitespace-pre overflow-x-auto" {
                (content)
            }
            span class="opacity-0 group-hover:opacity-100 px-2 flex items-center gap-1 transition-opacity" {
                button class="p-1 hover:bg-white/10 rounded text-[#64748B] hover:text-[#3B82F6]" title="添加评论" { "💬" }
            }
        }
    }
}

pub fn checklist_item(item: ChecklistItemData) -> Markup {
    html! {
        div class={
            "group flex items-start gap-4 p-4 bg-[#1E293B] border rounded-xl transition-all duration-300"
            @if item.checked { "border-emerald-500/30 bg-emerald-500/5" }
            @else { "border-[#334155] hover:border-[#3B82F6]/50" }
        } {
            label class="relative flex items-center cursor-pointer mt-0.5" {
                input type="checkbox" class="sr-only peer" checked[item.checked];
                div class={
                    "w-6 h-6 border-2 rounded-lg flex items-center justify-center transition-all duration-300"
                    @if item.checked { "bg-emerald-500 border-emerald-500" }
                    @else { "border-[#475569] peer-hover:border-[#3B82F6]" }
                } {
                    @if item.checked {
                        span class="text-white text-sm animate-pulse" { "✓" }
                    }
                }
            }
            div class="flex-1 min-w-0" {
                div class="flex items-center gap-3" {
                    label class={
                        "font-medium transition-colors cursor-pointer"
                        @if item.checked { "text-[#64748B] line-through" }
                        @else { "text-white" }
                    } {
                        (item.label)
                    }
                    @if let Some(reviewer) = &item.reviewer {
                        span class="flex items-center gap-1 text-xs text-[#94A3B8]" {
                            (user_avatar(reviewer, None, 16))
                            (reviewer)
                        }
                    }
                }
                @if let Some(desc) = &item.description {
                    p class="mt-1 text-sm text-[#94A3B8]" { (desc) }
                }
            }
        }
    }
}

pub fn activity_item(item: ActivityItemData) -> Markup {
    html! {
        div class="flex gap-4 group" {
            div class="relative flex flex-col items-center" {
                div class={
                    "w-10 h-10 rounded-full flex items-center justify-center text-lg flex-shrink-0 border-2 border-[#1E293B]"
                    " bg-[" (item.icon_color) "]/20 text-[" (item.icon_color) "]"
                } {
                    (item.icon)
                }
                div class="w-0.5 flex-1 bg-[#334155] group-last:hidden" {}
            }
            div class="flex-1 pb-6" {
                div class="bg-[#1E293B] border border-[#334155] rounded-xl p-4 group-hover:border-[#3B82F6]/30 transition-colors" {
                    div class="flex items-start justify-between mb-1" {
                        p class="text-white font-medium" { (item.title) }
                        span class="text-xs text-[#64748B] flex-shrink-0 ml-4" { (item.time) }
                    }
                    @if let Some(desc) = &item.description {
                        p class="text-sm text-[#94A3B8]" { (desc) }
                    }
                }
            }
        }
    }
}

pub fn pagination(pagination: PaginationData) -> Markup {
    html! {
        div class="flex items-center justify-between mt-6" {
            div class="text-sm text-[#94A3B8]" {
                "第 " (pagination.current_page) " / " (pagination.total_pages) " 页"
            }
            div class="flex items-center gap-1" {
                @if pagination.current_page > 1 {
                    a href=(format!("{}?page=1", pagination.base_url)) class="px-3 py-2 rounded-lg text-[#94A3B8] hover:bg-white/5 hover:text-white transition-colors" {
                        "«"
                    }
                    a href=(format!("{}?page={}", pagination.base_url, pagination.current_page - 1)) class="px-3 py-2 rounded-lg text-[#94A3B8] hover:bg-white/5 hover:text-white transition-colors" {
                        "‹"
                    }
                }
                @let start_page = if pagination.current_page > 3 { pagination.current_page - 2 } else { 1 };
                @let end_page = if pagination.current_page + 2 < pagination.total_pages { pagination.current_page + 2 } else { pagination.total_pages };
                @for page in start_page..=end_page {
                    a href=(format!("{}?page={}", pagination.base_url, page)) class={
                        "px-3 py-2 rounded-lg transition-colors"
                        @if page == pagination.current_page { "bg-[#3B82F6] text-white" }
                        @else { "text-[#94A3B8] hover:bg-white/5 hover:text-white" }
                    } {
                        (page)
                    }
                }
                @if pagination.current_page < pagination.total_pages {
                    a href=(format!("{}?page={}", pagination.base_url, pagination.current_page + 1)) class="px-3 py-2 rounded-lg text-[#94A3B8] hover:bg-white/5 hover:text-white transition-colors" {
                        "›"
                    }
                    a href=(format!("{}?page={}", pagination.base_url, pagination.total_pages)) class="px-3 py-2 rounded-lg text-[#94A3B8] hover:bg-white/5 hover:text-white transition-colors" {
                        "»"
                    }
                }
            }
        }
    }
}

pub fn modal(id: &str, title: &str, content: Markup) -> Markup {
    html! {
        div id=(id) class="fixed inset-0 z-50 hidden" {
            div class="absolute inset-0 bg-black/60 backdrop-blur-sm" onclick={"document.getElementById('" (id) "').classList.add('hidden')"} {}
            div class="absolute inset-0 flex items-center justify-center p-4" onclick={"event.stopPropagation()"} {
                div class="bg-[#1E293B] border border-[#334155] rounded-2xl shadow-2xl w-full max-w-lg animate-[fadeIn_0.2s_ease-out]" {
                    div class="flex items-center justify-between p-6 border-b border-[#334155]" {
                        h3 class="text-lg font-semibold text-white" { (title) }
                        button onclick={"document.getElementById('" (id) "').classList.add('hidden')"} class="p-2 text-[#64748B] hover:text-white hover:bg-white/10 rounded-lg transition-colors" {
                            "×"
                        }
                    }
                    div class="p-6" {
                        (content)
                    }
                }
            }
        }
    }
}

pub fn button(variant: ButtonVariant, content: &str, onclick: Option<&str>, disabled: bool) -> Markup {
    let base_class = "px-4 py-2 rounded-lg font-medium transition-all duration-200 flex items-center justify-center gap-2";
    let variant_class = match variant {
        ButtonVariant::Primary => "bg-[#3B82F6] hover:bg-[#2563EB] text-white disabled:bg-[#3B82F6]/50",
        ButtonVariant::Secondary => "bg-[#334155] hover:bg-[#475569] text-white disabled:bg-[#334155]/50",
        ButtonVariant::Danger => "bg-red-500 hover:bg-red-600 text-white disabled:bg-red-500/50",
        ButtonVariant::Ghost => "bg-transparent hover:bg-white/10 text-[#94A3B8] hover:text-white disabled:text-[#64748B]",
    };
    html! {
        button
            type="button"
            disabled[disabled]
            onclick=[onclick]
            class={(base_class) " " (variant_class) " disabled:cursor-not-allowed disabled:opacity-50"}
        {
            (content)
        }
    }
}

pub fn input_field(name: &str, value: &str, placeholder: &str, input_type: &str, required: bool) -> Markup {
    html! {
        div class="space-y-1" {
            input
                type=(input_type)
                name=(name)
                value=(value)
                placeholder=(placeholder)
                required[required]
                class="w-full px-4 py-2.5 bg-[#1E293B] border border-[#334155] rounded-lg text-white placeholder-[#64748B] focus:outline-none focus:border-[#3B82F6] focus:ring-1 focus:ring-[#3B82F6] transition-all"
            ;
        }
    }
}

pub fn select_field(name: &str, options: Vec<(String, String)>, selected: Option<&str>) -> Markup {
    html! {
        select name=(name) class="w-full px-4 py-2.5 bg-[#1E293B] border border-[#334155] rounded-lg text-white focus:outline-none focus:border-[#3B82F6] focus:ring-1 focus:ring-[#3B82F6] transition-all appearance-none cursor-pointer" {
            @for (value, label) in options {
                option value=(value) selected[selected == Some(&value)] {
                    (label)
                }
            }
        }
    }
}

pub fn textarea_field(name: &str, value: &str, placeholder: &str, rows: u32, required: bool) -> Markup {
    html! {
        textarea
            name=(name)
            placeholder=(placeholder)
            rows=(rows)
            required[required]
            class="w-full px-4 py-2.5 bg-[#1E293B] border border-[#334155] rounded-lg text-white placeholder-[#64748B] focus:outline-none focus:border-[#3B82F6] focus:ring-1 focus:ring-[#3B82F6] transition-all resize-y mono"
        {
            (value)
        }
    }
}

pub fn card(title: Option<&str>, content: Markup, extra: Option<Markup>) -> Markup {
    html! {
        div class="bg-[#1E293B] border border-[#334155] rounded-xl overflow-hidden" {
            @if title.is_some() || extra.is_some() {
                div class="flex items-center justify-between p-4 border-b border-[#334155]" {
                    @if let Some(t) = title {
                        h3 class="font-semibold text-white" { (t) }
                    }
                    @if let Some(e) = extra {
                        (e)
                    }
                }
            }
            div class="p-4" {
                (content)
            }
        }
    }
}

pub fn table(headers: Vec<String>, rows: Vec<Vec<Markup>>) -> Markup {
    html! {
        div class="overflow-x-auto" {
            table class="w-full" {
                thead class="bg-[#1E293B]/50" {
                    tr {
                        @for header in headers {
                            th class="px-4 py-3 text-left text-xs font-medium text-[#94A3B8] uppercase tracking-wider border-b border-[#334155]" {
                                (header)
                            }
                        }
                    }
                }
                tbody class="divide-y divide-[#334155]/50" {
                    @for row in rows {
                        tr class="hover:bg-white/5 transition-colors" {
                            @for cell in row {
                                td class="px-4 py-3 text-sm text-[#CBD5E1] whitespace-nowrap" {
                                    (cell)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

pub fn tabs(tabs: Vec<TabData>) -> Markup {
    html! {
        div class="flex gap-1 p-1 bg-[#1E293B] rounded-lg border border-[#334155]" {
            @for tab in tabs {
                button
                    onclick={"switchTab('" (tab.id) "')"}
                    class={
                        "px-4 py-2 rounded-md text-sm font-medium transition-all duration-200 flex-1"
                        @if tab.active { "bg-[#3B82F6] text-white shadow-lg shadow-[#3B82F6]/20" }
                        @else { "text-[#94A3B8] hover:text-white hover:bg-white/5" }
                    }
                {
                    (tab.label)
                }
            }
        }
    }
}

pub fn progress_bar(progress: ProgressBarData) -> Markup {
    let percentage = if progress.max > 0 { (progress.value * 100) / progress.max } else { 0 };
    let color = progress.color.unwrap_or_else(|| "#3B82F6".to_string());
    html! {
        div class="space-y-2" {
            @if let Some(label) = &progress.label {
                div class="flex justify-between text-sm" {
                    span class="text-[#94A3B8]" { (label) }
                    span class="text-white font-medium" { (progress.value) " / " (progress.max) }
                }
            }
            div class="h-2 bg-[#1E293B] rounded-full overflow-hidden" {
                div
                    class="h-full rounded-full transition-all duration-500 ease-out"
                    style={
                        "width: " (percentage) "%; "
                        "background: linear-gradient(90deg, " (color) ", " (color) "CC)"
                    }
                {}
            }
            @if progress.label.is_none() {
                div class="text-right text-xs text-[#64748B]" {
                    (percentage) "%"
                }
            }
        }
    }
}

pub fn attachment_gallery(attachments: &[AttachmentWithDetails]) -> Markup {
    if attachments.is_empty() {
        return html! {
            div class="flex flex-col items-center justify-center py-8 text-[#64748B]" {
                div class="text-4xl mb-2" { "📎" }
                span class="text-sm" { "暂无附件" }
            }
        };
    }

    html! {
        div class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-3" {
            @for attachment in attachments {
                div class="relative group rounded-lg overflow-hidden border border-[#334155] bg-[#1E293B]" {
                    @if attachment.content_type.starts_with("image/") {
                        div class="aspect-square bg-[#0F172A] flex items-center justify-center cursor-pointer"
                            onclick={"openAttachmentPreview('" (attachment.file_url) "', '" (attachment.file_name) "')"}
                        {
                            img
                                src=(attachment.thumbnail_url.as_ref().unwrap_or(&attachment.file_url))
                                alt=(attachment.file_name)
                                class="w-full h-full object-cover"
                            ;
                        }
                    } @else {
                        div class="aspect-square bg-[#0F172A] flex flex-col items-center justify-center cursor-pointer hover:bg-[#334155]/50 transition-colors"
                            onclick={"window.open('" (attachment.file_url) "', '_blank')"}
                        {
                            div class="text-3xl mb-2" { "📄" }
                            span class="text-xs text-[#94A3B8] px-2 text-center truncate w-full" title=(attachment.file_name) {
                                (attachment.file_name)
                            }
                        }
                    }
                    div class="absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity" {
                        button
                            class="w-7 h-7 bg-red-500 hover:bg-red-600 text-white rounded-full flex items-center justify-center text-sm shadow-lg"
                            onclick={"deleteAttachment('" (attachment.id) "')"}
                            title="删除附件"
                        {
                            "×"
                        }
                    }
                    div class="p-2 border-t border-[#334155]" {
                        div class="text-xs text-[#CBD5E1] truncate" title=(attachment.file_name) {
                            (attachment.file_name)
                        }
                        div class="text-xs text-[#64748B] mt-0.5" {
                            (format_file_size(attachment.file_size_bytes))
                        }
                    }
                }
            }
        }

        div id="attachmentPreviewModal" class="fixed inset-0 z-50 hidden items-center justify-center" {
            div class="absolute inset-0 bg-black/80 backdrop-blur-sm" onclick="closeAttachmentPreview()" {}
            div class="relative z-10 max-w-[90vw] max-h-[90vh]" onclick="event.stopPropagation()" {
                button
                    class="absolute -top-12 right-0 w-10 h-10 bg-white/10 hover:bg-white/20 text-white rounded-full flex items-center justify-center text-xl transition-colors"
                    onclick="closeAttachmentPreview()"
                {
                    "×"
                }
                img id="attachmentPreviewImage" src="" alt="" class="max-w-full max-h-[90vh] rounded-lg shadow-2xl";
            }
        }
    }
}

pub fn attachment_upload_button(target_type: &str, target_id: Uuid) -> Markup {
    html! {
        div class="attachment-upload-area" {
            div
                id="dropZone"
                class="border-2 border-dashed border-[#334155] rounded-xl p-8 text-center hover:border-[#3B82F6]/50 hover:bg-[#3B82F6]/5 transition-all cursor-pointer"
                onclick={"document.getElementById('fileInput').click()"}
                ondragover="event.preventDefault(); this.classList.add('border-[#3B82F6]', 'bg-[#3B82F6]/10');"
                ondragleave="this.classList.remove('border-[#3B82F6]', 'bg-[#3B82F6]/10');"
                ondrop={"handleFileDrop(event, '" (target_type) "', '" (target_id) "')"}
            {
                div class="text-4xl mb-3" { "📤" }
                p class="text-[#CBD5E1] font-medium mb-1" { "点击或拖拽上传附件" }
                p class="text-sm text-[#64748B]" { "支持图片、文档等文件" }
            }

            input
                type="file"
                id="fileInput"
                class="hidden"
                multiple
                onchange={"handleFileSelect(event, '" (target_type) "', '" (target_id) "')"}
            ;

            div id="uploadProgressContainer" class="hidden mt-4 space-y-2" {}
        }
    }
}

pub fn health_score_bar(score: f64) -> Markup {
    let score_pct = (score * 100.0).max(0.0).min(100.0);

    let color_start = if score_pct >= 70.0 {
        "#10B981"
    } else if score_pct >= 40.0 {
        "#F59E0B"
    } else {
        "#EF4444"
    };

    let color_end = if score_pct >= 70.0 {
        "#34D399"
    } else if score_pct >= 40.0 {
        "#FBBF24"
    } else {
        "#F87171"
    };

    html! {
        div class="space-y-2" {
            div class="flex items-center justify-between" {
                span class="text-sm text-[#94A3B8]" { "健康度" }
                span class="text-lg font-bold" style={"color: " (color_end) ";"} {
                    (format!("{:.0}", score_pct))
                }
            }
            div class="h-3 bg-[#1E293B] rounded-full overflow-hidden" {
                div
                    class="h-full rounded-full transition-all duration-700 ease-out"
                    style={
                        "width: " (score_pct) "%; "
                        "background: linear-gradient(90deg, " (color_start) ", " (color_end) ")"
                    }
                {}
            }
            div class="flex justify-between text-xs text-[#64748B]" {
                span { "0" }
                span { "50" }
                span { "100" }
            }
        }
    }
}

pub fn trend_badge(value: f64) -> Markup {
    let threshold = 0.01;
    let is_up = value > threshold;
    let is_down = value < -threshold;
    let is_flat = !is_up && !is_down;

    let display_value = format!("{:+.1}%", value.abs() * 100.0);

    html! {
        span class={
            "inline-flex items-center gap-1 px-2 py-1 rounded-lg text-xs font-medium"
            @if is_up { "text-emerald-400 bg-emerald-500/20" }
            @else if is_down { "text-red-400 bg-red-500/20" }
            @else { "text-gray-400 bg-gray-500/20" }
        } {
            @if is_up {
                "↑"
            } @else if is_down {
                "↓"
            } @else {
                "→"
            }
            (display_value)
        }
    }
}

fn format_file_size(bytes: i64) -> String {
    if bytes >= 1024 * 1024 {
        format!("{:.1} MB", bytes as f64 / (1024.0 * 1024.0))
    } else if bytes >= 1024 {
        format!("{:.1} KB", bytes as f64 / 1024.0)
    } else {
        format!("{} B", bytes)
    }
}
