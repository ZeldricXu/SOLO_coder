package com.formflow.service;

import com.formflow.entity.ApprovalTask;
import com.formflow.entity.FormData;
import com.formflow.entity.ProcessInstance;
import com.formflow.exception.BusinessException;
import com.formflow.repository.ApprovalTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class PermissionService {

    private static final Logger logger = LoggerFactory.getLogger(PermissionService.class);

    @Autowired
    private ApprovalTaskRepository approvalTaskRepository;

    @Autowired
    private FormDataService formDataService;

    @Autowired
    private ProcessEngineService processEngineService;

    private static final List<String> ADMIN_ROLES = Arrays.asList("admin", "super_admin");

    public boolean canViewForm(String formId, String userId, List<String> userRoles) {
        if (isAdmin(userRoles)) {
            return true;
        }

        try {
            FormData formData = formDataService.getFormByFormId(formId);

            if (formData.getSubmitterId().equals(userId)) {
                return true;
            }

            if (formData.getCurrentApproverIds() != null) {
                String[] approvers = formData.getCurrentApproverIds().split(",");
                for (String approver : approvers) {
                    if (approver.equals(userId)) {
                        return true;
                    }
                }
            }

            ProcessInstance instance = processEngineService.getInstanceByFormId(formId);
            List<ApprovalTask> tasks = approvalTaskRepository.findByApproverId(userId);
            for (ApprovalTask task : tasks) {
                if (task.getInstanceId().equals(instance.getInstanceId())) {
                    return true;
                }
            }

        } catch (Exception e) {
            logger.warn("检查表单查看权限失败: formId={}, userId={}, error={}", formId, userId, e.getMessage());
        }

        return false;
    }

    public boolean canProcessTask(String taskId, String userId, List<String> userRoles) {
        if (isAdmin(userRoles)) {
            return true;
        }

        try {
            ApprovalTask task = approvalTaskRepository.findByTaskId(taskId)
                    .orElseThrow(() -> new BusinessException(404, "审批任务不存在: " + taskId));

            return task.getApproverId().equals(userId);
        } catch (Exception e) {
            logger.warn("检查任务处理权限失败: taskId={}, userId={}, error={}", taskId, userId, e.getMessage());
        }

        return false;
    }

    public boolean canSubmitForm(String templateId, String userId, List<String> userRoles) {
        return true;
    }

    public boolean canViewProcess(String instanceId, String userId, List<String> userRoles) {
        if (isAdmin(userRoles)) {
            return true;
        }

        try {
            ProcessInstance instance = processEngineService.getInstanceByInstanceId(instanceId);

            if (instance.getSubmitterId().equals(userId)) {
                return true;
            }

            List<ApprovalTask> tasks = approvalTaskRepository.findByApproverId(userId);
            for (ApprovalTask task : tasks) {
                if (task.getInstanceId().equals(instanceId)) {
                    return true;
                }
            }

        } catch (Exception e) {
            logger.warn("检查流程查看权限失败: instanceId={}, userId={}, error={}", instanceId, userId, e.getMessage());
        }

        return false;
    }

    public boolean canManageTemplates(String userId, List<String> userRoles) {
        return isAdmin(userRoles) || hasRole(userRoles, "template_manager");
    }

    public boolean canManageProcesses(String userId, List<String> userRoles) {
        return isAdmin(userRoles) || hasRole(userRoles, "process_manager");
    }

    public boolean canViewStatistics(String userId, List<String> userRoles) {
        return isAdmin(userRoles) || hasRole(userRoles, "statistics_viewer");
    }

    public void checkViewFormPermission(String formId, String userId, List<String> userRoles) {
        if (!canViewForm(formId, userId, userRoles)) {
            throw new BusinessException(403, "您无权查看此表单");
        }
    }

    public void checkProcessTaskPermission(String taskId, String userId, List<String> userRoles) {
        if (!canProcessTask(taskId, userId, userRoles)) {
            throw new BusinessException(403, "您无权处理此审批任务");
        }
    }

    public void checkViewProcessPermission(String instanceId, String userId, List<String> userRoles) {
        if (!canViewProcess(instanceId, userId, userRoles)) {
            throw new BusinessException(403, "您无权查看此流程");
        }
    }

    public void checkManageTemplatesPermission(String userId, List<String> userRoles) {
        if (!canManageTemplates(userId, userRoles)) {
            throw new BusinessException(403, "您无权管理表单模板");
        }
    }

    public void checkManageProcessesPermission(String userId, List<String> userRoles) {
        if (!canManageProcesses(userId, userRoles)) {
            throw new BusinessException(403, "您无权管理流程定义");
        }
    }

    public void checkViewStatisticsPermission(String userId, List<String> userRoles) {
        if (!canViewStatistics(userId, userRoles)) {
            throw new BusinessException(403, "您无权查看统计数据");
        }
    }

    private boolean isAdmin(List<String> userRoles) {
        if (userRoles == null || userRoles.isEmpty()) {
            return false;
        }
        for (String role : userRoles) {
            if (ADMIN_ROLES.contains(role.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRole(List<String> userRoles, String requiredRole) {
        if (userRoles == null || userRoles.isEmpty()) {
            return false;
        }
        for (String role : userRoles) {
            if (role.equalsIgnoreCase(requiredRole)) {
                return true;
            }
        }
        return false;
    }
}
