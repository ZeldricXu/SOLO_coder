use actix_session::Session;
use actix_web::{web, HttpResponse, Responder};
use uuid::Uuid;

use crate::handlers::auth_handler::SESSION_ID_KEY;
use crate::models::ai_rule::{AiRuleQuery, CreateAiRuleRequest, UpdateAiRuleRequest};
use crate::services::{AiRuleService, AuthService, PermissionService};
use crate::utils::{ApiResponse, AppError, AppResult, PaginatedResponse, PaginationQuery};

async fn get_current_user(
    session: &Session,
    auth_service: &AuthService,
) -> AppResult<crate::models::AuthUser> {
    let session_id = session
        .get::<String>(SESSION_ID_KEY)?
        .ok_or_else(|| AppError::Authentication("请先登录".to_string()))?;

    auth_service.get_current_user(&session_id).await
}

fn render_base_template(title: &str, user: &crate::models::AuthUser, content: maud::Markup) -> maud::Markup {
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
                    .btn {
                        padding: 8px 16px;
                        border-radius: 6px;
                        border: none;
                        cursor: pointer;
                        font-size: 14px;
                        font-weight: 500;
                        transition: all 0.2s;
                    }
                    .btn-primary {
                        background: #667eea;
                        color: white;
                    }
                    .btn-primary:hover {
                        background: #5a67d8;
                    }
                    .btn-secondary {
                        background: #e2e8f0;
                        color: #475569;
                    }
                    .btn-secondary:hover {
                        background: #cbd5e1;
                    }
                    .btn-danger {
                        background: #ef4444;
                        color: white;
                    }
                    .btn-danger:hover {
                        background: #dc2626;
                    }
                    .card {
                        background: white;
                        border-radius: 12px;
                        padding: 24px;
                        box-shadow: 0 2px 8px rgba(0,0,0,0.05);
                        margin-bottom: 24px;
                    }
                    .table {
                        width: 100%;
                        border-collapse: collapse;
                    }
                    .table th, .table td {
                        padding: 12px 16px;
                        text-align: left;
                        border-bottom: 1px solid #f0f0f0;
                    }
                    .table th {
                        background: #f8fafc;
                        font-weight: 600;
                        color: #475569;
                        font-size: 13px;
                    }
                    .table tr:hover {
                        background: #f8fafc;
                    }
                    .badge {
                        display: inline-block;
                        padding: 2px 8px;
                        border-radius: 12px;
                        font-size: 12px;
                        font-weight: 500;
                    }
                    .badge-active { background: #dcfce7; color: #16a34a; }
                    .badge-inactive { background: #f1f5f9; color: #64748b; }
                    .badge-default { background: #dbeafe; color: #2563eb; }
                    .pagination {
                        display: flex;
                        justify-content: center;
                        gap: 8px;
                        margin-top: 24px;
                    }
                    .pagination button {
                        padding: 8px 12px;
                        border: 1px solid #e2e8f0;
                        background: white;
                        border-radius: 6px;
                        cursor: pointer;
                    }
                    .pagination button:hover {
                        background: #f8fafc;
                    }
                    .pagination button.active {
                        background: #667eea;
                        color: white;
                        border-color: #667eea;
                    }
                    .form-group {
                        margin-bottom: 16px;
                    }
                    .form-group label {
                        display: block;
                        margin-bottom: 6px;
                        font-weight: 500;
                        color: #374151;
                        font-size: 14px;
                    }
                    .form-group input, .form-group select, .form-group textarea {
                        width: 100%;
                        padding: 8px 12px;
                        border: 1px solid #e2e8f0;
                        border-radius: 6px;
                        font-size: 14px;
                    }
                    .form-group input:focus, .form-group select:focus, .form-group textarea:focus {
                        outline: none;
                        border-color: #667eea;
                        box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.1);
                    }
                    .modal {
                        display: none;
                        position: fixed;
                        top: 0;
                        left: 0;
                        width: 100%;
                        height: 100%;
                        background: rgba(0,0,0,0.5);
                        z-index: 1000;
                        align-items: center;
                        justify-content: center;
                    }
                    .modal.show { display: flex; }
                    .modal-content {
                        background: white;
                        border-radius: 12px;
                        padding: 24px;
                        max-width: 600px;
                        width: 90%;
                        max-height: 80vh;
                        overflow-y: auto;
                    }
                    .modal-header {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        margin-bottom: 20px;
                    }
                    .modal-title {
                        font-size: 20px;
                        font-weight: 600;
                        color: #1a1a2e;
                    }
                    .modal-close {
                        background: none;
                        border: none;
                        font-size: 24px;
                        cursor: pointer;
                        color: #94a3b8;
                    }
                    .modal-footer {
                        display: flex;
                        justify-content: flex-end;
                        gap: 12px;
                        margin-top: 24px;
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
                    a href="/dashboard" class="nav-item" {
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
                    a href="/ai-rules" class="nav-item active" {
                        svg viewBox="0 0 24 24" fill="currentColor" {
                            path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z";
                        }
                        span { "AI规则管理" }
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

pub async fn ai_rules_page(
    session: Session,
    auth_service: web::Data<AuthService>,
) -> AppResult<impl Responder> {
    let user = get_current_user(&session, &auth_service).await?;

    let content = maud::html! {
        div class="card" {
            div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;" {
                h2 style="font-size: 18px; font-weight: 600;" { "AI 审查规则列表" }
                button class="btn btn-primary" onclick="showCreateModal()" { "新建规则" }
            }
            div style="display: flex; gap: 16px; margin-bottom: 20px;" {
                select id="filter-scope" onchange="loadRules()" style="padding: 8px 12px; border: 1px solid #e2e8f0; border-radius: 6px;" {
                    option value="" { "全部范围" }
                    option value="organization" { "组织级" }
                    option value="repository" { "仓库级" }
                }
                select id="filter-active" onchange="loadRules()" style="padding: 8px 12px; border: 1px solid #e2e8f0; border-radius: 6px;" {
                    option value="" { "全部状态" }
                    option value="true" { "启用" }
                    option value="false" { "禁用" }
                }
            }
            div id="rules-container" {
                div style="text-align: center; padding: 40px; color: #999;" { "加载中..." }
            }
            div class="pagination" id="pagination" {}
        }

        div id="create-modal" class="modal" {
            div class="modal-content" {
                div class="modal-header" {
                    h3 class="modal-title" { "新建 AI 规则" }
                    button class="modal-close" onclick="hideCreateModal()" { "×" }
                }
                div {
                    div class="form-group" {
                        label for="rule-name" { "规则名称" }
                        input type="text" id="rule-name" placeholder="请输入规则名称";
                    }
                    div class="form-group" {
                        label for="rule-desc" { "规则描述" }
                        textarea id="rule-desc" rows="2" placeholder="请输入规则描述" {}
                    }
                    div class="form-group" {
                        label for="rule-scope" { "规则范围" }
                        select id="rule-scope" {
                            option value="organization" { "组织级" }
                            option value="repository" { "仓库级" }
                        }
                    }
                    div class="form-group" {
                        label for="rule-severity" { "严重级别" }
                        select id="rule-severity" {
                            option value="strict" { "严格" }
                            option value="normal" selected { "正常" }
                            option value="loose" { "宽松" }
                        }
                    }
                    div class="form-group" {
                        label for="rule-prompt" { "自定义提示词" }
                        textarea id="rule-prompt" rows="6" placeholder="请输入自定义提示词" {}
                    }
                    div class="form-group" {
                        label { "启用分类" }
                        div style="display: flex; gap: 16px; flex-wrap: wrap;" {
                            label style="display: flex; align-items: center; gap: 6px; font-weight: normal;" {
                                input type="checkbox" value="naming" class="category-checkbox";
                                span { "命名规范" }
                            }
                            label style="display: flex; align-items: center; gap: 6px; font-weight: normal;" {
                                input type="checkbox" value="style" class="category-checkbox";
                                span { "代码风格" }
                            }
                            label style="display: flex; align-items: center; gap: 6px; font-weight: normal;" {
                                input type="checkbox" value="security" class="category-checkbox";
                                span { "安全问题" }
                            }
                            label style="display: flex; align-items: center; gap: 6px; font-weight: normal;" {
                                input type="checkbox" value="performance" class="category-checkbox";
                                span { "性能优化" }
                            }
                        }
                    }
                    div class="form-group" style="display: flex; gap: 16px;" {
                        div style="flex: 1;" {
                            label for="min-changed-lines" { "最小改动行数" }
                            input type="number" id="min-changed-lines" placeholder="可选";
                        }
                        div style="flex: 1;" {
                            label for="context-lines" { "上下文行数" }
                            input type="number" id="context-lines" placeholder="可选";
                        }
                    }
                    div class="form-group" style="display: flex; align-items: center; gap: 8px;" {
                        input type="checkbox" id="rule-active" checked;
                        label for="rule-active" style="margin-bottom: 0; font-weight: normal;" { "立即启用" }
                    }
                    div class="form-group" style="display: flex; align-items: center; gap: 8px;" {
                        input type="checkbox" id="rule-default";
                        label for="rule-default" style="margin-bottom: 0; font-weight: normal;" { "设为默认规则" }
                    }
                }
                div class="modal-footer" {
                    button class="btn btn-secondary" onclick="hideCreateModal()" { "取消" }
                    button class="btn btn-primary" onclick="createRule()" { "创建" }
                }
            }
        }

        script {
            r#"
            let currentPage = 1;
            let totalPages = 1;

            async function loadRules() {
                const scope = document.getElementById('filter-scope').value;
                const isActive = document.getElementById('filter-active').value;
                
                let url = `/api/ai-rules?page=${currentPage}&per_page=10`;
                if (scope) url += `&scope=${scope}`;
                if (isActive) url += `&is_active=${isActive}`;
                
                try {
                    const response = await fetch(url);
                    const data = await response.json();
                    
                    if (data.code === 200 && data.data) {
                        renderRules(data.data.items);
                        renderPagination(data.data.page, data.data.total_pages);
                    }
                } catch (error) {
                    console.error('Failed to load rules:', error);
                }
            }

            function renderRules(rules) {
                const container = document.getElementById('rules-container');
                
                if (rules.length === 0) {
                    container.innerHTML = '<div style="text-align: center; padding: 40px; color: #999;">暂无规则</div>';
                    return;
                }
                
                let html = '<table class="table"><thead><tr><th>规则名称</th><th>范围</th><th>严重级别</th><th>状态</th><th>操作</th></tr></thead><tbody>';
                
                rules.forEach(rule => {
                    const statusBadge = rule.is_active 
                        ? '<span class="badge badge-active">启用</span>' 
                        : '<span class="badge badge-inactive">禁用</span>';
                    const defaultBadge = rule.is_default 
                        ? '<span class="badge badge-default" style="margin-left: 4px;">默认</span>' 
                        : '';
                    const scopeText = rule.scope === 'organization' ? '组织级' : '仓库级';
                    
                    html += `
                        <tr>
                            <td>${rule.name}${defaultBadge}</td>
                            <td>${scopeText}</td>
                            <td>${rule.severity_level}</td>
                            <td>${statusBadge}</td>
                            <td>
                                <button class="btn btn-secondary" style="padding: 4px 8px; font-size: 12px;" onclick="viewRule('${rule.id}')">查看</button>
                                <button class="btn btn-secondary" style="padding: 4px 8px; font-size: 12px;" onclick="editRule('${rule.id}')">编辑</button>
                                ${!rule.is_default ? `<button class="btn btn-secondary" style="padding: 4px 8px; font-size: 12px;" onclick="setDefault('${rule.id}')">设为默认</button>` : ''}
                                <button class="btn btn-danger" style="padding: 4px 8px; font-size: 12px;" onclick="deleteRule('${rule.id}')">删除</button>
                            </td>
                        </tr>
                    `;
                });
                
                html += '</tbody></table>';
                container.innerHTML = html;
            }

            function renderPagination(page, totalPages) {
                const pagination = document.getElementById('pagination');
                let html = '';
                
                for (let i = 1; i <= totalPages; i++) {
                    const activeClass = i === page ? 'active' : '';
                    html += `<button class="${activeClass}" onclick="goToPage(${i})">${i}</button>`;
                }
                
                pagination.innerHTML = html;
            }

            function goToPage(page) {
                currentPage = page;
                loadRules();
            }

            function showCreateModal() {
                document.getElementById('create-modal').classList.add('show');
            }

            function hideCreateModal() {
                document.getElementById('create-modal').classList.remove('show');
            }

            function getSelectedCategories() {
                const checkboxes = document.querySelectorAll('.category-checkbox:checked');
                return Array.from(checkboxes).map(cb => cb.value);
            }

            async function createRule() {
                const name = document.getElementById('rule-name').value;
                const description = document.getElementById('rule-desc').value;
                const scope = document.getElementById('rule-scope').value;
                const severity = document.getElementById('rule-severity').value;
                const prompt = document.getElementById('rule-prompt').value;
                const minChangedLines = document.getElementById('min-changed-lines').value;
                const contextLines = document.getElementById('context-lines').value;
                const isActive = document.getElementById('rule-active').checked;
                const isDefault = document.getElementById('rule-default').checked;
                const categories = getSelectedCategories();

                if (!name) {
                    alert('请输入规则名称');
                    return;
                }
                if (!prompt) {
                    alert('请输入自定义提示词');
                    return;
                }

                try {
                    const response = await fetch('/api/ai-rules', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            name,
                            description: description || null,
                            scope,
                            severity_level: severity,
                            custom_prompt: prompt,
                            enabled_categories: categories,
                            min_changed_lines: minChangedLines ? parseInt(minChangedLines) : null,
                            context_lines: contextLines ? parseInt(contextLines) : null,
                            is_active: isActive,
                            is_default: isDefault,
                        })
                    });
                    
                    const data = await response.json();
                    
                    if (data.code === 200) {
                        hideCreateModal();
                        loadRules();
                    } else {
                        alert(data.message || '创建失败');
                    }
                } catch (error) {
                    console.error('Failed to create rule:', error);
                    alert('创建失败');
                }
            }

            async function viewRule(id) {
                alert('查看规则: ' + id);
            }

            async function editRule(id) {
                alert('编辑规则: ' + id);
            }

            async function setDefault(id) {
                if (!confirm('确定要将此规则设为默认规则吗？')) return;
                
                try {
                    const response = await fetch(`/api/ai-rules/${id}/set-default`, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({})
                    });
                    
                    const data = await response.json();
                    
                    if (data.code === 200) {
                        loadRules();
                    } else {
                        alert(data.message || '设置失败');
                    }
                } catch (error) {
                    console.error('Failed to set default:', error);
                    alert('设置失败');
                }
            }

            async function deleteRule(id) {
                if (!confirm('确定要删除此规则吗？')) return;
                
                try {
                    const response = await fetch(`/api/ai-rules/${id}`, {
                        method: 'DELETE'
                    });
                    
                    const data = await response.json();
                    
                    if (data.code === 200) {
                        loadRules();
                    } else {
                        alert(data.message || '删除失败');
                    }
                } catch (error) {
                    console.error('Failed to delete rule:', error);
                    alert('删除失败');
                }
            }

            loadRules();
            "#
        }
    };

    let html = render_base_template("AI规则管理", &user, content);
    Ok(HttpResponse::Ok().content_type("text/html; charset=utf-8").body(html.into_string()))
}

pub async fn ai_rules_api(
    session: Session,
    auth_service: web::Data<AuthService>,
    ai_rule_service: web::Data<AiRuleService>,
    query: web::Query<PaginationQuery>,
    filter: web::Query<AiRuleQuery>,
) -> AppResult<impl Responder> {
    let user = get_current_user(&session, &auth_service).await?;
    let pagination = query.into_inner().sanitize();
    let filter = filter.into_inner();

    let (rules, total) = ai_rule_service
        .list_rules(user.id, &filter, pagination.page, pagination.per_page)
        .await?;

    let response = PaginatedResponse::new(rules, total, pagination.page, pagination.per_page);
    Ok(HttpResponse::Ok().json(ApiResponse::success(response)))
}

pub async fn ai_rule_api(
    session: Session,
    auth_service: web::Data<AuthService>,
    ai_rule_service: web::Data<AiRuleService>,
    id: web::Path<Uuid>,
) -> AppResult<impl Responder> {
    let user = get_current_user(&session, &auth_service).await?;
    let rule = ai_rule_service.get_rule(user.id, id.into_inner()).await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(rule)))
}

pub async fn create_ai_rule_api(
    session: Session,
    auth_service: web::Data<AuthService>,
    ai_rule_service: web::Data<AiRuleService>,
    body: web::Json<CreateAiRuleRequest>,
) -> AppResult<impl Responder> {
    let user = get_current_user(&session, &auth_service).await?;
    let rule = ai_rule_service
        .create_rule(user.id, &body.into_inner())
        .await?;
    Ok(HttpResponse::Created().json(ApiResponse::success(rule)))
}

pub async fn update_ai_rule_api(
    session: Session,
    auth_service: web::Data<AuthService>,
    ai_rule_service: web::Data<AiRuleService>,
    id: web::Path<Uuid>,
    body: web::Json<UpdateAiRuleRequest>,
) -> AppResult<impl Responder> {
    let user = get_current_user(&session, &auth_service).await?;
    let rule = ai_rule_service
        .update_rule(user.id, id.into_inner(), &body.into_inner())
        .await?;
    Ok(HttpResponse::Ok().json(ApiResponse::success(rule)))
}

pub async fn delete_ai_rule_api(
    session: Session,
    auth_service: web::Data<AuthService>,
    ai_rule_service: web::Data<AiRuleService>,
    id: web::Path<Uuid>,
) -> AppResult<impl Responder> {
    let user = get_current_user(&session, &auth_service).await?;
    ai_rule_service.delete_rule(user.id, id.into_inner()).await?;
    Ok(HttpResponse::Ok().json(ApiResponse::<()>::success_no_data()))
}

pub async fn set_default_rule_api(
    session: Session,
    auth_service: web::Data<AuthService>,
    ai_rule_service: web::Data<AiRuleService>,
    id: web::Path<Uuid>,
    permission_service: web::Data<PermissionService>,
) -> AppResult<impl Responder> {
    let user = get_current_user(&session, &auth_service).await?;
    let rule_id = id.into_inner();
    
    let rule = ai_rule_service.get_rule(user.id, rule_id).await?;
    
    ai_rule_service
        .set_default_rule(user.id, rule_id, rule.organization_id)
        .await?;
    
    Ok(HttpResponse::Ok().json(ApiResponse::<()>::success_no_data()))
}

pub async fn effective_rules_api(
    session: Session,
    auth_service: web::Data<AuthService>,
    ai_rule_service: web::Data<AiRuleService>,
    repo_id: web::Path<Uuid>,
) -> AppResult<impl Responder> {
    let user = get_current_user(&session, &auth_service).await?;
    let organization_id = user.organization_id.unwrap_or_else(Uuid::nil);
    
    let rules = ai_rule_service
        .get_effective_rules(organization_id, repo_id.into_inner())
        .await?;
    
    Ok(HttpResponse::Ok().json(ApiResponse::success(rules)))
}
