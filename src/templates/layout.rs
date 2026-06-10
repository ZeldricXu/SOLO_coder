use maud::{html, Markup, PreEscaped};

pub enum FlashType {
    Success,
    Error,
    Info,
    Warning,
}

pub struct FlashMessage {
    pub message: String,
    pub flash_type: FlashType,
}

pub struct UserContext {
    pub username: String,
    pub avatar_url: Option<String>,
    pub role: String,
}

pub struct LayoutContext {
    pub title: String,
    pub description: Option<String>,
    pub csrf_token: String,
    pub current_path: String,
    pub user: Option<UserContext>,
    pub flashes: Vec<FlashMessage>,
}

fn flash_icon(flash_type: &FlashType) -> &'static str {
    match flash_type {
        FlashType::Success => "✓",
        FlashType::Error => "✕",
        FlashType::Info => "ℹ",
        FlashType::Warning => "⚠",
    }
}

fn flash_class(flash_type: &FlashType) -> &'static str {
    match flash_type {
        FlashType::Success => "bg-emerald-500/20 border-emerald-500/50 text-emerald-400",
        FlashType::Error => "bg-red-500/20 border-red-500/50 text-red-400",
        FlashType::Info => "bg-blue-500/20 border-blue-500/50 text-blue-400",
        FlashType::Warning => "bg-amber-500/20 border-amber-500/50 text-amber-400",
    }
}

fn nav_items() -> Vec<(&'static str, &'static str, &'static str)> {
    vec![
        ("/dashboard", "📊", "仪表盘"),
        ("/repositories", "📦", "仓库"),
        ("/merge-requests", "🔀", "MR/PR"),
        ("/issues", "🐛", "问题"),
        ("/checklist", "✅", "Checklist"),
        ("/stats", "📈", "统计"),
        ("/notifications", "🔔", "通知"),
        ("/team", "👥", "团队管理"),
    ]
}

pub fn base_layout(ctx: LayoutContext, content: Markup) -> Markup {
    html! {
        (PreEscaped("<!DOCTYPE html>"))
        html lang="zh-CN" class="dark" {
            head {
                meta charset="UTF-8";
                meta name="viewport" content="width=device-width, initial-scale=1.0";
                meta name="csrf-token" content=(ctx.csrf_token);
                title { (ctx.title) " - CodeReview Platform" }
                @if let Some(desc) = &ctx.description {
                    meta name="description" content=(desc);
                }
                link rel="preconnect" href="https://fonts.googleapis.com";
                link rel="preconnect" href="https://fonts.gstatic.com" crossorigin;
                link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet";
                link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/tailwindcss@3.3.0/dist/tailwind.min.css";
                style {
                    (PreEscaped(r#"
                        :root {
                            --bg-primary: #0F172A;
                            --bg-secondary: #1E293B;
                            --bg-tertiary: #334155;
                            --accent-primary: #3B82F6;
                            --accent-hover: #2563EB;
                            --text-primary: #F8FAFC;
                            --text-secondary: #94A3B8;
                            --text-muted: #64748B;
                            --border-color: #334155;
                            --success: #10B981;
                            --warning: #F59E0B;
                            --danger: #EF4444;
                            --info: #8B5CF6;
                        }
                        * {
                            font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
                        }
                        code, pre, .mono {
                            font-family: 'JetBrains Mono', 'Fira Code', monospace;
                        }
                        body {
                            background-color: var(--bg-primary);
                            color: var(--text-primary);
                            min-height: 100vh;
                        }
                        .sidebar-item.active {
                            background: linear-gradient(90deg, rgba(59, 130, 246, 0.2) 0%, transparent 100%);
                            border-left: 3px solid var(--accent-primary);
                        }
                        .sidebar-item:hover:not(.active) {
                            background-color: rgba(255, 255, 255, 0.05);
                        }
                        .flash-message {
                            animation: slideIn 0.3s ease-out;
                        }
                        @keyframes slideIn {
                            from {
                                transform: translateY(-20px);
                                opacity: 0;
                            }
                            to {
                                transform: translateY(0);
                                opacity: 1;
                            }
                        }
                        .dropdown-menu {
                            animation: fadeIn 0.2s ease-out;
                        }
                        @keyframes fadeIn {
                            from { opacity: 0; transform: translateY(-10px); }
                            to { opacity: 1; transform: translateY(0); }
                        }
                        ::-webkit-scrollbar {
                            width: 8px;
                            height: 8px;
                        }
                        ::-webkit-scrollbar-track {
                            background: var(--bg-secondary);
                        }
                        ::-webkit-scrollbar-thumb {
                            background: var(--bg-tertiary);
                            border-radius: 4px;
                        }
                        ::-webkit-scrollbar-thumb:hover {
                            background: #475569;
                        }
                    "#))
                }
            }
            body class="min-h-screen flex" {
                @if ctx.user.is_some() {
                    aside class="w-64 bg-[#0F172A] border-r border-[#334155] flex flex-col fixed h-full z-30 transition-transform duration-300 lg:translate-x-0 -translate-x-full" id="sidebar" {
                        div class="p-4 border-b border-[#334155] flex items-center gap-3" {
                            div class="w-10 h-10 bg-gradient-to-br from-[#3B82F6] to-[#8B5CF6] rounded-lg flex items-center justify-center font-bold text-lg" {
                                "CR"
                            }
                            div {
                                div class="font-semibold text-white" { "CodeReview" }
                                div class="text-xs text-[#94A3B8]" { "协作审查平台" }
                            }
                        }
                        nav class="flex-1 py-4 overflow-y-auto" {
                            @for (path, icon, label) in nav_items() {
                                a href=(path) class={
                                    "sidebar-item flex items-center gap-3 px-4 py-3 text-[#94A3B8] hover:text-white transition-all duration-200"
                                    @if ctx.current_path.starts_with(path) { " active text-white" }
                                } {
                                    span class="text-lg" { (icon) }
                                    span { (label) }
                                    @if path == "/notifications" {
                                        span class="ml-auto w-5 h-5 bg-red-500 rounded-full text-xs flex items-center justify-center text-white" { "3" }
                                    }
                                }
                            }
                        }
                        div class="p-4 border-t border-[#334155]" {
                            @if let Some(user) = &ctx.user {
                                div class="flex items-center gap-3" {
                                    div class="w-10 h-10 rounded-full bg-gradient-to-br from-[#3B82F6] to-[#8B5CF6] flex items-center justify-center text-white font-semibold" {
                                        @if let Some(avatar) = &user.avatar_url {
                                            img src=(avatar) alt=(user.username) class="w-full h-full rounded-full object-cover";
                                        } @else {
                                            (user.username.chars().next().unwrap_or('U').to_ascii_uppercase())
                                        }
                                    }
                                    div class="flex-1 min-w-0" {
                                        div class="font-medium text-white truncate" { (user.username) }
                                        div class="text-xs text-[#94A3B8] truncate" { (user.role) }
                                    }
                                }
                            }
                        }
                    }
                    div class="flex-1 flex flex-col lg:ml-64" {
                        header class="h-16 bg-[#0F172A]/95 backdrop-blur-sm border-b border-[#334155] flex items-center justify-between px-4 lg:px-6 sticky top-0 z-20" {
                            div class="flex items-center gap-4" {
                                button class="lg:hidden text-white p-2 hover:bg-white/10 rounded-lg transition-colors" onclick="toggleSidebar()" {
                                    "☰"
                                }
                                div class="relative hidden md:block" {
                                    input type="text" placeholder="搜索 MR、问题、仓库..." class="w-80 pl-10 pr-4 py-2 bg-[#1E293B] border border-[#334155] rounded-lg text-white placeholder-[#64748B] focus:outline-none focus:border-[#3B82F6] transition-colors";
                                    span class="absolute left-3 top-1/2 -translate-y-1/2 text-[#64748B]" { "🔍" }
                                }
                            }
                            div class="flex items-center gap-4" {
                                div class="relative" {
                                    button class="relative p-2 text-[#94A3B8] hover:text-white hover:bg-white/10 rounded-lg transition-colors" onclick="toggleNotifications()" {
                                        "🔔"
                                        span class="absolute top-1 right-1 w-4 h-4 bg-red-500 rounded-full text-xs flex items-center justify-center text-white" { "3" }
                                    }
                                    div class="hidden absolute right-0 mt-2 w-80 bg-[#1E293B] border border-[#334155] rounded-lg shadow-xl dropdown-menu" id="notificationDropdown" {
                                        div class="p-3 border-b border-[#334155] font-medium" { "通知" }
                                        div class="max-h-80 overflow-y-auto" {
                                            div class="p-3 hover:bg-white/5 border-b border-[#334155]/50 cursor-pointer" {
                                                div class="flex gap-3" {
                                                    span class="text-[#3B82F6]" { "🔀" }
                                                    div class="flex-1" {
                                                        div class="text-sm" { "新的 MR 等待你的评审" }
                                                        div class="text-xs text-[#64748B] mt-1" { "2 分钟前" }
                                                    }
                                                }
                                            }
                                            div class="p-3 hover:bg-white/5 border-b border-[#334155]/50 cursor-pointer" {
                                                div class="flex gap-3" {
                                                    span class="text-[#10B981]" { "✅" }
                                                    div class="flex-1" {
                                                        div class="text-sm" { "MR #123 已通过评审" }
                                                        div class="text-xs text-[#64748B] mt-1" { "1 小时前" }
                                                    }
                                                }
                                            }
                                            div class="p-3 hover:bg-white/5 cursor-pointer" {
                                                div class="flex gap-3" {
                                                    span class="text-[#EF4444]" { "🐛" }
                                                    div class="flex-1" {
                                                        div class="text-sm" { "严重问题 #456 已指派给你" }
                                                        div class="text-xs text-[#64748B] mt-1" { "3 小时前" }
                                                    }
                                                }
                                            }
                                        }
                                        a href="/notifications" class="block p-3 text-center text-[#3B82F6] hover:bg-white/5 text-sm border-t border-[#334155]" {
                                            "查看全部通知"
                                        }
                                    }
                                }
                                div class="relative" {
                                    button class="flex items-center gap-2 hover:bg-white/10 p-1 rounded-lg transition-colors" onclick="toggleUserMenu()" {
                                        @if let Some(user) = &ctx.user {
                                            div class="w-8 h-8 rounded-full bg-gradient-to-br from-[#3B82F6] to-[#8B5CF6] flex items-center justify-center text-white text-sm font-semibold" {
                                                @if let Some(avatar) = &user.avatar_url {
                                                    img src=(avatar) alt=(user.username) class="w-full h-full rounded-full object-cover";
                                                } @else {
                                                    (user.username.chars().next().unwrap_or('U').to_ascii_uppercase())
                                                }
                                            }
                                            span class="hidden md:inline text-sm" { (user.username) }
                                        }
                                        span class="text-[#64748B] text-xs" { "▼" }
                                    }
                                    div class="hidden absolute right-0 mt-2 w-48 bg-[#1E293B] border border-[#334155] rounded-lg shadow-xl dropdown-menu" id="userDropdown" {
                                        a href="/profile" class="flex items-center gap-2 px-4 py-2 hover:bg-white/5 text-sm" {
                                            "👤" span { "个人设置" }
                                        }
                                        a href="/settings" class="flex items-center gap-2 px-4 py-2 hover:bg-white/5 text-sm" {
                                            "⚙️" span { "系统设置" }
                                        }
                                        div class="border-t border-[#334155] my-1" {}
                                        form action="/logout" method="POST" {
                                            input type="hidden" name="csrf_token" value=(ctx.csrf_token);
                                            button type="submit" class="w-full flex items-center gap-2 px-4 py-2 hover:bg-white/5 text-sm text-red-400 text-left" {
                                                "🚪" span { "退出登录" }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        @if !ctx.flashes.is_empty() {
                            div class="fixed top-20 right-4 z-50 space-y-2" {
                                @for flash in &ctx.flashes {
                                    div class={
                                        "flash-message flex items-center gap-3 px-4 py-3 rounded-lg border backdrop-blur-sm shadow-lg max-w-md"
                                        " " (flash_class(&flash.flash_type))
                                    } {
                                        span class="text-lg" { (flash_icon(&flash.flash_type)) }
                                        span class="flex-1" { (flash.message) }
                                        button onclick="this.parentElement.remove()" class="opacity-60 hover:opacity-100 transition-opacity" { "×" }
                                    }
                                }
                            }
                        }
                        main class="flex-1 p-4 lg:p-6 bg-gradient-to-b from-[#0F172A] to-[#0B1120] min-h-[calc(100vh-4rem)]" {
                            (content)
                        }
                        footer class="py-4 px-6 border-t border-[#334155] text-center text-[#64748B] text-sm" {
                            p { "© 2024 CodeReview Platform. 让代码评审更高效、更专业。" }
                        }
                    }
                } @else {
                    main class="flex-1 min-h-screen" {
                        (content)
                    }
                }
                script {
                    (PreEscaped(r#"
                        function toggleSidebar() {
                            document.getElementById('sidebar').classList.toggle('-translate-x-full');
                        }
                        function toggleNotifications() {
                            const dropdown = document.getElementById('notificationDropdown');
                            dropdown.classList.toggle('hidden');
                            document.getElementById('userDropdown').classList.add('hidden');
                        }
                        function toggleUserMenu() {
                            const dropdown = document.getElementById('userDropdown');
                            dropdown.classList.toggle('hidden');
                            document.getElementById('notificationDropdown').classList.add('hidden');
                        }
                        document.addEventListener('click', function(e) {
                            if (!e.target.closest('[onclick^="toggleNotifications"]') && 
                                !e.target.closest('#notificationDropdown')) {
                                document.getElementById('notificationDropdown').classList.add('hidden');
                            }
                            if (!e.target.closest('[onclick^="toggleUserMenu"]') && 
                                !e.target.closest('#userDropdown')) {
                                document.getElementById('userDropdown').classList.add('hidden');
                            }
                        });
                        setTimeout(() => {
                            document.querySelectorAll('.flash-message').forEach(el => {
                                el.style.opacity = '0';
                                el.style.transform = 'translateY(-20px)';
                                setTimeout(() => el.remove(), 300);
                            });
                        }, 5000);
                    "#))
                }
            }
        }
    }
}
