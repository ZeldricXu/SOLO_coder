use maud::{html, Markup};
use crate::templates::layout::{base_layout, LayoutContext};
use crate::templates::components::{
    card, button, modal, input_field, select_field, role_badge, user_avatar,
    ButtonVariant, Role,
};

pub struct Organization {
    pub id: String,
    pub name: String,
    pub slug: String,
    pub created_at: String,
    pub owner_name: String,
    pub owner_avatar_url: Option<String>,
}

pub struct Team {
    pub id: String,
    pub name: String,
    pub description: String,
    pub member_count: u32,
    pub repo_count: u32,
    pub created_at: String,
}

pub struct TeamMember {
    pub id: String,
    pub name: String,
    pub email: String,
    pub avatar_url: Option<String>,
    pub role: Role,
    pub joined_at: String,
}

pub struct Repo {
    pub id: String,
    pub name: String,
}

pub struct TeamTreeNode {
    pub id: String,
    pub name: String,
    pub teams: Vec<TeamNode>,
}

pub struct TeamNode {
    pub id: String,
    pub name: String,
    pub repos: Vec<Repo>,
}

pub fn organization_page(ctx: LayoutContext, org: &Organization, tree: &TeamTreeNode) -> Markup {
    base_layout(ctx, html! {
        div class="space-y-6" {
            div class="flex items-center justify-between" {
                h1 class="text-2xl font-bold text-white" { "组织管理" }
                div class="flex gap-2" {
                    (button(ButtonVariant::Secondary, "编辑", Some("toggleEditOrgModal()"), false))
                    (button(ButtonVariant::Danger, "删除组织", Some("toggleDeleteOrgModal()"), false))
                }
            }

            div class="grid grid-cols-1 lg:grid-cols-2 gap-6" {
                (card(Some("组织基本信息"), html! {
                    div class="space-y-4" {
                        div class="flex items-center gap-4" {
                            div class="w-16 h-16 bg-gradient-to-br from-[#3B82F6] to-[#8B5CF6] rounded-xl flex items-center justify-center text-2xl font-bold text-white" {
                                (org.name.chars().next().unwrap_or('O').to_ascii_uppercase())
                            }
                            div {
                                h2 class="text-xl font-semibold text-white" { (org.name) }
                                p class="text-[#94A3B8] text-sm" { (org.slug) }
                            }
                        }
                        div class="grid grid-cols-2 gap-4 pt-4 border-t border-[#334155]" {
                            div {
                                p class="text-xs text-[#64748B] mb-1" { "创建时间" }
                                p class="text-white" { (org.created_at) }
                            }
                            div {
                                p class="text-xs text-[#64748B] mb-1" { "Owner" }
                                div class="flex items-center gap-2" {
                                    (user_avatar(&org.owner_name, org.owner_avatar_url.as_deref(), 24))
                                    span class="text-white" { (org.owner_name) }
                                }
                            }
                        }
                    }
                }, None))

                (card(Some("组织设置"), html! {
                    form action="/admin/organization" method="POST" class="space-y-4" {
                        input type="hidden" name="csrf_token" value=(ctx.csrf_token);
                        div class="space-y-1" {
                            label class="text-sm text-[#94A3B8]" { "组织名称" }
                            (input_field("name", &org.name, "输入组织名称", "text", true))
                        }
                        div class="space-y-1" {
                            label class="text-sm text-[#94A3B8]" { "Slug" }
                            (input_field("slug", &org.slug, "输入组织标识", "text", true))
                        }
                        (button(ButtonVariant::Primary, "保存设置", None, false))
                    }
                }, None))
            }

            (card(Some("组织结构"), html! {
                div class="space-y-2" {
                    div class="flex items-center gap-3 p-3 bg-[#0F172A] rounded-lg border border-[#334155]" {
                        button onclick="toggleTreeNode('org')" class="text-[#64748B] hover:text-white transition-colors" id="org-toggle" { "▼" }
                        div class="w-10 h-10 bg-gradient-to-br from-[#3B82F6] to-[#8B5CF6] rounded-lg flex items-center justify-center text-white font-bold" {
                            (org.name.chars().next().unwrap_or('O').to_ascii_uppercase())
                        }
                        div class="flex-1" {
                            p class="font-medium text-white" { (org.name) }
                            p class="text-xs text-[#64748B]" { "组织" }
                        }
                        span class="text-xs text-[#64748B]" { (tree.teams.len()) " 个团队" }
                    }

                    div id="org-children" class="ml-6 space-y-2" {
                        @for team in &tree.teams {
                            div class="space-y-2" {
                                div class="flex items-center gap-3 p-3 bg-[#0F172A] rounded-lg border border-[#334155]" {
                                    button onclick={"toggleTreeNode('team-" (team.id) "')"} class="text-[#64748B] hover:text-white transition-colors" id={"team-" (team.id) "-toggle"} { "▼" }
                                    div class="w-8 h-8 bg-[#334155] rounded-lg flex items-center justify-center text-white" { "👥" }
                                    div class="flex-1" {
                                        p class="font-medium text-white" { (team.name) }
                                        p class="text-xs text-[#64748B]" { "团队" }
                                    }
                                    span class="text-xs text-[#64748B]" { (team.repos.len()) " 个仓库" }
                                }
                                div id={"team-" (team.id) "-children"} class="ml-6 space-y-1" {
                                    @for repo in &team.repos {
                                        div class="flex items-center gap-3 p-2 bg-[#0F172A]/50 rounded-lg border border-[#334155]/50" {
                                            div class="w-6 h-6 bg-[#1E293B] rounded flex items-center justify-center text-xs text-[#94A3B8]" { "📦" }
                                            div class="flex-1" {
                                                p class="text-sm text-white" { (repo.name) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }, None))

            div class="bg-red-500/10 border border-red-500/30 rounded-xl p-6" {
                h3 class="text-lg font-semibold text-red-400 mb-2" { "⚠️ 危险区域" }
                p class="text-[#94A3B8] text-sm mb-4" { "删除组织将永久删除所有相关数据，包括团队、仓库、MR、问题等。此操作不可撤销。" }
                (button(ButtonVariant::Danger, "删除组织", Some("toggleDeleteOrgModal()"), false))
            }
        }

        (modal("editOrgModal", "编辑组织", html! {
            form action="/admin/organization" method="POST" class="space-y-4" {
                input type="hidden" name="csrf_token" value=(ctx.csrf_token);
                div class="space-y-1" {
                    label class="text-sm text-[#94A3B8]" { "组织名称" }
                    (input_field("name", &org.name, "输入组织名称", "text", true))
                }
                div class="space-y-1" {
                    label class="text-sm text-[#94A3B8]" { "Slug" }
                    (input_field("slug", &org.slug, "输入组织标识", "text", true))
                }
                div class="flex justify-end gap-2 pt-4" {
                    (button(ButtonVariant::Secondary, "取消", Some("document.getElementById('editOrgModal').classList.add('hidden')"), false))
                    (button(ButtonVariant::Primary, "保存", None, false))
                }
            }
        }))

        (modal("deleteOrgModal", "删除组织", html! {
            div class="space-y-4" {
                p class="text-[#CBD5E1]" { "确定要删除组织 " strong class="text-white" { (org.name) } " 吗？此操作不可撤销。" }
                div class="bg-red-500/10 border border-red-500/30 rounded-lg p-3" {
                    p class="text-sm text-red-400" { "⚠️ 删除后，所有团队、仓库、MR、问题等数据都将被永久删除。" }
                }
                div class="space-y-1" {
                    label class="text-sm text-[#94A3B8]" { "请输入组织名称以确认" }
                    (input_field("confirm_name", "", "输入组织名称", "text", true))
                }
                form action="/admin/organization/delete" method="POST" class="flex justify-end gap-2 pt-4" {
                    input type="hidden" name="csrf_token" value=(ctx.csrf_token);
                    (button(ButtonVariant::Secondary, "取消", Some("document.getElementById('deleteOrgModal').classList.add('hidden')"), false))
                    (button(ButtonVariant::Danger, "确认删除", None, false))
                }
            }
        }))

        script {
            (maud::PreEscaped(r#"
                function toggleTreeNode(id) {
                    const children = document.getElementById(id + '-children');
                    const toggle = document.getElementById(id + '-toggle');
                    if (children) {
                        children.classList.toggle('hidden');
                        toggle.textContent = children.classList.contains('hidden') ? '▶' : '▼';
                    }
                }
                function toggleEditOrgModal() {
                    document.getElementById('editOrgModal').classList.toggle('hidden');
                }
                function toggleDeleteOrgModal() {
                    document.getElementById('deleteOrgModal').classList.toggle('hidden');
                }
            "#))
        }
    })
}

pub fn teams_page(ctx: LayoutContext, teams: &[Team]) -> Markup {
    base_layout(ctx, html! {
        div class="space-y-6" {
            div class="flex items-center justify-between" {
                h1 class="text-2xl font-bold text-white" { "团队管理" }
                (button(ButtonVariant::Primary, "+ 创建团队", Some("document.getElementById('createTeamModal').classList.remove('hidden')"), false))
            }

            @if teams.is_empty() {
                div class="text-center py-16" {
                    div class="text-6xl mb-4" { "👥" }
                    h3 class="text-xl font-semibold text-white mb-2" { "暂无团队" }
                    p class="text-[#94A3B8] mb-6" { "创建第一个团队开始协作吧" }
                    (button(ButtonVariant::Primary, "+ 创建团队", Some("document.getElementById('createTeamModal').classList.remove('hidden')"), false))
                }
            } @else {
                div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6" {
                    @for team in teams {
                        (card(None, html! {
                            div class="space-y-4" {
                                div class="flex items-start justify-between" {
                                    div class="flex items-center gap-3" {
                                        div class="w-12 h-12 bg-gradient-to-br from-[#3B82F6] to-[#8B5CF6] rounded-xl flex items-center justify-center text-xl" {
                                            "👥"
                                        }
                                        div {
                                            h3 class="font-semibold text-white" { (team.name) }
                                            p class="text-xs text-[#64748B]" { (team.created_at) }
                                        }
                                    }
                                }
                                p class="text-sm text-[#94A3B8] line-clamp-2 min-h-[40px]" { (team.description) }
                                div class="flex items-center gap-4 pt-4 border-t border-[#334155]" {
                                    div class="flex items-center gap-1 text-sm text-[#94A3B8]" {
                                        span { "👤" }
                                        (team.member_count) " 成员"
                                    }
                                    div class="flex items-center gap-1 text-sm text-[#94A3B8]" {
                                        span { "📦" }
                                        (team.repo_count) " 仓库"
                                    }
                                }
                                div class="flex gap-2 pt-2" {
                                    a href={"/admin/teams/" (team.id) "/edit"} class="flex-1" {
                                        (button(ButtonVariant::Secondary, "编辑", None, false))
                                    }
                                    a href={"/admin/teams/" (team.id) "/members"} class="flex-1" {
                                        (button(ButtonVariant::Ghost, "成员", None, false))
                                    }
                                    button onclick={"deleteTeam('" (team.id) "', '" (team.name) "')"} class="p-2 text-red-400 hover:bg-red-500/10 rounded-lg transition-colors" title="删除团队" {
                                        "🗑️"
                                    }
                                }
                            }
                        }, None))
                    }
                }
            }
        }

        (modal("createTeamModal", "创建团队", html! {
            form action="/admin/teams" method="POST" class="space-y-4" {
                input type="hidden" name="csrf_token" value=(ctx.csrf_token);
                div class="space-y-1" {
                    label class="text-sm text-[#94A3B8]" { "团队名称" }
                    (input_field("name", "", "输入团队名称", "text", true))
                }
                div class="space-y-1" {
                    label class="text-sm text-[#94A3B8]" { "团队描述" }
                    textarea
                        name="description"
                        placeholder="输入团队描述"
                        rows="3"
                        class="w-full px-4 py-2.5 bg-[#1E293B] border border-[#334155] rounded-lg text-white placeholder-[#64748B] focus:outline-none focus:border-[#3B82F6] focus:ring-1 focus:ring-[#3B82F6] transition-all resize-y"
                    {}
                }
                div class="flex justify-end gap-2 pt-4" {
                    (button(ButtonVariant::Secondary, "取消", Some("document.getElementById('createTeamModal').classList.add('hidden')"), false))
                    (button(ButtonVariant::Primary, "创建", None, false))
                }
            }
        }))

        (modal("deleteTeamModal", "删除团队", html! {
            div class="space-y-4" {
                p class="text-[#CBD5E1]" { "确定要删除团队 " strong class="text-white" id="deleteTeamName" {} " 吗？" }
                div class="bg-amber-500/10 border border-amber-500/30 rounded-lg p-3" {
                    p class="text-sm text-amber-400" { "⚠️ 删除团队不会删除仓库数据，但会移除所有成员的团队关联。" }
                }
                form id="deleteTeamForm" action="" method="POST" class="flex justify-end gap-2 pt-4" {
                    input type="hidden" name="csrf_token" value=(ctx.csrf_token);
                    (button(ButtonVariant::Secondary, "取消", Some("document.getElementById('deleteTeamModal').classList.add('hidden')"), false))
                    (button(ButtonVariant::Danger, "确认删除", None, false))
                }
            }
        }))

        script {
            (maud::PreEscaped(r#"
                function deleteTeam(id, name) {
                    document.getElementById('deleteTeamName').textContent = name;
                    document.getElementById('deleteTeamForm').action = '/admin/teams/' + id + '/delete';
                    document.getElementById('deleteTeamModal').classList.remove('hidden');
                }
            "#))
        }
    })
}

pub fn team_members_page(ctx: LayoutContext, team: &Team, members: &[TeamMember]) -> Markup {
    base_layout(ctx, html! {
        div class="space-y-6" {
            div class="bg-[#1E293B] border border-[#334155] rounded-xl p-6" {
                div class="flex items-center justify-between" {
                    div class="flex items-center gap-4" {
                        div class="w-16 h-16 bg-gradient-to-br from-[#3B82F6] to-[#8B5CF6] rounded-xl flex items-center justify-center text-2xl" {
                            "👥"
                        }
                        div {
                            h1 class="text-2xl font-bold text-white" { (team.name) }
                            p class="text-[#94A3B8]" { (team.description) }
                        }
                    }
                    (button(ButtonVariant::Primary, "+ 添加成员", Some("document.getElementById('addMemberModal').classList.remove('hidden')"), false))
                }
                div class="flex items-center gap-6 mt-4 pt-4 border-t border-[#334155]" {
                    div class="flex items-center gap-2" {
                        span class="text-[#64748B]" { "👤" }
                        span class="text-white" { (members.len()) " 成员" }
                    }
                    div class="flex items-center gap-2" {
                        span class="text-[#64748B]" { "📦" }
                        span class="text-white" { (team.repo_count) " 仓库" }
                    }
                    div class="flex items-center gap-2" {
                        span class="text-[#64748B]" { "📅" }
                        span class="text-white" { "创建于 " (team.created_at) }
                    }
                }
            }

            div class="bg-[#1E293B] border border-[#334155] rounded-xl p-4 mb-6" {
                div class="flex items-start gap-3" {
                    span class="text-xl" { "ℹ️" }
                    div {
                        h4 class="font-medium text-white mb-1" { "角色说明" }
                        div class="grid grid-cols-2 md:grid-cols-4 gap-3 text-sm" {
                            div class="flex items-center gap-2" {
                                (role_badge(Role::Owner))
                                span class="text-[#94A3B8]" { "完全管理权限" }
                            }
                            div class="flex items-center gap-2" {
                                (role_badge(Role::Maintainer))
                                span class="text-[#94A3B8]" { "管理团队和仓库" }
                            }
                            div class="flex items-center gap-2" {
                                (role_badge(Role::Reviewer))
                                span class="text-[#94A3B8]" { "代码评审权限" }
                            }
                            div class="flex items-center gap-2" {
                                (role_badge(Role::Developer))
                                span class="text-[#94A3B8]" { "开发和提交" }
                            }
                        }
                    }
                }
            }

            (card(Some("成员列表"), html! {
                div class="overflow-x-auto" {
                    table class="w-full" {
                        thead class="bg-[#0F172A]/50" {
                            tr {
                                th class="px-4 py-3 text-left text-xs font-medium text-[#94A3B8] uppercase tracking-wider border-b border-[#334155]" { "成员" }
                                th class="px-4 py-3 text-left text-xs font-medium text-[#94A3B8] uppercase tracking-wider border-b border-[#334155]" { "邮箱" }
                                th class="px-4 py-3 text-left text-xs font-medium text-[#94A3B8] uppercase tracking-wider border-b border-[#334155]" { "角色" }
                                th class="px-4 py-3 text-left text-xs font-medium text-[#94A3B8] uppercase tracking-wider border-b border-[#334155]" { "加入时间" }
                                th class="px-4 py-3 text-right text-xs font-medium text-[#94A3B8] uppercase tracking-wider border-b border-[#334155]" { "操作" }
                            }
                        }
                        tbody class="divide-y divide-[#334155]/50" {
                            @for member in members {
                                tr class="hover:bg-white/5 transition-colors" {
                                    td class="px-4 py-3" {
                                        div class="flex items-center gap-3" {
                                            (user_avatar(&member.name, member.avatar_url.as_deref(), 36))
                                            span class="font-medium text-white" { (member.name) }
                                        }
                                    }
                                    td class="px-4 py-3 text-sm text-[#CBD5E1]" { (member.email) }
                                    td class="px-4 py-3" { (role_badge(member.role.clone())) }
                                    td class="px-4 py-3 text-sm text-[#94A3B8]" { (member.joined_at) }
                                    td class="px-4 py-3 text-right" {
                                        div class="flex items-center justify-end gap-2" {
                                            button onclick={"changeRole('" (member.id) "', '" (member.name) "')"} class="p-2 text-[#64748B] hover:text-[#3B82F6] hover:bg-[#3B82F6]/10 rounded-lg transition-colors" title="更改角色" {
                                                "🔄"
                                            }
                                            button onclick={"removeMember('" (member.id) "', '" (member.name) "')"} class="p-2 text-[#64748B] hover:text-red-400 hover:bg-red-500/10 rounded-lg transition-colors" title="移除成员" {
                                                "✕"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }, None))
        }

        (modal("addMemberModal", "添加成员", html! {
            form action="" method="POST" class="space-y-4" id="addMemberForm" {
                input type="hidden" name="csrf_token" value=(ctx.csrf_token);
                div class="space-y-1" {
                    label class="text-sm text-[#94A3B8]" { "搜索用户" }
                    div class="relative" {
                        input
                            type="text"
                            id="userSearch"
                            placeholder="输入用户名或邮箱搜索"
                            class="w-full px-4 py-2.5 pl-10 bg-[#1E293B] border border-[#334155] rounded-lg text-white placeholder-[#64748B] focus:outline-none focus:border-[#3B82F6] focus:ring-1 focus:ring-[#3B82F6] transition-all"
                            oninput="searchUsers()"
                        ;
                        span class="absolute left-3 top-1/2 -translate-y-1/2 text-[#64748B]" { "🔍" }
                    }
                    div id="userSearchResults" class="mt-2 max-h-40 overflow-y-auto space-y-1 hidden" {}
                }
                div class="space-y-1" {
                    label class="text-sm text-[#94A3B8]" { "选择角色" }
                    (select_field("role", vec![
                        ("owner".to_string(), "Owner".to_string()),
                        ("maintainer".to_string(), "Maintainer".to_string()),
                        ("reviewer".to_string(), "Reviewer".to_string()),
                        ("developer".to_string(), "Developer".to_string()),
                    ], Some("developer")))
                }
                div class="flex justify-end gap-2 pt-4" {
                    (button(ButtonVariant::Secondary, "取消", Some("document.getElementById('addMemberModal').classList.add('hidden')"), false))
                    (button(ButtonVariant::Primary, "添加", None, false))
                }
            }
        }))

        (modal("changeRoleModal", "更改角色", html! {
            div class="space-y-4" {
                p class="text-[#CBD5E1]" { "更改 " strong class="text-white" id="changeRoleUserName" {} " 的角色为：" }
                form id="changeRoleForm" action="" method="POST" class="space-y-4" {
                    input type="hidden" name="csrf_token" value=(ctx.csrf_token);
                    (select_field("role", vec![
                        ("owner".to_string(), "Owner".to_string()),
                        ("maintainer".to_string(), "Maintainer".to_string()),
                        ("reviewer".to_string(), "Reviewer".to_string()),
                        ("developer".to_string(), "Developer".to_string()),
                    ], Some("developer")))
                    div class="flex justify-end gap-2 pt-4" {
                        (button(ButtonVariant::Secondary, "取消", Some("document.getElementById('changeRoleModal').classList.add('hidden')"), false))
                        (button(ButtonVariant::Primary, "确认", None, false))
                    }
                }
            }
        }))

        (modal("removeMemberModal", "移除成员", html! {
            div class="space-y-4" {
                p class="text-[#CBD5E1]" { "确定要将 " strong class="text-white" id="removeMemberName" {} " 从团队中移除吗？" }
                div class="bg-amber-500/10 border border-amber-500/30 rounded-lg p-3" {
                    p class="text-sm text-amber-400" { "⚠️ 移除后，该成员将失去团队的访问权限。" }
                }
                form id="removeMemberForm" action="" method="POST" class="flex justify-end gap-2 pt-4" {
                    input type="hidden" name="csrf_token" value=(ctx.csrf_token);
                    (button(ButtonVariant::Secondary, "取消", Some("document.getElementById('removeMemberModal').classList.add('hidden')"), false))
                    (button(ButtonVariant::Danger, "确认移除", None, false))
                }
            }
        }))

        script {
            (maud::PreEscaped(r#"
                function searchUsers() {
                    const query = document.getElementById('userSearch').value;
                    const results = document.getElementById('userSearchResults');
                    if (query.length < 2) {
                        results.classList.add('hidden');
                        return;
                    }
                    results.classList.remove('hidden');
                    results.innerHTML = '<div class="p-2 text-sm text-[#64748B]">正在搜索...</div>';
                }
                function changeRole(id, name) {
                    document.getElementById('changeRoleUserName').textContent = name;
                    document.getElementById('changeRoleForm').action = '/admin/teams/members/' + id + '/role';
                    document.getElementById('changeRoleModal').classList.remove('hidden');
                }
                function removeMember(id, name) {
                    document.getElementById('removeMemberName').textContent = name;
                    document.getElementById('removeMemberForm').action = '/admin/teams/members/' + id + '/remove';
                    document.getElementById('removeMemberModal').classList.remove('hidden');
                }
            "#))
        }
    })
}
