use maud::{html, Markup, PreEscaped};

pub struct LoginContext {
    pub csrf_token: String,
    pub error: Option<String>,
    pub remember_me: bool,
    pub github_auth_url: String,
    pub gitlab_auth_url: String,
    pub gitee_auth_url: String,
}

pub fn login_page(ctx: LoginContext) -> Markup {
    html! {
        (PreEscaped("<!DOCTYPE html>"))
        html lang="zh-CN" class="dark" {
            head {
                meta charset="UTF-8";
                meta name="viewport" content="width=device-width, initial-scale=1.0";
                meta name="csrf-token" content=(ctx.csrf_token);
                title { "登录 - CodeReview Platform" }
                link rel="preconnect" href="https://fonts.googleapis.com";
                link rel="preconnect" href="https://fonts.gstatic.com" crossorigin;
                link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet";
                link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/tailwindcss@3.3.0/dist/tailwind.min.css";
                style {
                    (PreEscaped(r#"
                        * {
                            font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
                        }
                        code, pre, .mono {
                            font-family: 'JetBrains Mono', 'Fira Code', monospace;
                        }
                        @keyframes gradientShift {
                            0%, 100% { background-position: 0% 50%; }
                            50% { background-position: 100% 50%; }
                        }
                        @keyframes float {
                            0%, 100% { transform: translateY(0); }
                            50% { transform: translateY(-10px); }
                        }
                        @keyframes slideUp {
                            from {
                                opacity: 0;
                                transform: translateY(30px);
                            }
                            to {
                                opacity: 1;
                                transform: translateY(0);
                            }
                        }
                        @keyframes pulseGlow {
                            0%, 100% { box-shadow: 0 0 20px rgba(59, 130, 246, 0.3); }
                            50% { box-shadow: 0 0 40px rgba(59, 130, 246, 0.6); }
                        }
                        .bg-animated {
                            background: linear-gradient(-45deg, #0F172A, #1E293B, #0F172A, #1E3A5F);
                            background-size: 400% 400%;
                            animation: gradientShift 15s ease infinite;
                        }
                        .float-animation {
                            animation: float 6s ease-in-out infinite;
                        }
                        .slide-up {
                            animation: slideUp 0.6s ease-out forwards;
                        }
                        .slide-up-delay-1 { animation-delay: 0.1s; opacity: 0; }
                        .slide-up-delay-2 { animation-delay: 0.2s; opacity: 0; }
                        .slide-up-delay-3 { animation-delay: 0.3s; opacity: 0; }
                        .slide-up-delay-4 { animation-delay: 0.4s; opacity: 0; }
                        .slide-up-delay-5 { animation-delay: 0.5s; opacity: 0; }
                        .pulse-glow {
                            animation: pulseGlow 2s ease-in-out infinite;
                        }
                        .oauth-btn {
                            transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
                        }
                        .oauth-btn:hover {
                            transform: translateY(-2px);
                        }
                        .code-pattern {
                            background-image: 
                                linear-gradient(rgba(59, 130, 246, 0.03) 1px, transparent 1px),
                                linear-gradient(90deg, rgba(59, 130, 246, 0.03) 1px, transparent 1px);
                            background-size: 50px 50px;
                        }
                    "#))
                }
            }
            body class="min-h-screen bg-animated code-pattern flex items-center justify-center p-4" {
                div class="absolute inset-0 overflow-hidden pointer-events-none" {
                    div class="absolute top-20 left-20 w-72 h-72 bg-[#3B82F6]/10 rounded-full blur-3xl float-animation" {}
                    div class="absolute bottom-20 right-20 w-96 h-96 bg-[#8B5CF6]/10 rounded-full blur-3xl float-animation" style="animation-delay: 1s" {}
                    div class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[600px] h-[600px] bg-[#10B981]/5 rounded-full blur-3xl" {}
                }
                div class="relative w-full max-w-md slide-up" {
                    div class="text-center mb-8 slide-up slide-up-delay-1" {
                        div class="inline-flex items-center justify-center w-20 h-20 bg-gradient-to-br from-[#3B82F6] to-[#8B5CF6] rounded-2xl mb-6 pulse-glow shadow-2xl" {
                            span class="text-4xl font-bold text-white" { "CR" }
                        }
                        h1 class="text-3xl font-bold text-white mb-2" { "CodeReview" }
                        p class="text-[#94A3B8] text-lg" { "让代码评审更高效、更专业" }
                    }
                    @if let Some(err) = &ctx.error {
                        div class="mb-6 p-4 bg-red-500/20 border border-red-500/50 rounded-xl text-red-400 text-center slide-up slide-up-delay-2" {
                            (err)
                        }
                    }
                    div class="bg-[#1E293B]/90 backdrop-blur-xl border border-[#334155] rounded-2xl p-8 shadow-2xl slide-up slide-up-delay-2" {
                        div class="space-y-4 mb-6" {
                            form action=(ctx.github_auth_url) method="GET" {
                                button type="submit" class="oauth-btn w-full flex items-center justify-center gap-3 px-6 py-3.5 bg-[#24292E] hover:bg-[#2D333B] text-white rounded-xl font-medium border border-[#30363D] shadow-lg" {
                                    svg class="w-5 h-5" viewBox="0 0 24 24" fill="currentColor" {
                                        path d="M12 0c-6.626 0-12 5.373-12 12 0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23.957-.266 1.983-.399 3.003-.404 1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576 4.765-1.589 8.199-6.086 8.199-11.386 0-6.627-5.373-12-12-12z" {}
                                    }
                                    span { "使用 GitHub 登录" }
                                }
                            }
                            form action=(ctx.gitlab_auth_url) method="GET" class="slide-up slide-up-delay-3" style="opacity: 0" {
                                button type="submit" class="oauth-btn w-full flex items-center justify-center gap-3 px-6 py-3.5 bg-[#FC6D26] hover:bg-[#E55A1F] text-white rounded-xl font-medium shadow-lg" {
                                    svg class="w-5 h-5" viewBox="0 0 24 24" fill="currentColor" {
                                        path d="M23.955 13.587l-1.342-4.135-2.664-8.189c-.135-.413-.562-.602-.939-.425-.377.177-.572.614-.436 1.027l2.553 7.848h-8.297l2.6-7.867c.129-.399-.072-.835-.455-1.02-.383-.185-.826.017-.955.416l-2.608 7.871H5.322l2.608-7.871c.131-.395-.066-.833-.449-1.02-.383-.187-.828.015-.959.41L3.863 9.458l-1.342 4.135a1.074 1.074 0 0 0 .401 1.206l10.933 7.969c.323.236.762.236 1.085 0l10.933-7.969a1.07 1.07 0 0 0 .401-1.206z" {}
                                    }
                                    span { "使用 GitLab 登录" }
                                }
                            }
                            form action=(ctx.gitee_auth_url) method="GET" class="slide-up slide-up-delay-4" style="opacity: 0" {
                                button type="submit" class="oauth-btn w-full flex items-center justify-center gap-3 px-6 py-3.5 bg-[#C71D23] hover:bg-[#A8181D] text-white rounded-xl font-medium shadow-lg" {
                                    svg class="w-5 h-5" viewBox="0 0 24 24" fill="currentColor" {
                                        path d="M12.482 0C5.596 0 0 5.596 0 12.482c0 5.522 3.582 10.193 8.558 11.875.625.114.852-.273.852-.604 0-.299-.01-1.093-.017-2.14-3.476.754-4.21-1.675-4.21-1.675-.568-1.44-1.39-1.823-1.39-1.823-1.134-.773.087-.756.087-.756 1.255.089 1.914 1.29 1.914 1.29 1.114 1.907 2.925 1.356 3.639 1.037.114-.806.436-1.357.793-1.669-2.775-.315-5.691-1.388-5.691-6.175 0-1.364.487-2.48 1.286-3.354-.128-.316-.558-1.586.122-3.305 0 0 1.05-.336 3.437 1.279 1-.277 2.07-.416 3.135-.421 1.064.005 2.135.144 3.135.421 2.385-1.615 3.433-1.279 3.433-1.279.682 1.719.252 2.989.124 3.305.8.874 1.285 1.99 1.285 3.354 0 4.797-2.921 5.855-5.706 6.165.448.386.846 1.143.846 2.304 0 1.661-.015 2.997-.015 3.403 0 .334.225.724.86.602 4.97-1.684 8.547-6.353 8.547-11.875C24.964 5.596 19.368 0 12.482 0z" {}
                                    }
                                    span { "使用 Gitee 登录" }
                                }
                            }
                        }
                        div class="relative my-8 slide-up slide-up-delay-5" style="opacity: 0" {
                            div class="absolute inset-0 flex items-center" {
                                div class="w-full border-t border-[#334155]" {}
                            }
                            div class="relative flex justify-center text-sm" {
                                span class="px-4 bg-[#1E293B] text-[#64748B]" { "或者使用本地账号登录" }
                            }
                        }
                        form action="/login" method="POST" class="space-y-5 slide-up slide-up-delay-5" style="opacity: 0" {
                            input type="hidden" name="csrf_token" value=(ctx.csrf_token);
                            div class="space-y-2" {
                                label class="block text-sm font-medium text-[#CBD5E1]" { "用户名" }
                                input
                                    type="text"
                                    name="username"
                                    required
                                    placeholder="请输入用户名"
                                    class="w-full px-4 py-3 bg-[#0F172A] border border-[#334155] rounded-xl text-white placeholder-[#64748B] focus:outline-none focus:border-[#3B82F6] focus:ring-2 focus:ring-[#3B82F6]/20 transition-all"
                                ;
                            }
                            div class="space-y-2" {
                                label class="block text-sm font-medium text-[#CBD5E1]" { "密码" }
                                input
                                    type="password"
                                    name="password"
                                    required
                                    placeholder="请输入密码"
                                    class="w-full px-4 py-3 bg-[#0F172A] border border-[#334155] rounded-xl text-white placeholder-[#64748B] focus:outline-none focus:border-[#3B82F6] focus:ring-2 focus:ring-[#3B82F6]/20 transition-all"
                                ;
                            }
                            div class="flex items-center justify-between" {
                                label class="flex items-center gap-2 cursor-pointer" {
                                    input type="checkbox" name="remember_me" checked[ctx.remember_me] class="w-4 h-4 rounded bg-[#0F172A] border-[#334155] text-[#3B82F6] focus:ring-[#3B82F6] focus:ring-offset-0";
                                    span class="text-sm text-[#94A3B8]" { "记住我" }
                                }
                                a href="#" class="text-sm text-[#3B82F6] hover:text-[#60A5FA] transition-colors" { "忘记密码?" }
                            }
                            button
                                type="submit"
                                class="w-full py-3.5 bg-gradient-to-r from-[#3B82F6] to-[#8B5CF6] hover:from-[#2563EB] hover:to-[#7C3AED] text-white font-medium rounded-xl shadow-lg shadow-[#3B82F6]/30 hover:shadow-[#3B82F6]/50 transition-all duration-300 hover:-translate-y-0.5"
                            {
                                "登录"
                            }
                        }
                    }
                    p class="mt-8 text-center text-sm text-[#64748B] slide-up slide-up-delay-5" style="opacity: 0" {
                        "登录即表示您同意我们的 "
                        a href="#" class="text-[#3B82F6] hover:text-[#60A5FA] transition-colors" { "服务条款" }
                        " 和 "
                        a href="#" class="text-[#3B82F6] hover:text-[#60A5FA] transition-colors" { "隐私政策" }
                    }
                }
            }
        }
    }
}
