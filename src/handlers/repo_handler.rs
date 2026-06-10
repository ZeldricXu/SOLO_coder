use actix_session::Session;
use actix_web::{web, HttpResponse, Responder};
use serde::Deserialize;
use uuid::Uuid;

use crate::handlers::auth_handler::SESSION_ID_KEY;
use crate::handlers::dashboard_handler::render_base_template;
use crate::models::{AuthUser, CreateRepositoryRequest, RepositoryQuery};
use crate::services::{AuthService, RepoService};
use crate::utils::{AppError, AppResult, ApiResponse, PaginationQuery};

async fn get_current_user(
    session: &Session,
    auth_service: &AuthService,
) -> AppResult<AuthUser> {
    let session_id = session
        .get::<String>(SESSION_ID_KEY)?
        .ok_or_else(|| AppError::Authentication("请先登录".to_string()))?;

    auth_service.get_current_user(&session_id).await
}

#[derive(Debug, Deserialize)]
pub struct UpdateRepositoryRequest {
    pub team_id: Option<Uuid>,
    pub is_active: Option<bool>,
}

#[derive(Debug, Deserialize)]
pub struct AssignReviewerRequest {
    pub user_id: Uuid,
}

pub async fn repos_page(
    session: Session,
    auth_service: web::Data<AuthService>,
) -> AppResult<impl Responder> {
    let user = get_current_user(&session, &auth_service).await?;

    let content = maud::html! {
        div class="header-actions" style="display: flex; gap: 12px; margin-bottom: 24px;" {
            button onclick="showAddRepoModal()" class="btn btn-primary"
                style="padding: 10px 20px; background: #667eea; color: white; border: none; border-radius: 8px; cursor: pointer; font-size: 14px; font-weight: 500;" {
                "➕ 添加仓库"
            }
            select id="filter-provider" onchange="loadRepos()"
                style="padding: 10px 16px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 14px;" {
                option value="" { "所有平台" }
                option value="github" { "GitHub" }
                option value="gitlab" { "GitLab" }
                option value="gitee" { "Gitee" }
            }
            select id="filter-status" onchange="loadRepos()"
                style="padding: 10px 16px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 14px;" {
                option value="" { "所有状态" }
                option value="true" { "已启用" }
                option value="false" { "已禁用" }
            }
        }

        div id="repos-container" class="repos-grid"
            style="display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 20px;" {
            div class="loading" { "加载仓库列表..." }
        }

        div id="add-repo-modal" class="modal"
            style="display: none; position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.5); align-items: center; justify-content: center; z-index: 1000;" {
            div class="modal-content" style="background: white; border-radius: 12px; padding: 32px; max-width: 500px; width: 90%; max-height: 90vh; overflow-y: auto;" {
                h2 style="margin-bottom: 24px; color: #1a1a2e;" { "添加仓库" }
                form id="add-repo-form" onsubmit="event.preventDefault(); addRepository();" {
                    div style="margin-bottom: 16px;" {
                        label style="display: block; margin-bottom: 8px; font-weight: 500; color: #333;" { "平台" }
                        select id="repo-provider" required style="width: 100%; padding: 10px 16px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 14px;" {
                            option value="github" { "GitHub" }
                            option value="gitlab" { "GitLab" }
                            option value="gitee" { "Gitee" }
                        }
                    }
                    div style="margin-bottom: 16px;" {
                        label style="display: block; margin-bottom: 8px; font-weight: 500; color: #333;" { "仓库ID" }
                        input type="text" id="repo-provider-id" required placeholder="例如: 123456"
                            style="width: 100%; padding: 10px 16px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 14px;";
                    }
                    div style="margin-bottom: 16px;" {
                        label style="display: block; margin-bottom: 8px; font-weight: 500; color: #333;" { "仓库名称" }
                        input type="text" id="repo-name" required placeholder="例如: my-project"
                            style="width: 100%; padding: 10px 16px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 14px;";
                    }
                    div style="margin-bottom: 16px;" {
                        label style="display: block; margin-bottom: 8px; font-weight: 500; color: #333;" { "完整名称" }
                        input type="text" id="repo-full-name" required placeholder="例如: org/my-project"
                            style="width: 100%; padding: 10px 16px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 14px;";
                    }
                    div style="display: flex; gap: 12px; justify-content: flex-end; margin-top: 24px;" {
                        button type="button" onclick="hideAddRepoModal()"
                            style="padding: 10px 20px; background: #e5e7eb; color: #333; border: none; border-radius: 8px; cursor: pointer; font-size: 14px;" {
                            "取消"
                        }
                        button type="submit" class="btn btn-primary"
                            style="padding: 10px 20px; background: #667eea; color: white; border: none; border-radius: 8px; cursor: pointer; font-size: 14px; font-weight: 500;" {
                            "添加"
                        }
                    }
                }
            }
        }

        script {
            r#"
            let currentPage = 1;
            let totalPages = 1;
            
            async function loadRepos() {
                const provider = document.getElementById('filter-provider').value;
                const isActive = document.getElementById('filter-status').value;
                
                const params = new URLSearchParams();
                params.set('page', currentPage);
                params.set('per_page', 20);
                if (provider) params.set('provider', provider);
                if (isActive) params.set('is_active', isActive);
                
                try {
                    const response = await fetch('/api/repos?' + params.toString());
                    const data = await response.json();
                    
                    if (data.code === 200 && data.data) {
                        renderRepos(data.data.items);
                        totalPages = data.data.page_info.total_pages;
                    }
                } catch (error) {
                    console.error('Failed to load repos:', error);
                }
            }
            
            function renderRepos(repos) {
                const container = document.getElementById('repos-container');
                
                if (repos.length === 0) {
                    container.innerHTML = '<div class="loading" style="grid-column: 1/-1;">暂无仓库，点击右上角"添加仓库"开始</div>';
                    return;
                }
                
                const providerColors = {
                    'github': '#333',
                    'gitlab': '#fc6d26',
                    'gitee': '#c71d23'
                };
                
                const statusColors = {
                    true: { bg: '#d1fae5', text: '#059669', label: '已启用' },
                    false: { bg: '#fee2e2', text: '#dc2626', label: '已禁用' }
                };
                
                container.innerHTML = repos.map(repo => {
                    const providerColor = providerColors[repo.provider] || '#666';
                    const status = statusColors[repo.is_active];
                    
                    return `
                        <div class="repo-card" style="background: white; border-radius: 12px; padding: 20px; box-shadow: 0 2px 8px rgba(0,0,0,0.05); transition: transform 0.2s, box-shadow 0.2s; cursor: pointer;" onclick="location.href='/repos/${repo.id}'">
                            <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px;">
                                <div>
                                    <h3 style="font-size: 16px; font-weight: 600; color: #1a1a2e; margin-bottom: 4px;">${repo.name}</h3>
                                    <p style="font-size: 13px; color: #666;">${repo.full_name}</p>
                                </div>
                                <span style="display: inline-flex; align-items: center; gap: 4px; padding: 4px 10px; border-radius: 12px; font-size: 12px; font-weight: 500; background: ${status.bg}; color: ${status.text};">
                                    ${status.label}
                                </span>
                            </div>
                            <div style="display: flex; gap: 16px; margin-bottom: 16px;">
                                <div style="display: flex; align-items: center; gap: 6px; font-size: 13px; color: #666;">
                                    <span style="width: 8px; height: 8px; border-radius: 50%; background: ${providerColor};"></span>
                                    ${repo.provider.charAt(0).toUpperCase() + repo.provider.slice(1)}
                                </div>
                                <div style="font-size: 13px; color: #666;">📥 ${repo.mr_count} MRs</div>
                                <div style="font-size: 13px; color: #f59e0b;">⏳ ${repo.pending_reviews} 待评审</div>
                            </div>
                            <div style="font-size: 12px; color: #999;">
                                ${repo.team_name ? '👥 ' + repo.team_name : '未分配团队'}
                            </div>
                        </div>
                    `;
                }).join('');
            }
            
            function showAddRepoModal() {
                document.getElementById('add-repo-modal').style.display = 'flex';
            }
            
            function hideAddRepoModal() {
                document.getElementById('add-repo-modal').style.display = 'none';
                document.getElementById('add-repo-form').reset();
            }
            
            async function addRepository() {
                const provider = document.getElementById('repo-provider').value;
                const provider_id = document.getElementById('repo-provider-id').value;
                const name = document.getElementById('repo-name').value;
                const full_name = document.getElementById('repo-full-name').value;
                
                try {
                    const response = await fetch('/api/repos', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ provider, provider_id, name, full_name })
                    });
                    
                    const data = await response.json();
                    
                    if (data.code === 200) {
                        hideAddRepoModal();
                        loadRepos();
                    } else {
                        alert('添加失败: ' + data.message);
                    }
                } catch (error) {
                    console.error('Failed to add repo:', error);
                    alert('添加失败，请重试');
                }
            }
            
            loadRepos();
            "#
        }
    };

    let html = render_base_template("仓库管理", &user, content);
    Ok(HttpResponse::Ok().content_type("text/html; charset=utf-8").body(html.into_string()))
}

pub async fn repo_detail_page(
    path: web::Path<Uuid>,
    session: Session,
    auth_service: web::Data<AuthService>,
) -> AppResult<impl Responder> {
    let user = get_current_user(&session, &auth_service).await?;
    let repo_id = path.into_inner();

    let content = maud::html! {
        div id="repo-detail" {
            div class="loading" { "加载仓库详情..." }
        }

        script {
            (format!(r#"
            const repoId = '{repo_id}';
            
            async function loadRepoDetail() {{
                try {{
                    const response = await fetch('/api/repos/' + repoId);
                    const data = await response.json();
                    
                    if (data.code === 200 && data.data) {{
                        renderRepoDetail(data.data);
                    }}
                }} catch (error) {{
                    console.error('Failed to load repo detail:', error);
                }}
            }}
            
            function renderRepoDetail(repo) {{
                const container = document.getElementById('repo-detail');
                
                const providerColors = {{
                    'github': '#333',
                    'gitlab': '#fc6d26',
                    'gitee': '#c71d23'
                }};
                
                const providerColor = providerColors[repo.provider] || '#666';
                const lastSync = repo.last_sync_at ? new Date(repo.last_sync_at).toLocaleString('zh-CN') : '从未同步';
                
                container.innerHTML = `
                    <div style="background: white; border-radius: 12px; padding: 24px; margin-bottom: 24px; box-shadow: 0 2px 8px rgba(0,0,0,0.05);">
                        <div style="display: flex; justify-content: space-between; align-items: flex-start;">
                            <div>
                                <div style="display: flex; align-items: center; gap: 12px; margin-bottom: 8px;">
                                    <span style="width: 12px; height: 12px; border-radius: 50%; background: $providerColor;"></span>
                                    <h1 style="font-size: 24px; font-weight: 600; color: #1a1a2e;">${{repo.name}}</h1>
                                    <span class="badge" style="padding: 4px 10px; border-radius: 12px; font-size: 12px; background: ${{repo.is_active ? '#d1fae5' : '#fee2e2'}}; color: ${{repo.is_active ? '#059669' : '#dc2626'}};">
                                        ${{repo.is_active ? '已启用' : '已禁用'}}
                                    </span>
                                </div>
                                <p style="font-size: 14px; color: #666; margin-bottom: 16px;">${{repo.full_name}}</p>
                                <div style="display: flex; gap: 24px; font-size: 13px; color: #666;">
                                    <span>📥 ${{repo.mr_count}} 个MR</span>
                                    <span>⏳ ${{repo.pending_reviews}} 个待评审</span>
                                    <span>🔄 最后同步: ${{lastSync}}</span>
                                </div>
                            </div>
                            <div style="display: flex; gap: 12px;">
                                <button onclick="syncRepo()" class="btn" 
                                    style="padding: 10px 20px; background: #e5e7eb; color: #333; border: none; border-radius: 8px; cursor: pointer; font-size: 14px;">
                                    🔄 同步
                                </button>
                                <a href="/repos/${{repo.id}}/settings" class="btn"
                                    style="padding: 10px 20px; background: #667eea; color: white; border: none; border-radius: 8px; cursor: pointer; font-size: 14px; text-decoration: none; display: inline-block;">
                                    ⚙️ 设置
                                </a>
                            </div>
                        </div>
                    </div>
                    
                    <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 20px; margin-bottom: 24px;">
                        <div style="background: white; border-radius: 12px; padding: 20px; text-align: center; box-shadow: 0 2px 8px rgba(0,0,0,0.05);">
                            <div style="font-size: 12px; color: #666; margin-bottom: 8px;">总MR数</div>
                            <div style="font-size: 28px; font-weight: 600; color: #1a1a2e;">${{repo.mr_count}}</div>
                        </div>
                        <div style="background: white; border-radius: 12px; padding: 20px; text-align: center; box-shadow: 0 2px 8px rgba(0,0,0,0.05);">
                            <div style="font-size: 12px; color: #666; margin-bottom: 8px;">待评审</div>
                            <div style="font-size: 28px; font-weight: 600; color: #f59e0b;">${{repo.pending_reviews}}</div>
                        </div>
                        <div style="background: white; border-radius: 12px; padding: 20px; text-align: center; box-shadow: 0 2px 8px rgba(0,0,0,0.05);">
                            <div style="font-size: 12px; color: #666; margin-bottom: 8px;">平台</div>
                            <div style="font-size: 18px; font-weight: 600; color: $providerColor;">${{repo.provider.charAt(0).toUpperCase() + repo.provider.slice(1)}}</div>
                        </div>
                    </div>
                    
                    <div style="background: white; border-radius: 12px; padding: 24px; box-shadow: 0 2px 8px rgba(0,0,0,0.05);">
                        <h2 style="font-size: 18px; font-weight: 600; margin-bottom: 16px; color: #1a1a2e;">最近合并请求</h2>
                        <div style="color: #666; text-align: center; padding: 40px;">
                            <a href="/merge-requests?repo_id=${{repo.id}}" style="color: #667eea; text-decoration: none;">查看该仓库的所有MR →</a>
                        </div>
                    </div>
                `;
            }}
            
            async function syncRepo() {{
                if (!confirm('确定要同步这个仓库吗？这可能需要一些时间。')) return;
                
                try {{
                    const response = await fetch('/api/repos/' + repoId + '/sync', {{
                        method: 'POST'
                    }});
                    
                    const data = await response.json();
                    
                    if (data.code === 200) {{
                        alert('同步已启动，请稍后刷新查看结果');
                        loadRepoDetail();
                    }} else {{
                        alert('同步失败: ' + data.message);
                    }}
                }} catch (error) {{
                    console.error('Failed to sync repo:', error);
                    alert('同步失败，请重试');
                }}
            }}
            
            loadRepoDetail();
            "#, repo_id = repo_id))
        }
    };

    let html = render_base_template("仓库详情", &user, content);
    Ok(HttpResponse::Ok().content_type("text/html; charset=utf-8").body(html.into_string()))
}

pub async fn repo_settings_page(
    path: web::Path<Uuid>,
    session: Session,
    auth_service: web::Data<AuthService>,
) -> AppResult<impl Responder> {
    let user = get_current_user(&session, &auth_service).await?;
    let repo_id = path.into_inner();

    let content = maud::html! {
        div id="repo-settings" {
            div class="loading" { "加载设置..." }
        }

        script {
            (format!(r#"
            const repoId = '{repo_id}';
            
            async function loadSettings() {{
                try {{
                    const response = await fetch('/api/repos/' + repoId);
                    const data = await response.json();
                    
                    if (data.code === 200 && data.data) {{
                        renderSettings(data.data);
                    }}
                }} catch (error) {{
                    console.error('Failed to load settings:', error);
                }}
            }}
            
            function renderSettings(repo) {{
                const container = document.getElementById('repo-settings');
                
                container.innerHTML = `
                    <div style="background: white; border-radius: 12px; padding: 24px; margin-bottom: 24px; box-shadow: 0 2px 8px rgba(0,0,0,0.05);">
                        <h2 style="font-size: 18px; font-weight: 600; margin-bottom: 20px; color: #1a1a2e;">基本设置</h2>
                        
                        <div style="margin-bottom: 20px;">
                            <label style="display: block; margin-bottom: 8px; font-weight: 500; color: #333;">仓库名称</label>
                            <input type="text" id="repo-name" value="${{repo.name}}" 
                                style="width: 100%; padding: 10px 16px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 14px;">
                        </div>
                        
                        <div style="margin-bottom: 20px;">
                            <label style="display: block; margin-bottom: 8px; font-weight: 500; color: #333;">完整名称</label>
                            <input type="text" id="repo-full-name" value="${{repo.full_name}}" 
                                style="width: 100%; padding: 10px 16px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 14px;">
                        </div>
                        
                        <div style="margin-bottom: 20px;">
                            <label style="display: flex; align-items: center; gap: 10px; cursor: pointer;">
                                <input type="checkbox" id="repo-active" ${{repo.is_active ? 'checked' : ''}} 
                                    style="width: 18px; height: 18px;">
                                <span style="font-weight: 500; color: #333;">启用仓库</span>
                            </label>
                        </div>
                        
                        <button onclick="updateSettings()" 
                            style="padding: 10px 20px; background: #667eea; color: white; border: none; border-radius: 8px; cursor: pointer; font-size: 14px; font-weight: 500;">
                            保存更改
                        </button>
                    </div>
                    
                    <div style="background: white; border-radius: 12px; padding: 24px; margin-bottom: 24px; box-shadow: 0 2px 8px rgba(0,0,0,0.05);">
                        <h2 style="font-size: 18px; font-weight: 600; margin-bottom: 20px; color: #1a1a2e;">Webhook 配置</h2>
                        <p style="color: #666; margin-bottom: 16px; font-size: 14px;">
                            Webhook URL: <code style="background: #f3f4f6; padding: 4px 8px; border-radius: 4px;">/webhook/${{repo.provider}}/${{repo.id}}</code>
                        </p>
                        <p style="color: #666; font-size: 14px;">
                            Webhook Secret: <code style="background: #f3f4f6; padding: 4px 8px; border-radius: 4px;">${{repo.webhook_secret}}</code>
                        </p>
                    </div>
                    
                    <div style="background: #fef2f2; border: 1px solid #fecaca; border-radius: 12px; padding: 24px;">
                        <h2 style="font-size: 18px; font-weight: 600; margin-bottom: 16px; color: #dc2626;">危险区域</h2>
                        <p style="color: #666; margin-bottom: 16px; font-size: 14px;">
                            删除仓库将移除所有相关数据，包括MR、评论和评审记录。此操作不可撤销。
                        </p>
                        <button onclick="deleteRepo()" 
                            style="padding: 10px 20px; background: #dc2626; color: white; border: none; border-radius: 8px; cursor: pointer; font-size: 14px; font-weight: 500;">
                            🗑️ 删除仓库
                        </button>
                    </div>
                `;
            }}
            
            async function updateSettings() {{
                const isActive = document.getElementById('repo-active').checked;
                
                try {{
                    const response = await fetch('/api/repos/' + repoId, {{
                        method: 'PUT',
                        headers: {{ 'Content-Type': 'application/json' }},
                        body: JSON.stringify({{ is_active: isActive }})
                    }});
                    
                    const data = await response.json();
                    
                    if (data.code === 200) {{
                        alert('设置已保存');
                        loadSettings();
                    }} else {{
                        alert('保存失败: ' + data.message);
                    }}
                }} catch (error) {{
                    console.error('Failed to update settings:', error);
                    alert('保存失败，请重试');
                }}
            }}
            
            async function deleteRepo() {{
                if (!confirm('确定要删除这个仓库吗？此操作不可撤销！')) return;
                
                try {{
                    const response = await fetch('/api/repos/' + repoId, {{
                        method: 'DELETE'
                    }});
                    
                    const data = await response.json();
                    
                    if (data.code === 200) {{
                        alert('仓库已删除');
                        location.href = '/repos';
                    }} else {{
                        alert('删除失败: ' + data.message);
                    }}
                }} catch (error) {{
                    console.error('Failed to delete repo:', error);
                    alert('删除失败，请重试');
                }}
            }}
            
            loadSettings();
            "#, repo_id = repo_id))
        }
    };

    let html = render_base_template("仓库设置", &user, content);
    Ok(HttpResponse::Ok().content_type("text/html; charset=utf-8").body(html.into_string()))
}

pub async fn repos_api(
    session: Session,
    auth_service: web::Data<AuthService>,
    repo_service: web::Data<RepoService>,
    query: web::Query<RepositoryQuery>,
) -> AppResult<impl Responder> {
    let user = get_current_user(&session, &auth_service).await?;
    
    let organization_id = user.organization_id.unwrap_or_else(Uuid::nil);
    let repos = repo_service.list_repos(organization_id, query.into_inner()).await?;

    Ok(HttpResponse::Ok().json(ApiResponse::success(repos)))
}

pub async fn repo_api(
    path: web::Path<Uuid>,
    session: Session,
    auth_service: web::Data<AuthService>,
    repo_service: web::Data<RepoService>,
) -> AppResult<impl Responder> {
    let _user = get_current_user(&session, &auth_service).await?;
    
    let repo_id = path.into_inner();
    let repo = repo_service.get_repo(repo_id).await?;

    Ok(HttpResponse::Ok().json(ApiResponse::success(repo)))
}

pub async fn create_repo_api(
    session: Session,
    auth_service: web::Data<AuthService>,
    repo_service: web::Data<RepoService>,
    body: web::Json<CreateRepositoryRequest>,
) -> AppResult<impl Responder> {
    let user = get_current_user(&session, &auth_service).await?;
    
    let organization_id = user.organization_id.unwrap_or_else(Uuid::nil);
    let repo = repo_service.create_repo(organization_id, body.into_inner()).await?;

    Ok(HttpResponse::Ok().json(ApiResponse::success_with_message(repo, "仓库创建成功")))
}

pub async fn update_repo_api(
    path: web::Path<Uuid>,
    session: Session,
    auth_service: web::Data<AuthService>,
    repo_service: web::Data<RepoService>,
    body: web::Json<UpdateRepositoryRequest>,
) -> AppResult<impl Responder> {
    let _user = get_current_user(&session, &auth_service).await?;
    
    let repo_id = path.into_inner();
    
    if let Some(is_active) = body.is_active {
        repo_service.set_repo_active(repo_id, is_active).await?;
    }
    
    let repo = repo_service.update_repo(repo_id, body.team_id).await?;

    Ok(HttpResponse::Ok().json(ApiResponse::success_with_message(repo, "仓库更新成功")))
}

pub async fn delete_repo_api(
    path: web::Path<Uuid>,
    session: Session,
    auth_service: web::Data<AuthService>,
    repo_service: web::Data<RepoService>,
) -> AppResult<impl Responder> {
    let _user = get_current_user(&session, &auth_service).await?;
    
    let repo_id = path.into_inner();
    repo_service.delete_repo(repo_id).await?;

    Ok(HttpResponse::Ok().json(ApiResponse::<()>::success_with_message((), "仓库删除成功")))
}

pub async fn sync_repo_api(
    path: web::Path<Uuid>,
    session: Session,
    auth_service: web::Data<AuthService>,
    repo_service: web::Data<RepoService>,
) -> AppResult<impl Responder> {
    let _user = get_current_user(&session, &auth_service).await?;
    
    let repo_id = path.into_inner();
    
    tokio::spawn(async move {
        let _ = repo_service.sync_repo(repo_id).await;
    });

    Ok(HttpResponse::Ok().json(ApiResponse::<()>::success_with_message((), "同步已启动")))
}
