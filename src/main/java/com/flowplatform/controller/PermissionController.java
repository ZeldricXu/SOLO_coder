package com.flowplatform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowplatform.common.R;
import com.flowplatform.entity.SysDept;
import com.flowplatform.entity.SysPermission;
import com.flowplatform.entity.SysRole;
import com.flowplatform.entity.SysUser;
import com.flowplatform.mapper.SysPermissionMapper;
import com.flowplatform.service.SysDeptService;
import com.flowplatform.service.SysRoleService;
import com.flowplatform.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
@RequestMapping("/permission")
public class PermissionController {

    private final SysUserService sysUserService;
    private final SysRoleService sysRoleService;
    private final SysDeptService sysDeptService;
    private final SysPermissionMapper sysPermissionMapper;

    @GetMapping
    public String index() {
        return "redirect:/permission/users";
    }

    @GetMapping("/users")
    public String users(Model model,
                        @RequestParam(required = false) String keyword,
                        @RequestParam(required = false) Long deptId,
                        @RequestParam(required = false) Integer status) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getRealName, keyword)
                    .or().like(SysUser::getEmail, keyword));
        }
        if (deptId != null) {
            wrapper.eq(SysUser::getDeptId, deptId);
        }
        if (status != null) {
            wrapper.eq(SysUser::getStatus, status);
        }
        wrapper.orderByDesc(SysUser::getCreateTime);
        List<SysUser> users = sysUserService.list(wrapper);
        users.forEach(u -> {
            u.setRoleCodes(sysUserService.getRoleCodes(u.getId()));
            u.setRoleNames(sysUserService.getRoleNames(u.getId()));
            if (u.getDeptId() != null) {
                SysDept dept = sysDeptService.getById(u.getDeptId());
                if (dept != null) u.setDeptName(dept.getDeptName());
            }
        });
        model.addAttribute("users", users);
        model.addAttribute("keyword", keyword);
        model.addAttribute("deptId", deptId);
        model.addAttribute("status", status);
        model.addAttribute("depts", sysDeptService.getDeptTree());
        model.addAttribute("activeTab", "users");
        return "permission/users";
    }

    @GetMapping("/roles")
    public String roles(Model model) {
        List<SysRole> roles = sysRoleService.list(new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId));
        model.addAttribute("roles", roles);
        model.addAttribute("activeTab", "roles");
        return "permission/roles";
    }

    @GetMapping("/departments")
    public String departments(Model model) {
        model.addAttribute("activeTab", "departments");
        return "permission/departments";
    }

    @PostMapping("/user/save")
    @ResponseBody
    public R<?> saveUser(SysUser user) {
        boolean success;
        if (user.getId() != null) {
            SysUser existing = sysUserService.getById(user.getId());
            if (existing != null && user.getPassword() == null) {
                user.setPassword(existing.getPassword());
            }
            success = sysUserService.updateById(user);
        } else {
            success = sysUserService.save(user);
        }
        return success ? R.ok() : R.fail("保存用户失败");
    }

    @PostMapping("/user/delete/{id}")
    @ResponseBody
    public R<?> deleteUser(@PathVariable Long id) {
        boolean success = sysUserService.removeById(id);
        return success ? R.ok() : R.fail("删除用户失败");
    }

    @PostMapping("/role/save")
    @ResponseBody
    public R<?> saveRole(SysRole role) {
        boolean success;
        if (role.getId() != null) {
            success = sysRoleService.updateById(role);
        } else {
            success = sysRoleService.save(role);
        }
        return success ? R.ok() : R.fail("保存角色失败");
    }

    @PostMapping("/role/delete/{id}")
    @ResponseBody
    public R<?> deleteRole(@PathVariable Long id) {
        boolean success = sysRoleService.removeById(id);
        return success ? R.ok() : R.fail("删除角色失败");
    }

    @PostMapping("/role/assign-permissions")
    @ResponseBody
    public R<?> assignPermissions(@RequestBody Map<String, Object> params) {
        Long roleId = Long.valueOf(params.get("roleId").toString());
        @SuppressWarnings("unchecked")
        List<Integer> permIds = (List<Integer>) params.get("permIds");
        return R.ok();
    }

    @PostMapping("/dept/save")
    @ResponseBody
    public R<?> saveDept(SysDept dept) {
        boolean success;
        if (dept.getId() != null) {
            success = sysDeptService.updateById(dept);
        } else {
            success = sysDeptService.save(dept);
        }
        return success ? R.ok() : R.fail("保存部门失败");
    }

    @PostMapping("/dept/delete/{id}")
    @ResponseBody
    public R<?> deleteDept(@PathVariable Long id) {
        boolean success = sysDeptService.removeById(id);
        return success ? R.ok() : R.fail("删除部门失败");
    }

    @GetMapping("/api/dept-tree")
    @ResponseBody
    public List<SysDept> deptTree(@RequestParam(required = false) Long parentId) {
        if (parentId != null) {
            return sysDeptService.list(new LambdaQueryWrapper<SysDept>()
                    .eq(SysDept::getParentId, parentId)
                    .eq(SysDept::getStatus, 1)
                    .orderByAsc(SysDept::getSortOrder));
        }
        return sysDeptService.getDeptTree();
    }

    @GetMapping("/api/permissions")
    @ResponseBody
    public List<SysPermission> permissions() {
        List<SysPermission> all = sysPermissionMapper.selectList(
                new LambdaQueryWrapper<SysPermission>().eq(SysPermission::getStatus, 1).orderByAsc(SysPermission::getSortOrder));
        java.util.Map<Long, java.util.List<SysPermission>> childrenMap = all.stream()
                .filter(p -> p.getParentId() != null && p.getParentId() > 0)
                .collect(java.util.stream.Collectors.groupingBy(SysPermission::getParentId));
        all.forEach(p -> p.setChildren(childrenMap.getOrDefault(p.getId(), new java.util.ArrayList<>())));
        return all.stream()
                .filter(p -> p.getParentId() == null || p.getParentId() == 0)
                .collect(java.util.stream.Collectors.toList());
    }
}
