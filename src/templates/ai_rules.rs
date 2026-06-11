use maud::{html, Markup, PreEscaped};
use uuid::Uuid;
use crate::models::{AiRule, Repository};
use crate::templates::layout::LayoutContext;
use crate::templates::layout::base_layout;
use crate::templates::components::{
    card, modal, input_field, select_field, textarea_field, pagination, PaginationData,
};

pub struct AiRulesPageContext {
    pub organization_id: Uuid,
    pub rules: Vec<AiRule>,
    pub repos: Vec<Repository>,
    pub filter_scope: Option<String>,
    pub filter_active: Option<bool>,
    pub total: i64,
    pub page: i32,
    pub per_page: i32,
}

fn scope_badge(scope: &str) -> Markup {
    let (label, class) = match scope {
        "organization" => ("组织级", "bg-purple-500/20 text-purple-400 border-purple-500/30"),
        "repository" => ("仓库级", "bg-blue-500/20 text-blue-400 border-blue-500/30"),
        _ => ("未知", "bg-gray-500/20 text-gray-400 border-gray-500/30"),
    };
    html! {
        span class={"inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border " (class)} {
            (label)
        }
    }
}

fn severity_badge(severity: &str) -> Markup {
    let (label, class) = match severity {
        "strict" => ("严格", "bg-red-500/20 text-red-400 border-red-500/30"),
        "normal" => ("标准", "bg-blue-500/20 text-blue-400 border-blue-500/30"),
        "loose" => ("宽松", "bg-emerald-500/20 text-emerald-400 border-emerald-500/30"),
        _ => ("未知", "bg-gray-500/20 text-gray-400 border-gray-500/30"),
    };
    html! {
        span class={"inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border " (class)} {
            (label)
        }
    }
}

fn toggle_switch(rule_id: &Uuid, is_active: bool) -> Markup {
    html! {
        label class="relative inline-flex items-center cursor-pointer" {
            input type="checkbox" class="sr-only peer" checked[is_active]
                onchange={"toggleRuleActive('" (rule_id) "', this.checked)"};
            div class={
                "w-11 h-6 rounded-full peer transition-colors duration-300"
                @if is_active { "bg-emerald-500" }
                @else { "bg-gray-600" }
                " peer-checked:after:translate-x-full after:content-[''] after:absolute after:top-0.5 after:left-[2px]"
                " after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all after:shadow-md"
            } {}
        }
    }
}

fn get_repo_name(rule: &AiRule, repos: &[Repository]) -> Option<String> {
    rule.repo_id.and_then(|rid| {
        repos.iter().find(|r| r.id == rid).map(|r| r.name.clone())
    })
}

fn rule_card(rule: &AiRule, repos: &[Repository]) -> Markup {
    let repo_name = get_repo_name(rule, repos);
    let has_repo = repo_name.is_some();
    let repo_display = repo_name.unwrap_or_default();
    let has_desc = rule.description.is_some();
    let desc_display = rule.description.clone().unwrap_or_default();
    let is_default = rule.is_default;

    html! {
        div class="bg-[#1E293B] border border-[#334155] rounded-xl p-5 hover:border-[#3B82F6]/50 transition-all duration-300" {
            div class="flex items-start justify-between mb-3" {
                div class="flex-1 min-w-0" {
                    div class="flex items-center gap-2 mb-2" {
                        h3 class="text-base font-semibold text-white truncate" { (rule.name) }
                        @if is_default {
                            span class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-amber-500/20 text-amber-400 border border-amber-500/30" {
                                "默认"
                            }
                        }
                    }
                    @if has_desc {
                        p class="text-sm text-[#94A3B8] line-clamp-2 mb-3" { (desc_display) }
                    }
                    div class="flex items-center gap-2 flex-wrap" {
                        (scope_badge(&rule.scope))
                        (severity_badge(&rule.severity_level))
                        @if has_repo {
                            span class="text-xs text-[#64748B]" { "📦 " (repo_display) }
                        }
                    }
                }
                div class="ml-4 flex-shrink-0" {
                    (toggle_switch(&rule.id, rule.is_active))
                }
            }

            div class="flex items-center gap-2 pt-3 border-t border-[#334155]/50 mt-3" {
                button
                    class="flex-1 px-3 py-1.5 text-sm text-[#94A3B8] hover:text-white hover:bg-white/5 rounded-lg transition-colors"
                    onclick={"openEditRuleModal('" (rule.id) "')"}
                {
                    "✏️ 编辑"
                }
                @if !is_default {
                    button
                        class="flex-1 px-3 py-1.5 text-sm text-[#94A3B8] hover:text-amber-400 hover:bg-amber-500/10 rounded-lg transition-colors"
                        onclick={"setDefaultRule('" (rule.id) "')"}
                    {
                        "⭐ 设为默认"
                    }
                }
                button
                    class="flex-1 px-3 py-1.5 text-sm text-[#94A3B8] hover:text-red-400 hover:bg-red-500/10 rounded-lg transition-colors"
                    onclick={"deleteRule('" (rule.id) "')"}
                {
                    "🗑️ 删除"
                }
            }
        }
    }
}

fn build_repo_options(repos: &[Repository]) -> Vec<(String, String)> {
    repos.iter().map(|r| (r.id.to_string(), r.name.clone())).collect()
}

fn rule_modal_content(repos: &[Repository]) -> Markup {
    let scope_options = vec![
        ("organization".to_string(), "组织级".to_string()),
        ("repository".to_string(), "仓库级".to_string()),
    ];
    let severity_options = vec![
        ("strict".to_string(), "严格 - 红色".to_string()),
        ("normal".to_string(), "标准 - 蓝色".to_string()),
        ("loose".to_string(), "宽松 - 绿色".to_string()),
    ];
    let repo_options = build_repo_options(repos);
    let categories = [
        ("code_style", "代码风格"),
        ("bug_patterns", "Bug模式"),
        ("security", "安全"),
        ("performance", "性能"),
        ("best_practices", "最佳实践"),
        ("maintainability", "可维护性"),
    ];

    html! {
        form id="ruleForm" onsubmit="submitRuleForm(event)" {
            input type="hidden" id="ruleId" name="id" value="";

            div class="space-y-4" {
                div {
                    label class="block text-sm font-medium text-[#94A3B8] mb-1" { "规则名称" }
                    (input_field("name", "", "输入规则名称", "text", true))
                }

                div {
                    label class="block text-sm font-medium text-[#94A3B8] mb-1" { "描述" }
                    (textarea_field("description", "", "输入规则描述", 2, false))
                }

                div class="grid grid-cols-2 gap-4" {
                    div {
                        label class="block text-sm font-medium text-[#94A3B8] mb-1" { "作用域" }
                        (select_field("scope", scope_options, Some("organization")))
                    }
                    div id="repoSelectContainer" class="hidden" {
                        label class="block text-sm font-medium text-[#94A3B8] mb-1" { "选择仓库" }
                        (select_field("repo_id", repo_options, None))
                    }
                }

                div {
                    label class="block text-sm font-medium text-[#94A3B8] mb-1" { "严重程度" }
                    (select_field("severity_level", severity_options, Some("normal")))
                }

                div {
                    label class="block text-sm font-medium text-[#94A3B8] mb-2" { "启用的检查类别" }
                    div class="grid grid-cols-2 gap-2" {
                        @for (value, label) in categories.iter() {
                            label class="flex items-center gap-2 p-2 bg-[#0F172A] border border-[#334155] rounded-lg cursor-pointer hover:border-[#3B82F6]/50 transition-colors" {
                                input type="checkbox" name="enabled_categories" value=(value) class="rounded border-[#475569] bg-[#1E293B] text-[#3B82F6] focus:ring-[#3B82F6]";
                                span class="text-sm text-[#CBD5E1]" { (label) }
                            }
                        }
                    }
                }

                div class="grid grid-cols-2 gap-4" {
                    div {
                        label class="block text-sm font-medium text-[#94A3B8] mb-1" { "最小变更行数" }
                        (input_field("min_changed_lines", "", "0表示不限制", "number", false))
                    }
                    div {
                        label class="block text-sm font-medium text-[#94A3B8] mb-1" { "上下文行数" }
                        (input_field("context_lines", "", "默认5行", "number", false))
                    }
                }

                div {
                    label class="block text-sm font-medium text-[#94A3B8] mb-1" { "自定义 Prompt" }
                    (textarea_field("custom_prompt", "", "输入自定义 AI 评审 prompt...", 5, false))
                }

                div class="flex items-center justify-between p-3 bg-[#0F172A] rounded-lg border border-[#334155]" {
                    span class="text-sm text-[#CBD5E1]" { "启用规则" }
                    label class="relative inline-flex items-center cursor-pointer" {
                        input type="checkbox" name="is_active" class="sr-only peer" checked;
                        div class="w-11 h-6 bg-emerald-500 rounded-full peer transition-colors duration-300
                            peer-checked:after:translate-x-full after:content-[''] after:absolute after:top-0.5 after:left-[2px]
                            after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all after:shadow-md" {}
                    }
                }
            }

            div class="flex justify-end gap-3 mt-6 pt-4 border-t border-[#334155]" {
                button type="button" onclick="closeRuleModal()" class="px-4 py-2 text-[#94A3B8] hover:text-white hover:bg-white/5 rounded-lg transition-colors" {
                    "取消"
                }
                button type="submit" class="px-4 py-2 bg-[#3B82F6] hover:bg-[#2563EB] text-white rounded-lg font-medium transition-colors" {
                    "保存"
                }
            }
        }
    }
}

pub fn ai_rules_page(ctx: LayoutContext, page_ctx: &AiRulesPageContext) -> Markup {
    let total_pages = (page_ctx.total as f64 / page_ctx.per_page as f64).ceil() as u32;
    let pagination_data = PaginationData {
        current_page: page_ctx.page as u32,
        total_pages: total_pages.max(1),
        base_url: "/ai-rules".to_string(),
    };

    let rules_empty = page_ctx.rules.is_empty();

    base_layout(ctx, html! {
        style { (ai_rules_styles()) }

        div class="mb-6" {
            div class="flex items-center justify-between mb-4" {
                div {
                    h1 class="text-2xl font-bold text-white mb-1" { "AI 评审规则" }
                    p class="text-[#94A3B8] text-sm" { "管理组织内的 AI 代码评审规则模板" }
                }
                button
                    onclick="openNewRuleModal()"
                    class="px-4 py-2 bg-[#3B82F6] hover:bg-[#2563EB] text-white rounded-lg font-medium transition-colors flex items-center gap-2"
                {
                    "➕"
                    "新建规则"
                }
            }

            div class="flex flex-wrap items-center gap-4 p-4 bg-[#1E293B] border border-[#334155] rounded-xl" {
                div class="flex items-center gap-2" {
                    span class="text-sm text-[#94A3B8]" { "作用域：" }
                    div class="flex bg-[#0F172A] rounded-lg p-1" {
                        @let scope_filters = [
                            ("all", "全部"),
                            ("organization", "组织级"),
                            ("repository", "仓库级"),
                        ];
                        @for (value, label) in scope_filters.iter() {
                            a
                                href={"?scope=" (value)}
                                class={
                                    "px-3 py-1.5 text-sm rounded-md transition-colors"
                                    @if page_ctx.filter_scope.as_deref() == Some(*value) || (value == "all" && page_ctx.filter_scope.is_none()) {
                                        "bg-[#3B82F6] text-white"
                                    } @else {
                                        "text-[#94A3B8] hover:text-white"
                                    }
                                }
                            {
                                (label)
                            }
                        }
                    }
                }

                div class="flex items-center gap-2" {
                    span class="text-sm text-[#94A3B8]" { "状态：" }
                    div class="flex bg-[#0F172A] rounded-lg p-1" {
                        @let active_filters = [
                            ("all", "全部"),
                            ("active", "已启用"),
                            ("inactive", "已禁用"),
                        ];
                        @for (value, label) in active_filters.iter() {
                            a
                                href={"?active=" (value)}
                                class={
                                    "px-3 py-1.5 text-sm rounded-md transition-colors"
                                    @if is_active_filter_selected(value, &page_ctx.filter_active) {
                                        "bg-[#3B82F6] text-white"
                                    } @else {
                                        "text-[#94A3B8] hover:text-white"
                                    }
                                }
                            {
                                (label)
                            }
                        }
                    }
                }

                div class="ml-auto text-sm text-[#64748B]" {
                    "共 " (page_ctx.total) " 条规则"
                }
            }
        }

        @if rules_empty {
            div class="flex flex-col items-center justify-center py-20 text-center" {
                div class="text-6xl mb-4" { "📋" }
                h3 class="text-lg font-medium text-white mb-2" { "暂无规则" }
                p class="text-[#94A3B8] text-sm mb-6" { "创建第一个 AI 评审规则来开始自动化代码审查" }
                button onclick="openNewRuleModal()" class="px-4 py-2 bg-[#3B82F6] hover:bg-[#2563EB] text-white rounded-lg font-medium transition-colors" {
                    "新建规则"
                }
            }
        } @else {
            div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 mb-6" {
                @for rule in &page_ctx.rules {
                    (rule_card(rule, &page_ctx.repos))
                }
            }

            (pagination(pagination_data))
        }

        (modal("ruleModal", "新建规则", rule_modal_content(&page_ctx.repos)))

        script {
            (PreEscaped(r#"
                function openNewRuleModal() {
                    document.getElementById('ruleForm').reset();
                    document.getElementById('ruleId').value = '';
                    document.querySelector('#ruleModal h3').textContent = '新建规则';
                    document.getElementById('ruleModal').classList.remove('hidden');
                    updateRepoSelectVisibility();
                }

                function openEditRuleModal(ruleId) {
                    fetch('/api/ai-rules/' + ruleId)
                        .then(r => r.json())
                        .then(rule => {
                            document.getElementById('ruleId').value = rule.id;
                            document.querySelector('#ruleModal h3').textContent = '编辑规则';
                            document.querySelector('[name="name"]').value = rule.name;
                            document.querySelector('[name="description"]').value = rule.description || '';
                            document.querySelector('[name="scope"]').value = rule.scope;
                            document.querySelector('[name="repo_id"]').value = rule.repo_id || '';
                            document.querySelector('[name="severity_level"]').value = rule.severity_level;
                            document.querySelector('[name="custom_prompt"]').value = rule.custom_prompt;
                            document.querySelector('[name="min_changed_lines"]').value = rule.min_changed_lines || '';
                            document.querySelector('[name="context_lines"]').value = rule.context_lines || '';
                            document.querySelector('[name="is_active"]').checked = rule.is_active;
                            const checkboxes = document.querySelectorAll('[name="enabled_categories"]');
                            checkboxes.forEach(cb => {
                                cb.checked = rule.enabled_categories.includes(cb.value);
                            });
                            updateRepoSelectVisibility();
                            document.getElementById('ruleModal').classList.remove('hidden');
                        });
                }

                function closeRuleModal() {
                    document.getElementById('ruleModal').classList.add('hidden');
                }

                function updateRepoSelectVisibility() {
                    const scope = document.querySelector('[name="scope"]').value;
                    const container = document.getElementById('repoSelectContainer');
                    if (scope === 'repository') {
                        container.classList.remove('hidden');
                    } else {
                        container.classList.add('hidden');
                    }
                }

                document.querySelector('[name="scope"]').addEventListener('change', updateRepoSelectVisibility);

                function submitRuleForm(e) {
                    e.preventDefault();
                    const form = e.target;
                    const formData = new FormData(form);
                    const ruleId = document.getElementById('ruleId').value;
                    const data = {};
                    formData.forEach((value, key) => {
                        if (key === 'enabled_categories') {
                            if (!data[key]) data[key] = [];
                            data[key].push(value);
                        } else {
                            data[key] = value;
                        }
                    });

                    const url = ruleId ? '/api/ai-rules/' + ruleId : '/api/ai-rules';
                    const method = ruleId ? 'PUT' : 'POST';

                    fetch(url, {
                        method: method,
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(data)
                    }).then(r => {
                        if (r.ok) {
                            location.reload();
                        } else {
                            alert('保存失败');
                        }
                    });
                }

                function toggleRuleActive(ruleId, isActive) {
                    fetch('/api/ai-rules/' + ruleId, {
                        method: 'PATCH',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ is_active: isActive })
                    }).then(r => {
                        if (!r.ok) {
                            alert('操作失败');
                            location.reload();
                        }
                    });
                }

                function setDefaultRule(ruleId) {
                    if (confirm('确定要将此规则设为默认规则吗？')) {
                        fetch('/api/ai-rules/' + ruleId + '/default', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' }
                        }).then(r => {
                            if (r.ok) {
                                location.reload();
                            } else {
                                alert('操作失败');
                            }
                        });
                    }
                }

                function deleteRule(ruleId) {
                    if (confirm('确定要删除此规则吗？此操作不可撤销。')) {
                        fetch('/api/ai-rules/' + ruleId, {
                            method: 'DELETE',
                            headers: { 'Content-Type': 'application/json' }
                        }).then(r => {
                            if (r.ok) {
                                location.reload();
                            } else {
                                alert('删除失败');
                            }
                        });
                    }
                }
            "#))
        }
    })
}

fn is_active_filter_selected(value: &str, filter: &Option<bool>) -> bool {
    match (value, filter) {
        ("all", None) => true,
        ("active", Some(true)) => true,
        ("inactive", Some(false)) => true,
        _ => false,
    }
}

fn ai_rules_styles() -> &'static str {
    r#"
    .line-clamp-2 {
        display: -webkit-box;
        -webkit-line-clamp: 2;
        -webkit-box-orient: vertical;
        overflow: hidden;
    }
    "#
}
