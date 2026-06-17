package com.designsystem.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.designsystem.common.PageQuery;
import com.designsystem.common.enums.ApprovalStatus;
import com.designsystem.entity.ApprovalRequest;
import com.designsystem.entity.Component;
import com.designsystem.entity.DesignToken;
import com.designsystem.entity.SysUser;
import com.designsystem.mapper.ApprovalRequestMapper;
import com.designsystem.mapper.ComponentMapper;
import com.designsystem.mapper.DesignTokenMapper;
import com.designsystem.mapper.SysUserMapper;
import com.designsystem.security.CustomUserDetails;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ApprovalService {

    private final ApprovalRequestMapper approvalMapper;
    private final ComponentMapper componentMapper;
    private final DesignTokenMapper tokenMapper;
    private final SysUserMapper userMapper;

    public ApprovalService(ApprovalRequestMapper approvalMapper, ComponentMapper componentMapper,
                           DesignTokenMapper tokenMapper, SysUserMapper userMapper) {
        this.approvalMapper = approvalMapper;
        this.componentMapper = componentMapper;
        this.tokenMapper = tokenMapper;
        this.userMapper = userMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public ApprovalRequest createComponentPublishRequest(Long componentId, String version, String changeContent) {
        checkPermission("component:publish");

        Component component = componentMapper.selectById(componentId);
        if (component == null) {
            throw new RuntimeException("Component not found");
        }

        ApprovalRequest request = new ApprovalRequest();
        request.setRequestType("COMPONENT_PUBLISH");
        request.setTargetId(componentId);
        request.setTargetType("COMPONENT");
        request.setTitle("组件发布申请: " + component.getDisplayName() + " v" + version);
        request.setDescription("申请发布组件 " + component.getName() + " 的新版本 " + version);
        request.setChangeContent(changeContent);
        request.setApproverId(findApprover("component_approver"));
        request.setStatus(ApprovalStatus.PENDING);
        request.setSubmittedBy(getCurrentUserId());
        request.setSubmittedAt(LocalDateTime.now());

        approvalMapper.insert(request);
        return request;
    }

    @Transactional(rollbackFor = Exception.class)
    public ApprovalRequest createTokenChangeRequest(Long tokenId, String changeType, String changeContent) {
        checkPermission("token:change");

        DesignToken token = tokenMapper.selectById(tokenId);
        if (token == null) {
            throw new RuntimeException("Token not found");
        }

        ApprovalRequest request = new ApprovalRequest();
        request.setRequestType("TOKEN_" + changeType.toUpperCase());
        request.setTargetId(tokenId);
        request.setTargetType("TOKEN");
        request.setTitle("设计令牌变更申请: " + token.getDisplayName());
        request.setDescription("申请" + getChangeDescription(changeType) + "令牌 " + token.getTokenName());
        request.setChangeContent(changeContent);
        request.setApproverId(findApprover("design_lead"));
        request.setStatus(ApprovalStatus.PENDING);
        request.setSubmittedBy(getCurrentUserId());
        request.setSubmittedAt(LocalDateTime.now());

        approvalMapper.insert(request);
        return request;
    }

    @Transactional(rollbackFor = Exception.class)
    public ApprovalRequest approveRequest(Long requestId, String comment) {
        ApprovalRequest request = approvalMapper.selectById(requestId);
        if (request == null) {
            throw new RuntimeException("Approval request not found");
        }

        checkApproverPermission(request);

        request.setStatus(ApprovalStatus.APPROVED);
        request.setApprovalComment(comment);
        request.setApprovedAt(LocalDateTime.now());
        approvalMapper.updateById(request);

        executeApprovalAction(request);

        return request;
    }

    @Transactional(rollbackFor = Exception.class)
    public ApprovalRequest rejectRequest(Long requestId, String reason) {
        ApprovalRequest request = approvalMapper.selectById(requestId);
        if (request == null) {
            throw new RuntimeException("Approval request not found");
        }

        checkApproverPermission(request);

        request.setStatus(ApprovalStatus.REJECTED);
        request.setRejectReason(reason);
        approvalMapper.updateById(request);

        return request;
    }

    @Transactional(rollbackFor = Exception.class)
    public void rollbackVersion(Long componentId, String targetVersion) {
        checkPermission("version:rollback");

        Component component = componentMapper.selectById(componentId);
        if (component == null) {
            throw new RuntimeException("Component not found");
        }

        component.setLatestVersion(targetVersion);
        componentMapper.updateById(component);
    }

    public IPage<ApprovalRequest> getApprovalPage(PageQuery query, String status, String requestType) {
        Long currentUserId = getCurrentUserId();
        Page<ApprovalRequest> page = new Page<>(query.getPageNum(), query.getPageSize());
        return approvalMapper.selectApprovalPage(page, status, requestType, currentUserId);
    }

    public List<ApprovalRequest> getPendingApprovals() {
        Long currentUserId = getCurrentUserId();
        return approvalMapper.selectPendingByApproverId(currentUserId);
    }

    public ApprovalRequest getApprovalById(Long id) {
        ApprovalRequest request = approvalMapper.selectById(id);
        if (request != null) {
            SysUser submitter = userMapper.selectById(request.getSubmittedBy());
            SysUser approver = userMapper.selectById(request.getApproverId());
        }
        return request;
    }

    private void checkPermission(String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedException("Not authenticated");
        }

        boolean hasPermission = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(permission) ||
                        a.getAuthority().equals("ROLE_ADMIN"));

        if (!hasPermission) {
            throw new AccessDeniedException("Insufficient permissions");
        }
    }

    private void checkApproverPermission(ApprovalRequest request) {
        Long currentUserId = getCurrentUserId();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        if (!isAdmin && !request.getApproverId().equals(currentUserId)) {
            throw new AccessDeniedException("You are not authorized to approve this request");
        }
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CustomUserDetails userDetails) {
            return userDetails.getUserId();
        }
        return 1L;
    }

    private Long findApprover(String roleCode) {
        List<SysUser> users = userMapper.selectList(null);
        for (SysUser user : users) {
            if (user.getUsername().equals("designer") || user.getUsername().equals("admin")) {
                return user.getId();
            }
        }
        return 1L;
    }

    private String getChangeDescription(String changeType) {
        return switch (changeType.toLowerCase()) {
            case "update" -> "更新";
            case "rename" -> "重命名";
            case "deprecate" -> "废弃";
            case "delete" -> "删除";
            default -> "修改";
        };
    }

    private void executeApprovalAction(ApprovalRequest request) {
        if ("COMPONENT_PUBLISH".equals(request.getRequestType())) {
            Component component = componentMapper.selectById(request.getTargetId());
            if (component != null) {
                component.setPublished(1);
                componentMapper.updateById(component);
            }
        } else if (request.getRequestType().startsWith("TOKEN_")) {
        }
    }
}
