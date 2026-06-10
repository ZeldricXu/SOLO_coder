use maud::{html, Markup};
use crate::templates::layout::{base_layout, LayoutContext};
use crate::templates::components::{
    card, button, modal, input_field, select_field, textarea_field, ButtonVariant,
};

pub enum ChecklistScope {
    Organization,
    Team,
    Repository,
}

pub struct ChecklistTemplate {
    pub id: String,
    pub name: String,
    pub description: String,
    pub scope: ChecklistScope,
    pub item_count: u32,
    pub parent_template: Option<String>,
    pub created_at: String,
}

pub struct ChecklistItem {
    pub id: String,
    pub title: String,
    pub description: String,
    pub sort_order: u32,
}

pub struct ChecklistGroup {
    pub id: String,
    pub name: String,
    pub items: Vec<ChecklistItem>,
}

pub struct ChecklistDetail {
    pub id: String,
    pub name: String,
    pub description: String,
    pub scope: ChecklistScope,
    pub parent_template_id: Option<String>,
    pub parent_template_name: Option<String>,
    pub groups: Vec<ChecklistGroup>,
    pub inherited_groups: Vec<ChecklistGroup>,
}

fn scope_badge(scope: &ChecklistScope) -> Markup {
    let (label, class) = match scope {
        ChecklistScope::Organization => ("组织", "bg-purple-500/20 text-purple-400 border-purple-500/30"),
        ChecklistScope::Team => ("团队", "bg-blue-500/20 text-blue-400 border-blue-500/30"),
        ChecklistScope::Repository => ("仓库", "bg-emerald-500/20 text-emerald-400 border-emerald-500/30"),
    };
    html! {
        span class={"inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium border " (class)} {
            (label)
        }
    }
}

fn scope_value(scope: &ChecklistScope) -> &'static str {
    match scope {
        ChecklistScope::Organization => "organization",
        ChecklistScope::Team => "team",
        ChecklistScope::Repository => "repository",
    }
}

pub fn checklists_page(ctx: LayoutContext, templates: &[ChecklistTemplate]) -> Markup {
    base_layout(ctx, html! {
        div class="space-y-6" {
            div class="flex items-center justify-between" {
                h1 class="text-2xl font-bold text-white" { "Checklist 模板" }
                (button(ButtonVariant::Primary, "+ 创建模板", Some("document.getElementById('createTemplateModal').classList.remove('hidden')"), false))
            }

            @if templates.is_empty() {
                div class="text-center py-16" {
                    div class="text-6xl mb-4" { "✅" }
                    h3 class="text-xl font-semibold text-white mb-2" { "暂无模板" }
                    p class="text-[#94A3B8] mb-6" { "创建第一个代码审查Checklist模板吧" }
                    (button(ButtonVariant::Primary, "+ 创建模板", Some("document.getElementById('createTemplateModal').classList.remove('hidden')"), false))
                }
            } @else {
                div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6" {
                    @for template in templates {
                        (card(None, html! {
                            div class="space-y-4" {
                                div class="flex items-start justify-between" {
                                    div class="flex-1" {
                                        h3 class="font-semibold text-white mb-1" { (template.name) }
                                        div class="flex items-center gap-2" {
                                            (scope_badge(&template.scope))
                                        }
                                    }
                                }
                                p class="text-sm text-[#94A3B8] line-clamp-2 min-h-[40px]" { (template.description) }
                                div class="flex items-center justify-between pt-4 border-t border-[#334155]" {
                                    div class="flex items-center gap-4 text-sm text-[#94A3B8]" {
                                        span { "📋 " (template.item_count) " 检查项" }
                                    }
                                    span class="text-xs text-[#64748B]" { (template.created_at) }
                                }
                                @if let Some(parent) = &template.parent_template {
                                    div class="flex items-center gap-2 text-sm text-[#64748B]" {
                                        span { "🔗" }
                                        span { "继承自: " (parent) }
                                    }
                                }
                                div class="flex gap-2 pt-2" {
                                    a href={"/checklist/" (template.id)} class="flex-1" {
                                        (button(ButtonVariant::Secondary, "编辑", None, false))
                                    }
                                    button onclick={"deleteTemplate('" (template.id) "', '" (template.name) "')"} class="p-2 text-red-400 hover:bg-red-500/10 rounded-lg transition-colors" title="删除模板" {
                                        "🗑️"
                                    }
                                }
                            }
                        }, None))
                    }
                }
            }
        }

        (modal("createTemplateModal", "创建模板", html! {
            form action="/checklist" method="POST" class="space-y-4" {
                input type="hidden" name="csrf_token" value=(ctx.csrf_token);
                div class="space-y-1" {
                    label class="text-sm text-[#94A3B8]" { "模板名称" }
                    (input_field("name", "", "输入模板名称", "text", true))
                }
                div class="space-y-1" {
                    label class="text-sm text-[#94A3B8]" { "模板描述" }
                    (textarea_field("description", "", "输入模板描述", 3, false))
                }
                div class="space-y-1" {
                    label class="text-sm text-[#94A3B8]" { "适用范围" }
                    (select_field("scope", vec![
                        ("organization".to_string(), "组织".to_string()),
                        ("team".to_string(), "团队".to_string()),
                        ("repository".to_string(), "仓库".to_string()),
                    ], Some("team")))
                }
                div class="space-y-1" {
                    label class="text-sm text-[#94A3B8]" { "父模板（可选）" }
                    (select_field("parent_id", vec![
                        ("".to_string(), "无".to_string()),
                        ("1".to_string(), "基础代码规范".to_string()),
                        ("2".to_string(), "安全审查标准".to_string()),
                    ], None))
                }
                div class="flex justify-end gap-2 pt-4" {
                    (button(ButtonVariant::Secondary, "取消", Some("document.getElementById('createTemplateModal').classList.add('hidden')"), false))
                    (button(ButtonVariant::Primary, "创建", None, false))
                }
            }
        }))

        (modal("deleteTemplateModal", "删除模板", html! {
            div class="space-y-4" {
                p class="text-[#CBD5E1]" { "确定要删除模板 " strong class="text-white" id="deleteTemplateName" {} " 吗？" }
                div class="bg-amber-500/10 border border-amber-500/30 rounded-lg p-3" {
                    p class="text-sm text-amber-400" { "⚠️ 删除后，所有使用此模板的审查记录将不受影响。" }
                }
                form id="deleteTemplateForm" action="" method="POST" class="flex justify-end gap-2 pt-4" {
                    input type="hidden" name="csrf_token" value=(ctx.csrf_token);
                    (button(ButtonVariant::Secondary, "取消", Some("document.getElementById('deleteTemplateModal').classList.add('hidden')"), false))
                    (button(ButtonVariant::Danger, "确认删除", None, false))
                }
            }
        }))

        script {
            (maud::PreEscaped(r#"
                function deleteTemplate(id, name) {
                    document.getElementById('deleteTemplateName').textContent = name;
                    document.getElementById('deleteTemplateForm').action = '/checklist/' + id + '/delete';
                    document.getElementById('deleteTemplateModal').classList.remove('hidden');
                }
            "#))
        }
    })
}

pub fn checklist_detail_page(ctx: LayoutContext, detail: &ChecklistDetail) -> Markup {
    base_layout(ctx, html! {
        div class="space-y-6" {
            div class="flex items-center justify-between" {
                div {
                    h1 class="text-2xl font-bold text-white" { "编辑模板" }
                    p class="text-[#94A3B8]" { (detail.name) }
                }
                div class="flex gap-2" {
                    a href="/checklist" {
                        (button(ButtonVariant::Ghost, "取消", None, false))
                    }
                    (button(ButtonVariant::Primary, "保存", Some("document.getElementById('templateForm').submit()"), false))
                }
            }

            form id="templateForm" action={"/checklist/" (detail.id)} method="POST" class="space-y-6" {
                input type="hidden" name="csrf_token" value=(ctx.csrf_token);

                (card(Some("基本信息"), html! {
                    div class="space-y-4" {
                        div class="grid grid-cols-1 md:grid-cols-2 gap-4" {
                            div class="space-y-1" {
                                label class="text-sm text-[#94A3B8]" { "模板名称" }
                                (input_field("name", &detail.name, "输入模板名称", "text", true))
                            }
                            div class="space-y-1" {
                                label class="text-sm text-[#94A3B8]" { "适用范围" }
                                (select_field("scope", vec![
                                    ("organization".to_string(), "组织".to_string()),
                                    ("team".to_string(), "团队".to_string()),
                                    ("repository".to_string(), "仓库".to_string()),
                                ], Some(scope_value(&detail.scope))))
                            }
                        }
                        div class="space-y-1" {
                            label class="text-sm text-[#94A3B8]" { "模板描述" }
                            (textarea_field("description", &detail.description, "输入模板描述", 3, false))
                        }
                        div class="space-y-1" {
                            label class="text-sm text-[#94A3B8]" { "父模板（可选）" }
                            (select_field("parent_id", vec![
                                ("".to_string(), "无".to_string()),
                                ("1".to_string(), "基础代码规范".to_string()),
                                ("2".to_string(), "安全审查标准".to_string()),
                            ], detail.parent_template_id.as_deref()))
                            @if let Some(parent_name) = &detail.parent_template_name {
                                p class="text-xs text-[#64748B] mt-1" { "当前继承: " (parent_name) }
                            }
                        }
                    }
                }, None))

                (card(Some("检查项分组"), html! {
                    div class="space-y-4" {
                        @for group in &detail.groups {
                            div class="border border-[#334155] rounded-xl overflow-hidden" {
                                div class="flex items-center justify-between p-4 bg-[#0F172A] cursor-pointer" onclick={"toggleGroup('" (group.id) "')"} {
                                    div class="flex items-center gap-3" {
                                        span id={"group-" (group.id) "-toggle"} class="text-[#64748B]" { "▼" }
                                        h4 class="font-medium text-white" { (group.name) }
                                        span class="text-xs text-[#64748B]" { "(" (group.items.len()) " 项)" }
                                    }
                                    div class="flex items-center gap-2" {
                                        button type="button" onclick={"event.stopPropagation(); addItem('" (group.id) "')"} class="p-2 text-[#64748B] hover:text-[#3B82F6] hover:bg-[#3B82F6]/10 rounded-lg transition-colors" title="添加检查项" {
                                            "➕"
                                        }
                                        button type="button" onclick={"event.stopPropagation(); editGroup('" (group.id) "', '" (group.name) "')"} class="p-2 text-[#64748B] hover:text-[#3B82F6] hover:bg-[#3B82F6]/10 rounded-lg transition-colors" title="编辑分组" {
                                            "✏️"
                                        }
                                        button type="button" onclick={"event.stopPropagation(); deleteGroup('" (group.id) "')"} class="p-2 text-[#64748B] hover:text-red-400 hover:bg-red-500/10 rounded-lg transition-colors" title="删除分组" {
                                            "🗑️"
                                        }
                                    }
                                }
                                div id={"group-" (group.id) "-items"} class="p-4 space-y-3" {
                                    @for (index, item) in group.items.iter().enumerate() {
                                        div class="flex items-start gap-3 p-3 bg-[#0F172A] rounded-lg border border-[#334155]/50 group/item" draggable="true" data-item-id=(item.id) {
                                            div class="cursor-grab text-[#64748B] hover:text-white pt-1" title="拖拽排序" { "⠿" }
                                            div class="flex-1" {
                                                input type="hidden" name={ "items[" (group.id) "][" (index) "][id]" } value=(item.id);
                                                div class="space-y-2" {
                                                    input
                                                        type="text"
                                                        name={ "items[" (group.id) "][" (index) "][title]" }
                                                        value=(item.title)
                                                        placeholder="检查项标题"
                                                        class="w-full px-3 py-1.5 bg-transparent border-b border-transparent focus:border-[#3B82F6] text-white placeholder-[#64748B] focus:outline-none transition-colors font-medium"
                                                    ;
                                                    textarea
                                                        name={ "items[" (group.id) "][" (index) "][description]" }
                                                        placeholder="检查项描述（可选）"
                                                        rows="2"
                                                        class="w-full px-3 py-1 bg-transparent border-b border-transparent focus:border-[#3B82F6] text-[#94A3B8] placeholder-[#64748B] focus:outline-none transition-colors text-sm resize-none"
                                                    { (item.description) }
                                                </div>
                                            }div class="flex flex-col gap-1 opacity-0 group-hover/item:opacity-100 transition-opacity" {
                                                button type="button" onclick={"moveItemUp('" (item.id) "')"} class="p-1 text-[#64748B] hover:text-white hover:bg-white/10 rounded transition-colors" title="上移" {
                                                    "↑"
                                                }
                                                button type="button" onclick={"moveItemDown('" (item.id) "')"} class="p-1 text-[#64748B] hover:text-white hover:bg-white/10 rounded transition-colors" title="下移" {
                                                    "↓"
                                                }
                                                button type="button" onclick={"deleteItem('" (item.id) "')"} class="p-1 text-[#64748B] hover:text-red-400 hover:bg-red-500/10 rounded transition-colors" title="删除" {
                                                    "✕"
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        button type="button" onclick="showAddGroupModal()" class="w-full p-4 border-2 border-dashed border-[#334155] rounded-xl text-[#64748B] hover:border-[#3B82F6] hover:text-[#3B82F6] transition-colors flex items-center justify-center gap-2" {
                            "➕" span { "添加分组" }
                        }
                    }
                }, None))

                @if !detail.inherited_groups.is_empty() {
                    (card(Some("继承的检查项"), html! {
                        div class="bg-[#8B5CF6]/10 border border-[#8B5CF6]/30 rounded-lg p-3 mb-4" {
                            p class="text-sm text-[#8B5CF6]" { "🔗 这些检查项继承自父模板，无法在此处编辑。如需修改，请编辑父模板。" }
                        }
                        div class="space-y-4" {
                            @for group in &detail.inherited_groups {
                                div class="border border-[#334155]/50 rounded-xl overflow-hidden opacity-70" {
                                    div class="flex items-center gap-3 p-4 bg-[#0F172A]/50" {
                                        span class="text-[#64748B]" { "▼" }
                                        h4 class="font-medium text-[#94A3B8]" { "🔗 " (group.name) }
                                        span class="text-xs text-[#64748B]" { "(" (group.items.len()) " 项)" }
                                    }
                                    div class="p-4 space-y-2" {
                                        @for item in &group.items {
                                            div class="flex items-start gap-3 p-3 bg-[#0F172A]/30 rounded-lg" {
                                                div class="text-[#64748B] pt-1" { "🔗" }
                                                div class="flex-1" {
                                                    p class="font-medium text-[#94A3B8]" { (item.title) }
                                                    @if !item.description.is_empty() {
                                                        p class="text-sm text-[#64748B] mt-1" { (item.description) }
                                                    }
                                                </div>
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }, None))
                }

                div class="flex justify-end gap-2 pt-4" {
                    a href="/checklist" {
                        (button(ButtonVariant::Secondary, "取消", None, false))
                    }
                    (button(ButtonVariant::Primary, "保存", None, false))
                }
            }
        }

        (modal("addGroupModal", "添加分组", html! {
            form id="addGroupForm" action={"/checklist/" (detail.id) "/groups"} method="POST" class="space-y-4" {
                input type="hidden" name="csrf_token" value=(ctx.csrf_token);
                div class="space-y-1" {
                    label class="text-sm text-[#94A3B8]" { "分组名称" }
                    (select_field("name", vec![
                        ("代码风格".to_string(), "🎨 代码风格".to_string()),
                        ("架构设计".to_string(), "🏗️ 架构设计".to_string()),
                        ("安全".to_string(), "🔒 安全".to_string()),
                        ("性能".to_string(), "⚡ 性能".to_string()),
                        ("测试".to_string(), "🧪 测试".to_string()),
                        ("文档".to_string(), "📝 文档".to_string()),
                    ], None))
                }
                div class="flex justify-end gap-2 pt-4" {
                    (button(ButtonVariant::Secondary, "取消", Some("document.getElementById('addGroupModal').classList.add('hidden')"), false))
                    (button(ButtonVariant::Primary, "添加", None, false))
                }
            }
        }))

        (modal("editGroupModal", "编辑分组", html! {
            form id="editGroupForm" action="" method="POST" class="space-y-4" {
                input type="hidden" name="csrf_token" value=(ctx.csrf_token);
                div class="space-y-1" {
                    label class="text-sm text-[#94A3B8]" { "分组名称" }
                    (input_field("name", "", "输入分组名称", "text", true))
                }
                div class="flex justify-end gap-2 pt-4" {
                    (button(ButtonVariant::Secondary, "取消", Some("document.getElementById('editGroupModal').classList.add('hidden')"), false))
                    (button(ButtonVariant::Primary, "保存", None, false))
                }
            }
        }))

        (modal("addItemModal", "添加检查项", html! {
            form id="addItemForm" action="" method="POST" class="space-y-4" {
                input type="hidden" name="csrf_token" value=(ctx.csrf_token);
                div class="space-y-1" {
                    label class="text-sm text-[#94A3B8]" { "检查项标题" }
                    (input_field("title", "", "输入检查项标题", "text", true))
                }
                div class="space-y-1" {
                    label class="text-sm text-[#94A3B8]" { "检查项描述" }
                    (textarea_field("description", "", "输入检查项描述（可选）", 3, false))
                }
                div class="flex justify-end gap-2 pt-4" {
                    (button(ButtonVariant::Secondary, "取消", Some("document.getElementById('addItemModal').classList.add('hidden')"), false))
                    (button(ButtonVariant::Primary, "添加", None, false))
                }
            }
        }))

        script {
            (maud::PreEscaped(r#"
                function toggleGroup(id) {
                    const items = document.getElementById('group-' + id + '-items');
                    const toggle = document.getElementById('group-' + id + '-toggle');
                    if (items) {
                        items.classList.toggle('hidden');
                        toggle.textContent = items.classList.contains('hidden') ? '▶' : '▼';
                    }
                }
                function showAddGroupModal() {
                    document.getElementById('addGroupModal').classList.remove('hidden');
                }
                function editGroup(id, name) {
                    document.querySelector('#editGroupForm input[name="name"]').value = name;
                    document.getElementById('editGroupForm').action = '/checklist/groups/' + id;
                    document.getElementById('editGroupModal').classList.remove('hidden');
                }
                function deleteGroup(id) {
                    if (confirm('确定要删除此分组吗？所有检查项也将被删除。')) {
                        // TODO: 实现删除逻辑
                    }
                }
                function addItem(groupId) {
                    document.getElementById('addItemForm').action = '/checklist/groups/' + groupId + '/items';
                    document.getElementById('addItemModal').classList.remove('hidden');
                }
                function deleteItem(id) {
                    if (confirm('确定要删除此检查项吗？')) {
                        // TODO: 实现删除逻辑
                    }
                }
                function moveItemUp(id) {
                    // TODO: 实现上移逻辑
                }
                function moveItemDown(id) {
                    // TODO: 实现下移逻辑
                }
            "#))
        }
    })
}
