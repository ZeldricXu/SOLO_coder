use actix_session::Session;
use actix_web::{web, HttpResponse, Responder};
use serde::Deserialize;
use uuid::Uuid;

use crate::handlers::auth_handler::SESSION_ID_KEY;
use crate::handlers::dashboard_handler::render_base_template;
use crate::models::{
    AuthUser, CreateCommentRequest, MergeRequestQuery, MergeRequestStatus, ReviewerAssignment,
};
use crate::services::{AuthService, MergeRequestService};
use crate::utils::{AppError, AppResult, ApiResponse, DiffFile};

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
pub struct UpdateMrStatusRequest {
    pub status: String,
}

#[derive(Debug, Deserialize)]
pub struct AssignReviewerRequest {
    pub user_id: Uuid,
}

fn get_status_style(status: &str) -> (&'static str, &'static str, &'static str) {
    match status {
        "open" => ("#dbeafe", "#2563eb", "待评审"),
        "reviewing" => ("#fef3c7", "#d97706", "评审中"),
        "approved" => ("#d1fae5", "#059669", "已批准"),
        "changes_requested" => ("#fee2e2", "#dc2626", "需修改"),
        "merged" => ("#e9d5ff", "#7c3aed", "已合并"),
        "closed" => ("#e5e7eb", "#6b7280", "已关闭"),
        _ => ("#e5e7eb", "#6b7280", status),
    }
}

pub async fn mrs_page(
    session: Session,
    auth_service: web::Data<AuthService>,
) -> AppResult<impl Responder> {
    let user = get_current_user(&session, &auth_service).await?;

    let content = maud::html! {
        div class="header-actions" style="display: flex; gap: 12px; margin-bottom: 24px; flex-wrap: wrap;" {
            select id="filter-status" onchange="loadMRs()"
                style="padding: 10px 16px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 14px;" {
                option value="" { "所有状态" }
                option value="open" { "待评审" }
                option value="reviewing" { "评审中" }
                option value="approved" { "已批准" }
                option value="changes_requested" { "需修改" }
                option value="merged" { "已合并" }
                option value="closed" { "已关闭" }
            }
            select id="filter-repo" onchange="loadMRs()"
                style="padding: 10px 16px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 14px;" {
                option value="" { "所有仓库" }
            }
            input type="text" id="search-input" placeholder="搜索MR标题..." onkeyup="if(event.key==='Enter')loadMRs()"
                style="padding: 10px 16px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 14px; min-width: 200px;";
        }

        div id="mrs-container" {
            div class="loading" { "加载合并请求列表..." }
        }

        script {
            r#"
            let currentPage = 1;
            
            async function loadRepos() {
                try {
                    const response = await fetch('/api/repos?per_page=100');
                    const data = await response.json();
                    
                    if (data.code === 200 && data.data) {
                        const select = document.getElementById('filter-repo');
                        data.data.items.forEach(repo => {
                            const option = document.createElement('option');
                            option.value = repo.id;
                            option.textContent = repo.name;
                            select.appendChild(option);
                        });
                    }
                } catch (error) {
                    console.error('Failed to load repos:', error);
                }
            }
            
            async function loadMRs() {
                const status = document.getElementById('filter-status').value;
                const repoId = document.getElementById('filter-repo').value;
                const search = document.getElementById('search-input').value;
                
                const params = new URLSearchParams();
                params.set('page', currentPage);
                params.set('per_page', 20);
                if (status) params.set('status', status);
                if (repoId) params.set('repo_id', repoId);
                
                try {
                    const response = await fetch('/api/merge-requests?' + params.toString());
                    const data = await response.json();
                    
                    if (data.code === 200 && data.data) {
                        renderMRs(data.data.items);
                    }
                } catch (error) {
                    console.error('Failed to load MRs:', error);
                }
            }
            
            function renderMRs(mrs) {
                const container = document.getElementById('mrs-container');
                
                if (mrs.length === 0) {
                    container.innerHTML = '<div class="loading" style="grid-column: 1/-1;">暂无合并请求</div>';
                    return;
                }
                
                container.innerHTML = `
                    <div style="background: white; border-radius: 12px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.05);">
                        <table style="width: 100%; border-collapse: collapse;">
                            <thead style="background: #f8f9fa;">
                                <tr>
                                    <th style="text-align: left; padding: 16px; font-size: 13px; font-weight: 600; color: #666; border-bottom: 1px solid #e5e7eb;">MR</th>
                                    <th style="text-align: left; padding: 16px; font-size: 13px; font-weight: 600; color: #666; border-bottom: 1px solid #e5e7eb;">仓库</th>
                                    <th style="text-align: left; padding: 16px; font-size: 13px; font-weight: 600; color: #666; border-bottom: 1px solid #e5e7eb;">状态</th>
                                    <th style="text-align: left; padding: 16px; font-size: 13px; font-weight: 600; color: #666; border-bottom: 1px solid #e5e7eb;">作者</th>
                                    <th style="text-align: left; padding: 16px; font-size: 13px; font-weight: 600; color: #666; border-bottom: 1px solid #e5e7eb;">评论</th>
                                    <th style="text-align: left; padding: 16px; font-size: 13px; font-weight: 600; color: #666; border-bottom: 1px solid #e5e7eb;">更新时间</th>
                                </tr>
                            </thead>
                            <tbody>
                                ${mrs.map(mr => {
                                    const (bg, color, label) = getStatusStyle(mr.status);
                                    const updatedAt = new Date(mr.updated_at).toLocaleString('zh-CN');
                                    const initial = mr.author_name.charAt(0).toUpperCase();
                                    
                                    return `
                                        <tr style="cursor: pointer; transition: background 0.2s;" onmouseover="this.style.background='#f8f9fa'" onmouseout="this.style.background='white'" onclick="location.href='/merge-requests/${mr.id}'">
                                            <td style="padding: 16px; border-bottom: 1px solid #f0f0f0;">
                                                <div style="font-weight: 500; color: #1a1a2e; margin-bottom: 4px;">${mr.title}</div>
                                                <div style="font-size: 12px; color: #999;">
                                                    <code style="background: #f3f4f6; padding: 2px 6px; border-radius: 4px;">${mr.source_branch}</code>
                                                    →
                                                    <code style="background: #f3f4f6; padding: 2px 6px; border-radius: 4px;">${mr.target_branch}</code>
                                                </div>
                                            </td>
                                            <td style="padding: 16px; border-bottom: 1px solid #f0f0f0; font-size: 13px; color: #666;">
                                                ${mr.repo_name}
                                            </td>
                                            <td style="padding: 16px; border-bottom: 1px solid #f0f0f0;">
                                                <span style="display: inline-flex; align-items: center; padding: 4px 10px; border-radius: 12px; font-size: 12px; font-weight: 500; background: ${bg}; color: ${color};">
                                                    ${label}
                                                </span>
                                            </td>
                                            <td style="padding: 16px; border-bottom: 1px solid #f0f0f0;">
                                                <div style="display: flex; align-items: center; gap: 8px;">
                                                    <div style="width: 28px; height: 28px; border-radius: 50%; background: #667eea; color: white; display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 600;">
                                                        ${mr.author_avatar ? `<img src="${mr.author_avatar}" style="width: 100%; height: 100%; border-radius: 50%; object-fit: cover;">` : initial}
                                                    </div>
                                                    <span style="font-size: 13px; color: #333;">${mr.author_name}</span>
                                                </div>
                                            </td>
                                            <td style="padding: 16px; border-bottom: 1px solid #f0f0f0; font-size: 13px; color: #666;">
                                                💬 ${mr.comment_count}
                                                ${mr.unresolved_comment_count > 0 ? `<span style="color: #dc2626; margin-left: 4px;">(${mr.unresolved_comment_count} 未解决)</span>` : ''}
                                            </td>
                                            <td style="padding: 16px; border-bottom: 1px solid #f0f0f0; font-size: 12px; color: #999;">
                                                ${updatedAt}
                                            </td>
                                        </tr>
                                    `;
                                }).join('')}
                            </tbody>
                        </table>
                    </div>
                `;
            }
            
            loadRepos();
            loadMRs();
            "#
        }
    };

    let html = render_base_template("合并请求", &user, content);
    Ok(HttpResponse::Ok().content_type("text/html; charset=utf-8").body(html.into_string()))
}

pub async fn mr_detail_page(
    path: web::Path<Uuid>,
    session: Session,
    auth_service: web::Data<AuthService>,
) -> AppResult<impl Responder> {
    let user = get_current_user(&session, &auth_service).await?;
    let mr_id = path.into_inner();
    let mr_id_str = mr_id.to_string();

    let content = maud::html! {
        div id="mr-detail" {
            div class="loading" { "加载MR详情..." }
        }

        script {
            "const mrId = '" (mr_id_str) "';"
            r#"
            let currentMr = null;
            
            async function loadMRDetail() {
                try {
                    const [mrResponse, diffResponse] = await Promise.all([
                        fetch('/api/merge-requests/' + mrId),
                        fetch('/api/merge-requests/' + mrId + '/diff')
                    ]);
                    
                    const mrData = await mrResponse.json();
                    const diffData = await diffResponse.json();
                    
                    if (mrData.code === 200 && mrData.data) {
                        currentMr = mrData.data;
                        renderMRDetail(mrData.data);
                    }
                    
                    if (diffData.code === 200 && diffData.data) {
                        renderDiff(diffData.data);
                    }
                } catch (error) {
                    console.error('Failed to load MR detail:', error);
                }
            }
            
            function renderMRDetail(mr) {
                const container = document.getElementById('mr-detail');
                const [bg, color, label] = getStatusStyle(mr.status);
                const createdAt = new Date(mr.created_at).toLocaleString('zh-CN');
                const updatedAt = new Date(mr.updated_at).toLocaleString('zh-CN');
                const authorInitial = mr.author_name.charAt(0).toUpperCase();
                
                let descriptionHtml = '';
                if (mr.description) {
                    descriptionHtml = `
                        <div style="background: #f8f9fa; border-radius: 8px; padding: 16px; margin-top: 16px;">
                            <div style="white-space: pre-wrap; color: #333; line-height: 1.6;">${mr.description}</div>
                        </div>
                    `;
                }
                
                let authorAvatarHtml = authorInitial;
                if (mr.author_avatar) {
                    authorAvatarHtml = `<img src="${mr.author_avatar}" style="width: 100%; height: 100%; border-radius: 50%; object-fit: cover;">`;
                }
                
                container.innerHTML = `
                    <div style="background: white; border-radius: 12px; padding: 24px; margin-bottom: 24px; box-shadow: 0 2px 8px rgba(0,0,0,0.05);">
                        <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 20px;">
                            <div style="flex: 1;">
                                <div style="display: flex; align-items: center; gap: 12px; margin-bottom: 8px; flex-wrap: wrap;">
                                    <h1 style="font-size: 22px; font-weight: 600; color: #1a1a2e;">${mr.title}</h1>
                                    <span style="display: inline-flex; align-items: center; padding: 4px 10px; border-radius: 12px; font-size: 12px; font-weight: 500; background: ${bg}; color: ${color};">
                                        ${label}
                                    </span>
                                </div>
                                <div style="display: flex; align-items: center; gap: 12px; font-size: 13px; color: #666; flex-wrap: wrap;">
                                    <div style="display: flex; align-items: center; gap: 6px;">
                                        <div style="width: 20px; height: 20px; border-radius: 50%; background: #667eea; color: white; display: flex; align-items: center; justify-content: center; font-size: 10px; font-weight: 600;">
                                            ${authorAvatarHtml}
                                        </div>
                                        <span>${mr.author_name}</span>
                                    </div>
                                    <span>•</span>
                                    <span>${mr.repo_name}</span>
                                    <span>•</span>
                                    <code style="background: #f3f4f6; padding: 2px 6px; border-radius: 4px;">${mr.source_branch}</code>
                                    <span>→</span>
                                    <code style="background: #f3f4f6; padding: 2px 6px; border-radius: 4px;">${mr.target_branch}</code>
                                </div>
                            </div>
                            <div style="display: flex; gap: 12px; flex-wrap: wrap;">
                                <select onchange="updateMRStatus(this.value)" 
                                    style="padding: 8px 12px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 13px;">
                                    <option value="" disabled selected>更新状态</option>
                                    <option value="open">待评审</option>
                                    <option value="reviewing">评审中</option>
                                    <option value="approved">已批准</option>
                                    <option value="changes_requested">需修改</option>
                                    <option value="merged">已合并</option>
                                    <option value="closed">已关闭</option>
                                </select>
                                <button onclick="showAssignReviewerModal()" 
                                    style="padding: 8px 16px; background: #667eea; color: white; border: none; border-radius: 8px; cursor: pointer; font-size: 13px;">
                                    👤 分配评审人
                                </button>
                            </div>
                        </div>
                        ${descriptionHtml}
                        <div style="display: flex; gap: 24px; margin-top: 20px; padding-top: 20px; border-top: 1px solid #f0f0f0; flex-wrap: wrap;">
                            <div style="font-size: 13px; color: #666;">💬 ${mr.comment_count} 条评论</div>
                            <div style="font-size: 13px; color: #dc2626;">⚠️ ${mr.unresolved_comment_count} 条未解决</div>
                            <div style="font-size: 13px; color: #666;">🐛 ${mr.issue_count} 个问题</div>
                            <div style="font-size: 13px; color: #666;">📅 创建于 ${createdAt}</div>
                            <div style="font-size: 13px; color: #666;">🔄 更新于 ${updatedAt}</div>
                        </div>
                    </div>
                    
                    <div style="display: grid; grid-template-columns: 2fr 1fr; gap: 24px;">
                        <div>
                            <h2 style="font-size: 18px; font-weight: 600; margin-bottom: 16px; color: #1a1a2e;">代码变更</h2>
                            <div id="diff-container" style="background: white; border-radius: 12px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.05);">
                                <div class="loading">加载Diff数据...</div>
                            </div>
                        </div>
                        
                        <div>
                            <h2 style="font-size: 18px; font-weight: 600; margin-bottom: 16px; color: #1a1a2e;">评论</h2>
                            <div id="comments-container" style="background: white; border-radius: 12px; padding: 16px; box-shadow: 0 2px 8px rgba(0,0,0,0.05);">
                                <div style="margin-bottom: 16px;">
                                    <textarea id="comment-content" placeholder="添加评论..." rows="3"
                                        style="width: 100%; padding: 12px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 13px; resize: vertical; font-family: inherit;"></textarea>
                                    <button onclick="addComment()" 
                                        style="margin-top: 8px; padding: 8px 16px; background: #667eea; color: white; border: none; border-radius: 8px; cursor: pointer; font-size: 13px;">
                                        💬 发表评论
                                    </button>
                                </div>
                                <div id="comments-list">
                                    <div class="loading">加载评论...</div>
                                </div>
                            </div>
                        </div>
                    </div>
                    
                    <div id="assign-reviewer-modal" class="modal"
                        style="display: none; position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.5); align-items: center; justify-content: center; z-index: 1000;">
                        <div class="modal-content" style="background: white; border-radius: 12px; padding: 24px; max-width: 400px; width: 90%;">
                            <h3 style="margin-bottom: 16px; color: #1a1a2e;">分配评审人</h3>
                            <div style="margin-bottom: 16px;">
                                <label style="display: block; margin-bottom: 8px; font-weight: 500; color: #333;">选择用户</label>
                                <select id="reviewer-select" style="width: 100%; padding: 10px 16px; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 14px;">
                                    <option value="" disabled selected>选择评审人...</option>
                                </select>
                            </div>
                            <div style="display: flex; gap: 12px; justify-content: flex-end;">
                                <button onclick="hideAssignReviewerModal()"
                                    style="padding: 8px 16px; background: #e5e7eb; color: #333; border: none; border-radius: 8px; cursor: pointer; font-size: 13px;">
                                    取消
                                </button>
                                <button onclick="assignReviewer()"
                                    style="padding: 8px 16px; background: #667eea; color: white; border: none; border-radius: 8px; cursor: pointer; font-size: 13px;">
                                    分配
                                </button>
                            </div>
                        </div>
                    </div>
                `;
                
                loadComments();
                loadUsers();
            }
            
            function renderDiff(files) {
                const container = document.getElementById('diff-container');
                
                if (files.length === 0) {
                    container.innerHTML = '<div class="loading">暂无变更</div>';
                    return;
                }
                
                container.innerHTML = files.map(file => {
                    const additions = file.hunks.reduce((sum, h) => 
                        sum + h.lines.filter(l => l.line_type === 'new').length, 0);
                    const deletions = file.hunks.reduce((sum, h) => 
                        sum + h.lines.filter(l => l.line_type === 'old').length, 0);
                    
                    let hunksHtml = file.hunks.map(hunk => {
                        let linesHtml = hunk.lines.map(line => {
                            let bgColor = '#fff';
                            let prefix = ' ';
                            if (line.line_type === 'new') { bgColor = '#dcfce7'; prefix = '+'; }
                            else if (line.line_type === 'old') { bgColor = '#fee2e2'; prefix = '-'; }
                            
                            return `
                                <div style="display: flex; font-family: 'SF Mono', 'Consolas', monospace; font-size: 12px; line-height: 1.6; background: ${bgColor};">
                                    <div style="width: 50px; padding: 0 12px; text-align: right; color: #999; user-select: none; border-right: 1px solid #f0f0f0;">
                                        ${line.old_line_no || ''}
                                    </div>
                                    <div style="width: 50px; padding: 0 12px; text-align: right; color: #999; user-select: none; border-right: 1px solid #f0f0f0;">
                                        ${line.new_line_no || ''}
                                    </div>
                                    <div style="width: 20px; text-align: center; user-select: none;">${prefix}</div>
                                    <pre style="flex: 1; padding: 0 12px; margin: 0; white-space: pre-wrap; word-break: break-all;">${line.content || ''}</pre>
                                </div>
                            `;
                        }).join('');
                        
                        return `
                            <div style="border-bottom: 1px solid #f0f0f0;">
                                <div style="background: #eff6ff; padding: 4px 16px; font-size: 12px; color: #1e40af; font-family: monospace;">
                                    ${hunk.header}
                                </div>
                                ${linesHtml}
                            </div>
                        `;
                    }).join('');
                    
                    return `
                        <div style="border-bottom: 1px solid #f0f0f0;">
                            <div style="display: flex; justify-content: space-between; align-items: center; padding: 12px 16px; background: #f8f9fa; cursor: pointer;" onclick="toggleFile(this)">
                                <div style="display: flex; align-items: center; gap: 8px;">
                                    <span style="font-size: 12px;">▼</span>
                                    <span style="font-weight: 500; color: #1a1a2e;">${file.new_path || file.old_path}</span>
                                </div>
                                <div style="display: flex; gap: 12px; font-size: 12px;">
                                    <span style="color: #22c55e;">+${additions}</span>
                                    <span style="color: #ef4444;">-${deletions}</span>
                                </div>
                            </div>
                            <div class="file-content" style="display: block; overflow-x: auto;">
                                ${hunksHtml}
                            </div>
                        </div>
                    `;
                }).join('');
            }
            
            function toggleFile(header) {
                const content = header.nextElementSibling;
                const icon = header.querySelector('span:first-child');
                if (content.style.display === 'none') {
                    content.style.display = 'block';
                    icon.textContent = '▼';
                } else {
                    content.style.display = 'none';
                    icon.textContent = '▶';
                }
            }
            
            async function loadComments() {
                try {
                    const response = await fetch('/api/merge-requests/' + mrId + '/comments');
                    const data = await response.json();
                    
                    if (data.code === 200 && data.data) {
                        renderComments(data.data);
                    }
                } catch (error) {
                    console.error('Failed to load comments:', error);
                }
            }
            
            function renderComments(comments) {
                const container = document.getElementById('comments-list');
                
                if (comments.length === 0) {
                    container.innerHTML = '<div style="text-align: center; padding: 20px; color: #999;">暂无评论</div>';
                    return;
                }
                
                container.innerHTML = comments.map(comment => {
                    const time = new Date(comment.created_at).toLocaleString('zh-CN');
                    const initial = comment.author_name.charAt(0).toUpperCase();
                    
                    let avatarHtml = initial;
                    if (comment.author_avatar) {
                        avatarHtml = `<img src="${comment.author_avatar}" style="width: 100%; height: 100%; border-radius: 50%; object-fit: cover;">`;
                    }
                    
                    let fileHtml = '';
                    if (comment.file_path) {
                        fileHtml = `
                            <div style="font-size: 11px; color: #666; margin-top: 2px;">
                                📄 ${comment.file_path}${comment.line_no ? ':' + comment.line_no : ''}
                            </div>
                        `;
                    }
                    
                    let resolvedHtml = '';
                    if (!comment.resolved) {
                        resolvedHtml = `
                            <div style="margin-left: 32px; margin-top: 8px;">
                                <span style="font-size: 11px; padding: 2px 8px; border-radius: 10px; background: #fee2e2; color: #dc2626;">未解决</span>
                            </div>
                        `;
                    }
                    
                    return `
                        <div style="padding: 12px 0; border-bottom: 1px solid #f0f0f0;">
                            <div style="display: flex; gap: 8px; margin-bottom: 8px;">
                                <div style="width: 24px; height: 24px; border-radius: 50%; background: #667eea; color: white; display: flex; align-items: center; justify-content: center; font-size: 10px; font-weight: 600; flex-shrink: 0;">
                                    ${avatarHtml}
                                </div>
                                <div style="flex: 1;">
                                    <div style="display: flex; justify-content: space-between; align-items: center;">
                                        <span style="font-weight: 500; font-size: 13px; color: #1a1a2e;">${comment.author_name}</span>
                                        <span style="font-size: 11px; color: #999;">${time}</span>
                                    </div>
                                    ${fileHtml}
                                </div>
                            </div>
                            <div style="margin-left: 32px; font-size: 13px; color: #333; line-height: 1.6; white-space: pre-wrap;">${comment.content}</div>
                            ${resolvedHtml}
                        </div>
                    `;
                }).join('');
            }
            
            async function addComment() {
                const content = document.getElementById('comment-content').value.trim();
                if (!content) return;
                
                try {
                    const response = await fetch('/api/merge-requests/' + mrId + '/comments', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ content })
                    });
                    
                    const data = await response.json();
                    
                    if (data.code === 200) {
                        document.getElementById('comment-content').value = '';
                        loadComments();
                    } else {
                        alert('评论失败: ' + data.message);
                    }
                } catch (error) {
                    console.error('Failed to add comment:', error);
                    alert('评论失败，请重试');
                }
            }
            
            async function updateMRStatus(status) {
                if (!confirm('确定要更新状态吗？')) return;
                
                try {
                    const response = await fetch('/api/merge-requests/' + mrId + '/status', {
                        method: 'PUT',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ status })
                    });
                    
                    const data = await response.json();
                    
                    if (data.code === 200) {
                        alert('状态已更新');
                        loadMRDetail();
                    } else {
                        alert('更新失败: ' + data.message);
                    }
                } catch (error) {
                    console.error('Failed to update status:', error);
                    alert('更新失败，请重试');
                }
            }
            
            async function loadUsers() {
                try {
                    const response = await fetch('/api/users');
                    const data = await response.json();
                    
                    if (data.code === 200 && data.data) {
                        const select = document.getElementById('reviewer-select');
                        data.data.items.forEach(user => {
                            const option = document.createElement('option');
                            option.value = user.id;
                            option.textContent = user.username;
                            select.appendChild(option);
                        });
                    }
                } catch (error) {
                    console.error('Failed to load users:', error);
                }
            }
            
            function showAssignReviewerModal() {
                document.getElementById('assign-reviewer-modal').style.display = 'flex';
            }
            
            function hideAssignReviewerModal() {
                document.getElementById('assign-reviewer-modal').style.display = 'none';
            }
            
            async function assignReviewer() {
                const userId = document.getElementById('reviewer-select').value;
                if (!userId) {
                    alert('请选择评审人');
                    return;
                }
                
                try {
                    const response = await fetch('/api/merge-requests/' + mrId + '/reviewers', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ user_id: userId })
                    });
                    
                    const data = await response.json();
                    
                    if (data.code === 200) {
                        alert('评审人已分配');
                        hideAssignReviewerModal();
                    } else {
                        alert('分配失败: ' + data.message);
                    }
                } catch (error) {
                    console.error('Failed to assign reviewer:', error);
                    alert('分配失败，请重试');
                }
            }
            
            loadMRDetail();
            "#
        }
    };

    let html = render_base_template("MR详情", &user, content);
    Ok(HttpResponse::Ok().content_type("text/html; charset=utf-8").body(html.into_string()))
}

pub async fn mrs_api(
    session: Session,
    auth_service: web::Data<AuthService>,
    mr_service: web::Data<MergeRequestService>,
    query: web::Query<MergeRequestQuery>,
) -> AppResult<impl Responder> {
    let _user = get_current_user(&session, &auth_service).await?;
    
    let mrs = mr_service.list_mrs(query.into_inner()).await?;

    Ok(HttpResponse::Ok().json(ApiResponse::success(mrs)))
}

pub async fn mr_api(
    path: web::Path<Uuid>,
    session: Session,
    auth_service: web::Data<AuthService>,
    mr_service: web::Data<MergeRequestService>,
) -> AppResult<impl Responder> {
    let _user = get_current_user(&session, &auth_service).await?;
    
    let mr_id = path.into_inner();
    let mr = mr_service.get_mr(mr_id).await?;

    Ok(HttpResponse::Ok().json(ApiResponse::success(mr)))
}

pub async fn mr_diff_api(
    path: web::Path<Uuid>,
    session: Session,
    auth_service: web::Data<AuthService>,
    mr_service: web::Data<MergeRequestService>,
) -> AppResult<impl Responder> {
    let _user = get_current_user(&session, &auth_service).await?;
    
    let mr_id = path.into_inner();
    let diff = mr_service.get_mr_diff(mr_id).await?;

    Ok(HttpResponse::Ok().json(ApiResponse::success(diff)))
}

pub async fn create_comment_api(
    path: web::Path<Uuid>,
    session: Session,
    auth_service: web::Data<AuthService>,
    mr_service: web::Data<MergeRequestService>,
    body: web::Json<CreateCommentRequest>,
) -> AppResult<impl Responder> {
    let user = get_current_user(&session, &auth_service).await?;
    
    let mr_id = path.into_inner();
    let comment = mr_service.add_comment(mr_id, user.id, body.into_inner()).await?;

    Ok(HttpResponse::Ok().json(ApiResponse::success_with_message(comment, "评论发表成功")))
}

pub async fn update_mr_status_api(
    path: web::Path<Uuid>,
    session: Session,
    auth_service: web::Data<AuthService>,
    mr_service: web::Data<MergeRequestService>,
    body: web::Json<UpdateMrStatusRequest>,
) -> AppResult<impl Responder> {
    let _user = get_current_user(&session, &auth_service).await?;
    
    let mr_id = path.into_inner();
    let status = MergeRequestStatus::from_str(&body.status)
        .ok_or_else(|| AppError::Validation(format!("无效的状态: {}", body.status)))?;
    
    let mr = mr_service.update_mr_status(mr_id, status).await?;

    Ok(HttpResponse::Ok().json(ApiResponse::success_with_message(mr, "状态更新成功")))
}

pub async fn assign_reviewer_api(
    path: web::Path<Uuid>,
    session: Session,
    auth_service: web::Data<AuthService>,
    mr_service: web::Data<MergeRequestService>,
    body: web::Json<AssignReviewerRequest>,
) -> AppResult<impl Responder> {
    let _user = get_current_user(&session, &auth_service).await?;
    
    let mr_id = path.into_inner();
    let assignment = mr_service.assign_reviewer(mr_id, body.user_id).await?;

    Ok(HttpResponse::Ok().json(ApiResponse::success_with_message(assignment, "评审人分配成功")))
}
