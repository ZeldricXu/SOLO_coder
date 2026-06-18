package com.designsystem.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.designsystem.common.PageQuery;
import com.designsystem.common.enums.ApprovalStatus;
import com.designsystem.common.util.SemverUtil;
import com.designsystem.entity.ApprovalRequest;
import com.designsystem.entity.Changelog;
import com.designsystem.entity.Component;
import com.designsystem.entity.ComponentVersion;
import com.designsystem.entity.DesignToken;
import com.designsystem.entity.SysUser;
import com.designsystem.mapper.ApprovalRequestMapper;
import com.designsystem.mapper.ChangelogMapper;
import com.designsystem.mapper.ComponentMapper;
import com.designsystem.mapper.ComponentVersionMapper;
import com.designsystem.mapper.DesignTokenMapper;
import com.designsystem.mapper.SysUserMapper;
import com.designsystem.security.CustomUserDetails;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.designsystem.config.RabbitMQConfig.*;

@Service
public class ApprovalService {

    private final ApprovalRequestMapper approvalMapper;
    private final ComponentMapper componentMapper;
    private final DesignTokenMapper tokenMapper;
    private final SysUserMapper userMapper;
    private final ComponentVersionMapper versionMapper;
    private final ChangelogMapper changelogMapper;
    private final RabbitTemplate rabbitTemplate;

    public ApprovalService(ApprovalRequestMapper approvalMapper, ComponentMapper componentMapper,
                           DesignTokenMapper tokenMapper, SysUserMapper userMapper,
                           ComponentVersionMapper versionMapper, ChangelogMapper changelogMapper,
                           RabbitTemplate rabbitTemplate) {
        this.approvalMapper = approvalMapper;
        this.componentMapper = componentMapper;
        this.tokenMapper = tokenMapper;
        this.userMapper = userMapper;
        this.versionMapper = versionMapper;
        this.changelogMapper = changelogMapper;
        this.rabbitTemplate = rabbitTemplate;
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
                List<Changelog> unreleasedLogs = changelogMapper.selectUnreleasedByComponentId(component.getId());
                List<String> commitMessages = unreleasedLogs.stream()
                        .map(log -> log.getCommitType() + ": " + log.getCommitSubject())
                        .toList();

                String currentVersion = component.getLatestVersion() != null ? component.getLatestVersion() : "1.0.0";
                String newVersion = SemverUtil.getNextVersionFromChangelogs(currentVersion, commitMessages);
                if (newVersion.equals(currentVersion)) {
                    newVersion = SemverUtil.incrementVersion(currentVersion, SemverUtil.BumpType.PATCH);
                }

                ComponentVersion newVersionObj = new ComponentVersion();
                newVersionObj.setComponentId(component.getId());
                newVersionObj.setVersion(newVersion);
                newVersionObj.setChangelog(request.getChangeContent());
                newVersionObj.setIsLatest(1);
                newVersionObj.setIsPrerelease(0);
                versionMapper.insert(newVersionObj);

                component.setLatestVersion(newVersion);
                component.setPublished(1);
                componentMapper.updateById(component);

                ComponentVersion previousLatest = versionMapper.selectLatestVersion(component.getId());
                if (previousLatest != null && !previousLatest.getId().equals(newVersionObj.getId())) {
                    previousLatest.setIsLatest(0);
                    versionMapper.updateById(previousLatest);
                }

                rabbitTemplate.convertAndSend(EXCHANGE_DESIGN_SYSTEM, ROUTING_KEY_COMPONENT_PUBLISH, component.getId());
                rabbitTemplate.convertAndSend(EXCHANGE_DESIGN_SYSTEM, ROUTING_KEY_NOTIFICATION,
                        "Component " + component.getName() + " v" + newVersion + " has been published");
            }
        } else if (request.getRequestType().startsWith("TOKEN_")) {
            DesignToken token = tokenMapper.selectById(request.getTargetId());
            if (token != null) {
                rabbitTemplate.convertAndSend(EXCHANGE_DESIGN_SYSTEM, ROUTING_KEY_TOKEN_CHANGE, token.getId());
                rabbitTemplate.convertAndSend(EXCHANGE_DESIGN_SYSTEM, ROUTING_KEY_NOTIFICATION,
                        "Token " + token.getTokenName() + " has been updated");
            }
        }
    }
}
