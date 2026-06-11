use maud::{html, Markup};
use crate::models::AuthUser;
use crate::templates::layout::base_layout;
use crate::templates::components::{component_styles, status_badge, avatar_with_name, severity_badge};

pub fn review_detail_page(user: &AuthUser, mr_id: &str, mr_title: &str, repo_name: &str, source_branch: &str, target_branch: &str, author_name: &str, author_avatar: &Option<String>, status: &str) -> Markup {
    base_layout(&format!("评审 - {}", mr_title), "merge_requests", user, html! {
        style { (component_styles()) }
        style { (review_detail_styles()) }

        div class="review-header" {
            div class="review-header-left" {
                div class="breadcrumb" {
                    a href="/merge-requests" { "合并请求" }
                    span { " / " }
                    span { (repo_name) }
                    span { " / " }
                    span { "!" (mr_id) }
                }
                div class="review-title-row" {
                    h1 class="review-title" { (mr_title) }
                    (status_badge(status))
                }
                div class="review-meta" {
                    div class="review-meta-item" {
                        (avatar_with_name(author_name, author_avatar, 20))
                        span { (author_name) }
                        span class="meta-separator" { "·" }
                        span { "feature/auth" }
                        svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="14" height="14" {
                            line x1="5" y1="12" x2="19" y2="12";
                            polyline points="12 5 19 12 12 19";
                        }
                        span { "main" }
                        span class="meta-separator" { "·" }
                        span { "更新于 2小时前" }
                    }
                }
            }
            div class="review-header-actions" {
                button class="btn btn-secondary" {
                    svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16" {
                        path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4";
                        polyline points="7 10 12 15 17 10";
                        line x1="12" y1="15" x2="12" y2="3";
                    }
                    "刷新"
                }
                button class="btn btn-secondary" {
                    svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16" {
                        circle cx="18" cy="5" r="3";
                        circle cx="6" cy="12" r="3";
                        circle cx="18" cy="19" r="3";
                        line x1="8.59" y1="13.51" x2="15.42" y2="17.49";
                        line x1="15.41" y1="6.51" x2="8.59" y2="10.49";
                    }
                    "分享"
                }
                button class="btn btn-success" {
                    svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16" {
                        path d="M22 11.08V12a10 10 0 1 1-5.93-9.14";
                        polyline points="22 4 12 14.01 9 11.01";
                    }
                    "通过评审"
                }
                button class="btn btn-warning" {
                    svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16" {
                        path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z";
                        line x1="12" y1="9" x2="12" y2="13";
                        line x1="12" y1="17" x2="12.01" y2="17";
                    }
                    "请求修改"
                }
            }
        }

        div class="tabs review-tabs" {
            button class="tab-btn active" {
                svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="14" height="14" {
                    path d="M9 19c-5 1.5-5-2.5-7-3m14 6v-3.87a3.37 3.37 0 0 0-.94-2.61c3.14-.35 6.44-1.54 6.44-7A5.44 5.44 0 0 0 20 4.77 5.07 5.07 0 0 0 19.91 1S18.73.65 16 2.48a13.38 13.38 0 0 0-7 0C6.27.65 5.09 1 5.09 1A5.07 5.07 0 0 0 5 4.77a5.44 5.44 0 0 0-1.5 3.78c0 5.42 3.3 6.61 6.44 7A3.37 3.37 0 0 0 9 18.13V22";
                }
                "变更文件"
                span class="tab-count" { "8" }
            }
            button class="tab-btn" {
                svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="14" height="14" {
                    path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z";
                }
                "评论"
                span class="tab-count" { "12" }
            }
            button class="tab-btn" {
                svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="14" height="14" {
                    path d="M9 11l3 3L22 4";
                    path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11";
                }
                "Checklist"
            }
            button class="tab-btn" {
                svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="14" height="14" {
                    path d="M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2z";
                    path d="M12 16v-4";
                    path d="M12 8h.01";
                }
                "AI建议"
                span class="tab-count ai-badge" { "3" }
            }
            button class="tab-btn" {
                svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="14" height="14" {
                    circle cx="12" cy="12" r="10";
                    path d="M12 8v4";
                    path d="M12 16h.01";
                }
                "问题"
                span class="tab-count issue-badge" { "2" }
            }
        }

        div class="review-container" {
            div class="file-tree-panel" {
                div class="panel-header" {
                    span class="panel-title" { "变更文件" }
                    span class="file-count" { "8 个文件 · +412 -156" }
                }
                div class="file-tree" {
                    div class="file-tree-folder" {
                        div class="file-tree-item folder" onclick="this.classList.toggle('expanded')" {
                            span class="folder-icon" { "▶" }
                            span class="file-name" { "src" }
                        }
                        div class="file-tree-children" {
                            div class="file-tree-folder" {
                                div class="file-tree-item folder" onclick="this.classList.toggle('expanded')" {
                                    span class="folder-icon" { "▶" }
                                    span class="file-name" { "services" }
                                }
                                div class="file-tree-children" {
                                    div class="file-tree-item file modified active" {
                                        span class="file-icon" { "📄" }
                                        span class="file-name" { "auth_service.rs" }
                                        span class="file-diff" { "+45 -12" }
                                        span class="comment-dot" title="3 条评论" { "3" }
                                    }
                                    div class="file-tree-item file added" {
                                        span class="file-icon" { "📄" }
                                        span class="file-name" { "ai_review_service.rs" }
                                        span class="file-diff" { "+128" }
                                    }
                                }
                            }
                            div class="file-tree-folder" {
                                div class="file-tree-item folder expanded" onclick="this.classList.toggle('expanded')" {
                                    span class="folder-icon expanded" { "▼" }
                                    span class="file-name" { "handlers" }
                                }
                                div class="file-tree-children" {
                                    div class="file-tree-item file modified" {
                                        span class="file-icon" { "📄" }
                                        span class="file-name" { "auth_handler.rs" }
                                        span class="file-diff" { "+87 -23" }
                                        span class="comment-dot" title="5 条评论" { "5" }
                                    }
                                    div class="file-tree-item file modified" {
                                        span class="file-icon" { "📄" }
                                        span class="file-name" { "dashboard_handler.rs" }
                                        span class="file-diff" { "+56 -8" }
                                    }
                                }
                            }
                            div class="file-tree-item file modified" {
                                span class="file-icon" { "📄" }
                                span class="file-name" { "lib.rs" }
                                span class="file-diff" { "+12 -3" }
                            }
                        }
                    }
                    div class="file-tree-folder" {
                        div class="file-tree-item folder" onclick="this.classList.toggle('expanded')" {
                            span class="folder-icon" { "▶" }
                            span class="file-name" { "tests" }
                        }
                        div class="file-tree-children" {
                            div class="file-tree-item file added" {
                                span class="file-icon" { "📄" }
                                span class="file-name" { "auth_service_test.rs" }
                                span class="file-diff" { "+84" }
                            }
                        }
                    }
                    div class="file-tree-item file modified" {
                        span class="file-icon" { "📄" }
                        span class="file-name" { "Cargo.toml" }
                        span class="file-diff" { "+2 -1" }
                    }
                    div class="file-tree-item file added" {
                        span class="file-icon" { "📄" }
                        span class="file-name" { ".env.example" }
                        span class="file-diff" { "+12" }
                    }
                }
            }

            div class="diff-panel" {
                div class="diff-header" {
                    div class="diff-file-path" {
                        span { "src/handlers/auth_handler.rs" }
                    }
                    div class="diff-stats" {
                        span class="stat added" { "+87" }
                        span class="stat removed" { "-23" }
                    }
                    div class="diff-actions" {
                        button class="btn btn-sm btn-ghost" { "查看原始" }
                        select class="diff-view-select" {
                            option value="split" { "分栏视图" }
                            option value="unified" { "统一视图" }
                        }
                    }
                }

                div class="diff-content" {
                    div class="diff-side diff-left" {
                        div class="diff-file-header" {
                            span { "删除的内容" }
                        }
                        div class="diff-lines" {
                            (diff_line("128", "    pub async fn login(session: Session, form: web::Json<LoginRequest>,", "", "context", ""))
                            (diff_line("129", "        auth_service: web::Data<AuthService>,", "", "context", ""))
                            (diff_line("130", "    ) -> AppResult<impl Responder> {", "", "context", ""))
                            (diff_line("131", "        let user = auth_service.authenticate(&form.email, &form.password).await?;", "", "removed", ""))
                            (diff_line("132", "", "        let credentials = auth_service", "added", "highlight"))
                            (diff_line("133", "", "            .authenticate(&form.email, &form.password)", "added", ""))
                            (diff_line("134", "", "            .await?;", "added", ""))
                            (diff_line("135", "", "", "added", "comment"))
                            (diff_line("136", "        session.set(SESSION_ID_KEY, user.id.to_string())?;", "", "context", ""))
                        }
                    }

                    div class="diff-side diff-right" {
                        div class="diff-file-header" {
                            span { "新增的内容" }
                        }
                        div class="diff-lines" {
                            (diff_line("128", "    pub async fn login(session: Session, form: web::Json<LoginRequest>,", "", "context", ""))
                            (diff_line("129", "        auth_service: web::Data<AuthService>,", "", "context", ""))
                            (diff_line("130", "    ) -> AppResult<impl Responder> {", "", "context", ""))
                            (diff_line("", "", "        let credentials = auth_service", "added", "highlight"))
                            (diff_line("", "", "            .authenticate(&form.email, &form.password)", "added", ""))
                            (diff_line("", "", "            .await?;", "added", ""))
                            (diff_line("", "", "", "added", "comment"))
                            (diff_line("", "", "        session.set(SESSION_ID_KEY, credentials.user.id.to_string())?;", "added", ""))
                            (diff_line("131", "", "        let user = credentials.user;", "added", ""))
                        }
                    }
                }

                div class="inline-comment-thread" {
                    div class="inline-comment-header" {
                        (avatar_with_name("张三", &None, 28))
                        div class="comment-meta" {
                            span class="comment-author" { "张三" }
                            span class="comment-time" { "2小时前" }
                        }
                        div class="comment-actions" {
                            button class="comment-action-btn" { "✓ 解决" }
                            button class="comment-action-btn" { "⋯" }
                        }
                    }
                    div class="comment-content" {
                        p { "这里可以优化一下，把 credentials.user 提取出来，避免重复访问。" }
                        (severity_badge("minor"))
                    }
                    div class="comment-reply-form" {
                        (avatar_with_name("李四", &None, 24))
                        input type="text" class="reply-input" placeholder="回复...";
                        button class="btn btn-sm btn-primary" { "回复" }
                    }
                }

                div class="diff-content" style="margin-top: 24px;" {
                    div class="diff-side diff-left" {
                        div class="diff-file-header" {
                            span { "删除的内容" }
                        }
                        div class="diff-lines" {
                            (diff_line("200", "    Ok(HttpResponse::Ok().json(ApiResponse::success(user)))", "", "context", ""))
                            (diff_line("201", "}", "", "context", ""))
                            (diff_line("202", "", "", "empty", ""))
                        }
                    }
                    div class="diff-side diff-right" {
                        div class="diff-file-header" {
                            span { "新增的内容" }
                        }
                        div class="diff-lines" {
                            (diff_line("200", "    Ok(HttpResponse::Ok().json(ApiResponse::success(user)))", "", "context", ""))
                            (diff_line("201", "}", "", "context", ""))
                            (diff_line("202", "", "", "empty", ""))
                        }
                    }
                }
            }

            div class="side-panel" {
                div class="side-tabs" {
                    button class="side-tab active" data-tab="comments" {
                        "评论"
                        span class="side-tab-count" { "12" }
                    }
                    button class="side-tab" data-tab="checklist" {
                        "Checklist"
                    }
                    button class="side-tab" data-tab="ai" {
                        "AI建议"
                        span class="side-tab-count ai" { "3" }
                    }
                }

                div class="side-panel-content" id="comments-panel" {
                    div class="comment-thread" {
                        div class="comment-thread-header resolved" {
                            span class="thread-status" { "✓ 已解决" }
                            span class="thread-location" { "auth_service.rs:145" }
                        }
                        div class="comment-item" {
                            (avatar_with_name("李四", &None, 28))
                            div class="comment-body" {
                                div class="comment-header" {
                                    span class="comment-author" { "李四" }
                                    span class="comment-time" { "昨天 15:30" }
                                }
                                div class="comment-text" {
                                    p { "这里的错误处理可以更友好一些，建议返回具体的错误信息给前端。" }
                                }
                                div class="comment-actions-inline" {
                                    button { "回复" }
                                    button { "👍 3" }
                                }
                            }
                        }
                        div class="comment-item reply" {
                            (avatar_with_name("王五", &None, 24))
                            div class="comment-body" {
                                div class="comment-header" {
                                    span class="comment-author" { "王五" }
                                    span class="comment-time" { "昨天 16:00" }
                                }
                                div class="comment-text" {
                                    p { "同意，已经修复了，现在会返回具体的错误码和消息。" }
                                }
                            }
                        }
                    }

                    div class="comment-thread" {
                        div class="comment-thread-header unresolved" {
                            span class="thread-status" { "● 未解决" }
                            span class="thread-location" { "auth_handler.rs:89" }
                        }
                        div class="comment-item" {
                            (avatar_with_name("赵六", &None, 28))
                            div class="comment-body" {
                                div class="comment-header" {
                                    span class="comment-author" { "赵六" }
                                    span class="comment-time" { "3小时前" }
                                    (severity_badge("major"))
                                }
                                div class="comment-text" {
                                    p { "这里有个安全问题，密码验证失败时应该使用相同的响应时间，防止时序攻击。" }
                                    pre class="code-inline" {
                                        code { "// TODO: 添加恒定时间比较" }
                                    }
                                }
                                div class="comment-actions-inline" {
                                    button { "回复" }
                                    button { "👍 5" }
                                    button { "创建问题" }
                                }
                            }
                        }
                    }

                    div class="comment-input-box" {
                        (avatar_with_name(&user.username, &user.avatar_url, 32))
                        div class="comment-input-wrapper" {
                            textarea class="comment-input" placeholder="添加评论...";
                            div class="comment-input-actions" {
                                div class="input-tools" {
                                    button class="tool-btn" { "B" }
                                    button class="tool-btn" { "I" }
                                    button class="tool-btn" { "`" }
                                    button class="tool-btn" { "📎" }
                                    button class="tool-btn" { "@" }
                                }
                                button class="btn btn-sm btn-primary" { "评论" }
                            }
                        }
                    }
                }

                div class="side-panel-content hidden" id="checklist-panel" {
                    div class="checklist-section" {
                        div class="checklist-progress" {
                            div class="progress-bar" {
                                div class="progress-fill" style="width: 60%;";
                            }
                            span class="progress-text" { "6/10 项已完成" }
                        }

                        div class="checklist-group" {
                            div class="checklist-group-header" {
                                span class="group-toggle" { "▼" }
                                span class="group-title" { "代码规范" }
                                span class="group-progress" { "3/3" }
                            }
                            div class="checklist-items" {
                                div class="checklist-item" {
                                    div class="checklist-checkbox checked" { "✓" }
                                    div class="checklist-item-content" {
                                        div class="checklist-item-title" { "命名规范" }
                                        div class="checklist-item-desc" { "变量和函数命名符合Rust规范" }
                                        div class="checklist-item-reviewer" {
                                            (avatar_with_name("张三", &None, 16))
                                            span { "张三 · 2小时前" }
                                        }
                                    }
                                }
                                div class="checklist-item" {
                                    div class="checklist-checkbox checked" { "✓" }
                                    div class="checklist-item-content" {
                                        div class="checklist-item-title" { "代码格式" }
                                        div class="checklist-item-desc" { "已运行 cargo fmt" }
                                        div class="checklist-item-reviewer" {
                                            (avatar_with_name("张三", &None, 16))
                                            span { "张三 · 2小时前" }
                                        }
                                    }
                                }
                                div class="checklist-item" {
                                    div class="checklist-checkbox checked" { "✓" }
                                    div class="checklist-item-content" {
                                        div class="checklist-item-title" { "注释完整" }
                                        div class="checklist-item-desc" { "公共API有完整文档注释" }
                                        div class="checklist-item-reviewer" {
                                            (avatar_with_name("李四", &None, 16))
                                            span { "李四 · 1小时前" }
                                        }
                                    }
                                }
                            }
                        }

                        div class="checklist-group" {
                            div class="checklist-group-header" {
                                span class="group-toggle" { "▼" }
                                span class="group-title" { "功能正确性" }
                                span class="group-progress" { "2/3" }
                            }
                            div class="checklist-items" {
                                div class="checklist-item" {
                                    div class="checklist-checkbox checked" { "✓" }
                                    div class="checklist-item-content" {
                                        div class="checklist-item-title" { "边界情况处理" }
                                        div class="checklist-item-desc" { "空输入、异常值等已处理" }
                                        div class="checklist-item-reviewer" {
                                            (avatar_with_name("王五", &None, 16))
                                            span { "王五 · 3小时前" }
                                        }
                                    }
                                }
                                div class="checklist-item failed" {
                                    div class="checklist-checkbox" { "" }
                                    div class="checklist-item-content" {
                                        div class="checklist-item-title" { "错误处理" }
                                        div class="checklist-item-desc" { "所有错误都有适当处理" }
                                        div class="checklist-item-reviewer" {
                                            (avatar_with_name("赵六", &None, 16))
                                            span { "赵六 · 2小时前" }
                                        }
                                    }
                                }
                                div class="checklist-item" {
                                    div class="checklist-checkbox checked" { "✓" }
                                    div class="checklist-item-content" {
                                        div class="checklist-item-title" { "单元测试" }
                                        div class="checklist-item-desc" { "核心逻辑有单元测试覆盖" }
                                        div class="checklist-item-reviewer" {
                                            (avatar_with_name("张三", &None, 16))
                                            span { "张三 · 4小时前" }
                                        }
                                    }
                                }
                            }
                        }

                        div class="checklist-group" {
                            div class="checklist-group-header" {
                                span class="group-toggle" { "▶" }
                                span class="group-title" { "安全性" }
                                span class="group-progress" { "1/2" }
                            }
                        }

                        div class="checklist-group" {
                            div class="checklist-group-header" {
                                span class="group-toggle" { "▶" }
                                span class="group-title" { "性能" }
                                span class="group-progress" { "0/2" }
                            }
                        }
                    }
                }

                div class="side-panel-content hidden" id="ai-panel" {
                    div class="ai-suggestions" {
                        div class="ai-suggestion-item" {
                            div class="ai-suggestion-header" {
                                span class="ai-icon" { "🤖" }
                                span class="ai-title" { "潜在的空指针问题" }
                                (severity_badge("critical"))
                            }
                            div class="ai-suggestion-content" {
                                p { "在第156行，`credentials.user` 可能为 None，建议添加空值检查。" }
                                pre class="ai-code" {
                                    code { "if let Some(user) = credentials.user {\n    // 处理逻辑\n} else {\n    return Err(AppError::Authentication(\"用户不存在\"));\n}" }
                                }
                            }
                            div class="ai-suggestion-actions" {
                                button class="btn btn-sm btn-success" { "采纳建议" }
                                button class="btn btn-sm btn-secondary" { "忽略" }
                                button class="btn btn-sm btn-ghost" { "查看详情" }
                            }
                        }

                        div class="ai-suggestion-item" {
                            div class="ai-suggestion-header" {
                                span class="ai-icon" { "🤖" }
                                span class="ai-title" { "可以优化的代码结构" }
                                (severity_badge("minor"))
                            }
                            div class="ai-suggestion-content" {
                                p { "这段认证逻辑可以提取为一个独立的函数，提高复用性。" }
                            }
                            div class="ai-suggestion-actions" {
                                button class="btn btn-sm btn-success" { "采纳建议" }
                                button class="btn btn-sm btn-secondary" { "忽略" }
                                button class="btn btn-sm btn-ghost" { "查看详情" }
                            }
                        }

                        div class="ai-suggestion-item" {
                            div class="ai-suggestion-header" {
                                span class="ai-icon" { "🤖" }
                                span class="ai-title" { "缺少错误日志" }
                                (severity_badge("info"))
                            }
                            div class="ai-suggestion-content" {
                                p { "建议在认证失败时添加错误日志，便于问题排查。" }
                                pre class="ai-code" {
                                    code { "tracing::warn!(\"Authentication failed for user: {}\", email);" }
                                }
                            }
                            div class="ai-suggestion-actions" {
                                button class="btn btn-sm btn-success" { "采纳建议" }
                                button class="btn btn-sm btn-secondary" { "忽略" }
                                button class="btn btn-sm btn-ghost" { "查看详情" }
                            }
                        }
                    }

                    div class="ai-scan-info" {
                        span { "AI 扫描完成于 2小时前" }
                        button class="btn btn-sm btn-ghost" {
                            svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="14" height="14" {
                                path d="M21 12a9 9 0 1 1-3-6.7";
                                polyline points="21 3 21 9 15 9";
                            }
                            "重新扫描"
                        }
                    }
                }
            }
        }
    })
}

fn review_detail_styles() -> &'static str {
    r#"
    .review-header {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        margin-bottom: 20px;
        padding-bottom: 20px;
        border-bottom: 1px solid #334155;
    }

    .review-header-left {
        flex: 1;
    }

    .breadcrumb {
        font-size: 13px;
        color: #64748B;
        margin-bottom: 12px;
    }

    .breadcrumb a {
        color: #3B82F6;
        text-decoration: none;
    }

    .breadcrumb a:hover {
        text-decoration: underline;
    }

    .review-title-row {
        display: flex;
        align-items: center;
        gap: 12px;
        margin-bottom: 8px;
    }

    .review-title {
        font-size: 24px;
        font-weight: 700;
        color: #F8FAFC;
    }

    .review-meta {
        display: flex;
        align-items: center;
        gap: 8px;
        font-size: 13px;
        color: #94A3B8;
    }

    .review-meta-item {
        display: flex;
        align-items: center;
        gap: 8px;
    }

    .meta-separator {
        color: #475569;
    }

    .review-header-actions {
        display: flex;
        gap: 8px;
    }

    .review-tabs {
        margin-bottom: 20px;
        border: none;
        background: transparent;
        padding: 0;
    }

    .review-tabs .tab-btn {
        display: flex;
        align-items: center;
        gap: 6px;
    }

    .tab-count {
        background: #334155;
        color: #94A3B8;
        padding: 2px 8px;
        border-radius: 10px;
        font-size: 11px;
        font-weight: 600;
    }

    .tab-count.ai-badge {
        background: rgba(139, 92, 246, 0.2);
        color: #A78BFA;
    }

    .tab-count.issue-badge {
        background: rgba(239, 68, 68, 0.2);
        color: #F87171;
    }

    .review-container {
        display: grid;
        grid-template-columns: 260px 1fr 380px;
        gap: 16px;
        height: calc(100vh - 280px);
        min-height: 600px;
    }

    .file-tree-panel, .diff-panel, .side-panel {
        background: #1E293B;
        border: 1px solid #334155;
        border-radius: 12px;
        overflow: hidden;
        display: flex;
        flex-direction: column;
    }

    .panel-header {
        padding: 14px 16px;
        border-bottom: 1px solid #334155;
        display: flex;
        justify-content: space-between;
        align-items: center;
        background: #0F172A;
    }

    .panel-title {
        font-weight: 600;
        color: #F1F5F9;
        font-size: 14px;
    }

    .file-count {
        font-size: 12px;
        color: #64748B;
    }

    .file-tree {
        flex: 1;
        overflow-y: auto;
        padding: 8px;
    }

    .file-tree-item {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 8px 10px;
        border-radius: 6px;
        cursor: pointer;
        font-size: 13px;
        transition: background 0.15s;
    }

    .file-tree-item:hover {
        background: #334155;
    }

    .file-tree-item.active {
        background: rgba(59, 130, 246, 0.15);
        color: #3B82F6;
    }

    .file-tree-item.folder {
        font-weight: 500;
    }

    .folder-icon {
        font-size: 10px;
        color: #64748B;
        transition: transform 0.2s;
    }

    .folder-icon.expanded {
        transform: rotate(0deg);
    }

    .file-icon {
        font-size: 14px;
    }

    .file-name {
        flex: 1;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
    }

    .file-diff {
        font-size: 11px;
        color: #64748B;
        font-family: 'JetBrains Mono', monospace;
    }

    .file-tree-item.modified .file-name { color: #F59E0B; }
    .file-tree-item.added .file-name { color: #10B981; }
    .file-tree-item.deleted .file-name { color: #EF4444; text-decoration: line-through; }

    .comment-dot {
        width: 18px;
        height: 18px;
        background: #3B82F6;
        color: white;
        border-radius: 50%;
        font-size: 10px;
        font-weight: 600;
        display: flex;
        align-items: center;
        justify-content: center;
    }

    .file-tree-children {
        padding-left: 20px;
        display: none;
    }

    .file-tree-folder.expanded > .file-tree-children {
        display: block;
    }

    .diff-header {
        padding: 14px 16px;
        border-bottom: 1px solid #334155;
        display: flex;
        justify-content: space-between;
        align-items: center;
        background: #0F172A;
    }

    .diff-file-path {
        font-family: 'JetBrains Mono', monospace;
        font-size: 13px;
        color: #E2E8F0;
    }

    .diff-stats {
        display: flex;
        gap: 12px;
        font-family: 'JetBrains Mono', monospace;
        font-size: 13px;
        font-weight: 600;
    }

    .diff-stats .added { color: #10B981; }
    .diff-stats .removed { color: #EF4444; }

    .diff-actions {
        display: flex;
        align-items: center;
        gap: 8px;
    }

    .diff-view-select {
        padding: 6px 10px;
        background: #0F172A;
        border: 1px solid #334155;
        border-radius: 6px;
        color: #E2E8F0;
        font-size: 12px;
    }

    .diff-content {
        display: grid;
        grid-template-columns: 1fr 1fr;
        flex: 1;
        overflow: auto;
    }

    .diff-side {
        min-width: 0;
    }

    .diff-file-header {
        padding: 10px 16px;
        background: #0F172A;
        border-bottom: 1px solid #334155;
        font-size: 12px;
        color: #64748B;
        font-weight: 500;
    }

    .diff-left .diff-file-header {
        border-right: 1px solid #334155;
    }

    .diff-lines {
        font-family: 'JetBrains Mono', monospace;
        font-size: 13px;
        line-height: 1.6;
    }

    .diff-line {
        display: flex;
        position: relative;
    }

    .diff-line:hover {
        background: rgba(59, 130, 246, 0.05);
    }

    .diff-line:hover .add-comment-btn {
        opacity: 1;
    }

    .diff-line-number {
        width: 50px;
        padding: 2px 8px;
        text-align: right;
        color: #475569;
        user-select: none;
        flex-shrink: 0;
    }

    .diff-line-code {
        flex: 1;
        padding: 2px 8px;
        white-space: pre;
        min-width: 0;
    }

    .diff-line.context .diff-line-code {
        color: #CBD5E1;
    }

    .diff-line.added {
        background: rgba(16, 185, 129, 0.1);
    }

    .diff-line.added .diff-line-code {
        color: #34D399;
    }

    .diff-line.removed {
        background: rgba(239, 68, 68, 0.1);
    }

    .diff-line.removed .diff-line-code {
        color: #F87171;
    }

    .diff-line.highlight {
        background: rgba(59, 130, 246, 0.15);
    }

    .diff-line.empty .diff-line-number,
    .diff-line.empty .diff-line-code {
        background: #0F172A;
    }

    .diff-line.comment {
        background: rgba(139, 92, 246, 0.1);
        border-left: 3px solid #8B5CF6;
    }

    .add-comment-btn {
        position: absolute;
        left: -8px;
        top: 50%;
        transform: translateY(-50%);
        width: 20px;
        height: 20px;
        background: #3B82F6;
        color: white;
        border: none;
        border-radius: 50%;
        font-size: 14px;
        font-weight: bold;
        cursor: pointer;
        opacity: 0;
        transition: opacity 0.15s;
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 10;
    }

    .inline-comment-thread {
        grid-column: 1 / -1;
        background: #0F172A;
        border: 1px solid #334155;
        border-radius: 8px;
        margin: 8px 16px;
        padding: 16px;
    }

    .inline-comment-header {
        display: flex;
        align-items: center;
        gap: 10px;
        margin-bottom: 12px;
    }

    .comment-meta {
        flex: 1;
    }

    .comment-author {
        font-weight: 600;
        color: #F1F5F9;
        font-size: 14px;
        margin-right: 8px;
    }

    .comment-time {
        font-size: 12px;
        color: #64748B;
    }

    .comment-actions {
        display: flex;
        gap: 4px;
    }

    .comment-action-btn {
        background: transparent;
        border: none;
        color: #64748B;
        font-size: 12px;
        cursor: pointer;
        padding: 4px 8px;
        border-radius: 4px;
    }

    .comment-action-btn:hover {
        background: #334155;
        color: #E2E8F0;
    }

    .comment-content {
        margin-bottom: 12px;
        color: #CBD5E1;
        font-size: 14px;
        line-height: 1.6;
    }

    .comment-content p {
        margin-bottom: 8px;
    }

    .comment-reply-form {
        display: flex;
        gap: 8px;
        align-items: flex-start;
    }

    .reply-input {
        flex: 1;
        padding: 8px 12px;
        background: #0F172A;
        border: 1px solid #334155;
        border-radius: 6px;
        color: #E2E8F0;
        font-size: 13px;
    }

    .side-tabs {
        display: flex;
        border-bottom: 1px solid #334155;
        background: #0F172A;
    }

    .side-tab {
        flex: 1;
        padding: 12px 8px;
        background: transparent;
        border: none;
        color: #64748B;
        font-size: 13px;
        font-weight: 500;
        cursor: pointer;
        transition: all 0.2s;
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 4px;
    }

    .side-tab:hover {
        color: #E2E8F0;
    }

    .side-tab.active {
        color: #3B82F6;
        border-bottom: 2px solid #3B82F6;
    }

    .side-tab-count {
        background: #334155;
        color: #94A3B8;
        padding: 1px 6px;
        border-radius: 8px;
        font-size: 10px;
        font-weight: 600;
    }

    .side-tab-count.ai {
        background: rgba(139, 92, 246, 0.2);
        color: #A78BFA;
    }

    .side-panel-content {
        flex: 1;
        overflow-y: auto;
        padding: 16px;
    }

    .side-panel-content.hidden {
        display: none;
    }

    .comment-thread {
        background: #0F172A;
        border: 1px solid #334155;
        border-radius: 8px;
        margin-bottom: 16px;
        overflow: hidden;
    }

    .comment-thread-header {
        padding: 10px 14px;
        background: #1E293B;
        border-bottom: 1px solid #334155;
        display: flex;
        justify-content: space-between;
        align-items: center;
        font-size: 12px;
    }

    .comment-thread-header.resolved {
        background: rgba(16, 185, 129, 0.05);
        border-left: 3px solid #10B981;
    }

    .comment-thread-header.unresolved {
        background: rgba(239, 68, 68, 0.05);
        border-left: 3px solid #EF4444;
    }

    .thread-status {
        font-weight: 600;
    }

    .thread-status.resolved { color: #10B981; }
    .thread-status.unresolved { color: #EF4444; }

    .thread-location {
        color: #64748B;
        font-family: 'JetBrains Mono', monospace;
    }

    .comment-item {
        display: flex;
        gap: 12px;
        padding: 14px;
        border-bottom: 1px solid #334155;
    }

    .comment-item:last-child {
        border-bottom: none;
    }

    .comment-item.reply {
        padding-left: 54px;
        background: rgba(51, 65, 85, 0.2);
    }

    .comment-body {
        flex: 1;
        min-width: 0;
    }

    .comment-header {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-bottom: 6px;
    }

    .comment-text {
        color: #CBD5E1;
        font-size: 14px;
        line-height: 1.6;
        margin-bottom: 8px;
    }

    .code-inline {
        background: #0F172A;
        border: 1px solid #334155;
        border-radius: 6px;
        padding: 8px 12px;
        margin-top: 8px;
        font-family: 'JetBrains Mono', monospace;
        font-size: 12px;
        color: #94A3B8;
        overflow-x: auto;
    }

    .comment-actions-inline {
        display: flex;
        gap: 16px;
    }

    .comment-actions-inline button {
        background: none;
        border: none;
        color: #64748B;
        font-size: 12px;
        cursor: pointer;
        padding: 0;
    }

    .comment-actions-inline button:hover {
        color: #3B82F6;
    }

    .comment-input-box {
        display: flex;
        gap: 12px;
        padding: 16px;
        background: #0F172A;
        border-top: 1px solid #334155;
    }

    .comment-input-wrapper {
        flex: 1;
    }

    .comment-input {
        width: 100%;
        min-height: 80px;
        padding: 10px 12px;
        background: #1E293B;
        border: 1px solid #334155;
        border-radius: 8px;
        color: #E2E8F0;
        font-size: 14px;
        font-family: inherit;
        resize: vertical;
        margin-bottom: 8px;
    }

    .comment-input:focus {
        border-color: #3B82F6;
        outline: none;
    }

    .comment-input-actions {
        display: flex;
        justify-content: space-between;
        align-items: center;
    }

    .input-tools {
        display: flex;
        gap: 4px;
    }

    .tool-btn {
        width: 28px;
        height: 28px;
        background: transparent;
        border: none;
        border-radius: 4px;
        color: #64748B;
        cursor: pointer;
        font-family: inherit;
        font-weight: 600;
    }

    .tool-btn:hover {
        background: #334155;
        color: #E2E8F0;
    }

    .checklist-section {
        display: flex;
        flex-direction: column;
        gap: 12px;
    }

    .checklist-progress {
        display: flex;
        align-items: center;
        gap: 12px;
        padding: 12px;
        background: #0F172A;
        border-radius: 8px;
    }

    .progress-bar {
        flex: 1;
        height: 8px;
        background: #334155;
        border-radius: 4px;
        overflow: hidden;
    }

    .progress-fill {
        height: 100%;
        background: linear-gradient(90deg, #3B82F6, #10B981);
        border-radius: 4px;
        transition: width 0.3s;
    }

    .progress-text {
        font-size: 12px;
        font-weight: 600;
        color: #94A3B8;
        white-space: nowrap;
    }

    .checklist-group-header {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 10px 12px;
        background: #0F172A;
        border: 1px solid #334155;
        border-radius: 6px;
        cursor: pointer;
    }

    .group-toggle {
        font-size: 10px;
        color: #64748B;
    }

    .group-title {
        flex: 1;
        font-weight: 600;
        color: #F1F5F9;
        font-size: 13px;
    }

    .group-progress {
        font-size: 12px;
        color: #64748B;
    }

    .ai-suggestions {
        display: flex;
        flex-direction: column;
        gap: 16px;
    }

    .ai-suggestion-item {
        background: #0F172A;
        border: 1px solid #334155;
        border-radius: 8px;
        padding: 16px;
    }

    .ai-suggestion-header {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-bottom: 12px;
    }

    .ai-icon {
        font-size: 18px;
    }

    .ai-title {
        flex: 1;
        font-weight: 600;
        color: #F1F5F9;
        font-size: 14px;
    }

    .ai-suggestion-content {
        margin-bottom: 12px;
    }

    .ai-suggestion-content p {
        color: #CBD5E1;
        font-size: 13px;
        line-height: 1.6;
        margin-bottom: 8px;
    }

    .ai-code {
        background: #0F172A;
        border: 1px solid #334155;
        border-radius: 6px;
        padding: 12px;
        font-family: 'JetBrains Mono', monospace;
        font-size: 12px;
        color: #94A3B8;
        overflow-x: auto;
    }

    .ai-suggestion-actions {
        display: flex;
        gap: 8px;
    }

    .ai-scan-info {
        margin-top: 20px;
        padding-top: 16px;
        border-top: 1px solid #334155;
        display: flex;
        justify-content: space-between;
        align-items: center;
        font-size: 12px;
        color: #64748B;
    }

    .hidden {
        display: none !important;
    }
    "#
}

fn diff_line(left_num: &str, left_code: &str, right_code: &str, line_type: &str, extra_class: &str) -> Markup {
    html! {
        div class={ "diff-line " (line_type) " " (extra_class) } {
            @if !line_type.is_empty() {
                button class="add-comment-btn" title="添加批注" { "+" }
            }
            @if line_type != "added" {
                span class="diff-line-number" { (left_num) }
                span class="diff-line-code" { (left_code) }
            } @else {
                span class="diff-line-number" { "" }
                span class="diff-line-code" { "" }
            }
            @if line_type == "added" || line_type == "context" {
                span class="diff-line-number" { (if line_type == "added" { "" } else { left_num }) }
                span class="diff-line-code" { (if line_type == "added" { right_code } else { left_code }) }
            }
        }
    }
}
