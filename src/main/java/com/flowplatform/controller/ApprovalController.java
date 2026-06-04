package com.flowplatform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowplatform.common.R;
import com.flowplatform.entity.*;
import com.flowplatform.mapper.ProcessTaskMapper;
import com.flowplatform.service.NotificationService;
import com.flowplatform.service.ProcessInstanceService;
import com.flowplatform.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
@RequestMapping("/approval")
public class ApprovalController {

    private final ProcessInstanceService processInstanceService;
    private final ProcessTaskMapper processTaskMapper;
    private final SysUserService sysUserService;
    private final NotificationService notificationService;

    @GetMapping
    public String index() {
        return "redirect:/approval/pending";
    }

    @GetMapping("/pending")
    public String pending(Authentication auth, Model model,
                          @RequestParam(required = false) String keyword,
                          @RequestParam(required = false) String processType,
                          @RequestParam(required = false) String startDate,
                          @RequestParam(required = false) String endDate) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        List<ProcessTask> tasks = processInstanceService.getPendingTasks(user.getId());
        tasks.forEach(t -> {
            if (t.getAssigneeId() != null) {
                SysUser assignee = sysUserService.getById(t.getAssigneeId());
                if (assignee != null) t.setAssigneeName(assignee.getRealName() != null ? assignee.getRealName() : assignee.getUsername());
            }
            if (t.getInstanceId() != null) {
                ProcessInstance inst = processInstanceService.getById(t.getInstanceId());
                if (inst != null) {
                    t.setInstanceTitle(inst.getTitle());
                    if (inst.getInitiatorId() != null) {
                        SysUser initiator = sysUserService.getById(inst.getInitiatorId());
                        if (initiator != null) t.setProcessName(initiator.getRealName() != null ? initiator.getRealName() : initiator.getUsername());
                    }
                }
            }
        });
        model.addAttribute("tasks", tasks);
        model.addAttribute("keyword", keyword);
        model.addAttribute("processType", processType);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("activeTab", "pending");
        return "approval/pending";
    }

    @GetMapping("/completed")
    public String completed(Authentication auth, Model model) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        List<ProcessTask> tasks = processInstanceService.getCompletedTasks(user.getId());
        tasks.forEach(t -> {
            if (t.getAssigneeId() != null) {
                SysUser assignee = sysUserService.getById(t.getAssigneeId());
                if (assignee != null) t.setAssigneeName(assignee.getRealName() != null ? assignee.getRealName() : assignee.getUsername());
            }
            if (t.getInstanceId() != null) {
                ProcessInstance inst = processInstanceService.getById(t.getInstanceId());
                if (inst != null) {
                    t.setInstanceTitle(inst.getTitle());
                }
            }
        });
        model.addAttribute("tasks", tasks);
        model.addAttribute("activeTab", "completed");
        return "approval/completed";
    }

    @GetMapping("/initiated")
    public String initiated(Authentication auth, Model model) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        List<ProcessInstance> instances = processInstanceService.getMyInstances(user.getId());
        instances.forEach(inst -> {
            if (inst.getInitiatorId() != null) {
                SysUser initiator = sysUserService.getById(inst.getInitiatorId());
                if (initiator != null) inst.setInitiatorName(initiator.getRealName() != null ? initiator.getRealName() : initiator.getUsername());
            }
            List<ProcessTask> pendingTasks = processTaskMapper.selectList(
                    new LambdaQueryWrapper<ProcessTask>()
                            .eq(ProcessTask::getInstanceId, inst.getId())
                            .eq(ProcessTask::getStatus, "PENDING"));
            if (!pendingTasks.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (ProcessTask pt : pendingTasks) {
                    if (pt.getAssigneeId() != null) {
                        SysUser assignee = sysUserService.getById(pt.getAssigneeId());
                        if (assignee != null) names.add(assignee.getRealName() != null ? assignee.getRealName() : assignee.getUsername());
                    }
                }
                inst.setCurrentNodes(String.join(", ", names));
            }
        });
        model.addAttribute("instances", instances);
        model.addAttribute("activeTab", "initiated");
        return "approval/initiated";
    }

    @GetMapping("/detail/{instanceId}")
    public String detail(@PathVariable Long instanceId, Authentication auth, Model model) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        ProcessInstance instance = processInstanceService.getById(instanceId);
        if (instance == null) return "redirect:/approval/pending";

        if (instance.getInitiatorId() != null) {
            SysUser initiator = sysUserService.getById(instance.getInitiatorId());
            if (initiator != null) instance.setInitiatorName(initiator.getRealName() != null ? initiator.getRealName() : initiator.getUsername());
        }

        List<ProcessTask> allTasks = processTaskMapper.selectList(
                new LambdaQueryWrapper<ProcessTask>()
                        .eq(ProcessTask::getInstanceId, instanceId)
                        .orderByAsc(ProcessTask::getCreateTime));
        allTasks.forEach(t -> {
            if (t.getAssigneeId() != null) {
                SysUser assignee = sysUserService.getById(t.getAssigneeId());
                if (assignee != null) t.setAssigneeName(assignee.getRealName() != null ? assignee.getRealName() : assignee.getUsername());
            }
        });

        boolean isAssignee = allTasks.stream()
                .anyMatch(t -> "PENDING".equals(t.getStatus()) && user.getId().equals(t.getAssigneeId()));
        ProcessTask currentTask = allTasks.stream()
                .filter(t -> "PENDING".equals(t.getStatus()) && user.getId().equals(t.getAssigneeId()))
                .findFirst().orElse(null);

        model.addAttribute("instance", instance);
        model.addAttribute("tasks", allTasks);
        model.addAttribute("isAssignee", isAssignee);
        model.addAttribute("currentTask", currentTask);
        model.addAttribute("currentUser", user);
        return "approval/detail";
    }

    @PostMapping("/approve/{taskId}")
    @ResponseBody
    public R<?> approve(@PathVariable Long taskId, @RequestParam(required = false) String comment, Authentication auth) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        boolean success = processInstanceService.approveTask(taskId, user.getId(), comment);
        if (success) {
            ProcessTask task = processTaskMapper.selectById(taskId);
            if (task != null) {
                notificationService.sendNotification(
                        processInstanceService.getById(task.getInstanceId()).getInitiatorId(),
                        "审批通知", "您的流程已被同意", "APPROVAL", "INSTANCE", task.getInstanceId());
            }
        }
        return success ? R.ok() : R.fail("审批操作失败");
    }

    @PostMapping("/reject/{taskId}")
    @ResponseBody
    public R<?> reject(@PathVariable Long taskId, @RequestParam(required = false) String comment, Authentication auth) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        boolean success = processInstanceService.rejectTask(taskId, user.getId(), comment);
        if (success) {
            ProcessTask task = processTaskMapper.selectById(taskId);
            if (task != null) {
                notificationService.sendNotification(
                        processInstanceService.getById(task.getInstanceId()).getInitiatorId(),
                        "审批通知", "您的流程已被拒绝", "APPROVAL", "INSTANCE", task.getInstanceId());
            }
        }
        return success ? R.ok() : R.fail("拒绝操作失败");
    }

    @PostMapping("/return/{taskId}")
    @ResponseBody
    public R<?> returnTask(@PathVariable Long taskId, @RequestParam(required = false) String comment, Authentication auth) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        boolean success = processInstanceService.returnTask(taskId, user.getId(), comment);
        if (success) {
            ProcessTask task = processTaskMapper.selectById(taskId);
            if (task != null) {
                notificationService.sendNotification(
                        processInstanceService.getById(task.getInstanceId()).getInitiatorId(),
                        "审批通知", "您的流程已被退回", "APPROVAL", "INSTANCE", task.getInstanceId());
            }
        }
        return success ? R.ok() : R.fail("退回操作失败");
    }

    @PostMapping("/transfer/{taskId}")
    @ResponseBody
    public R<?> transfer(@PathVariable Long taskId, @RequestParam Long toUserId,
                         @RequestParam(required = false) String comment, Authentication auth) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        boolean success = processInstanceService.transferTask(taskId, user.getId(), toUserId, comment);
        if (success) {
            notificationService.sendNotification(toUserId,
                    "审批转交", "您收到一条转交的审批任务", "APPROVAL", "TASK", taskId);
        }
        return success ? R.ok() : R.fail("转交操作失败");
    }

    @PostMapping("/addSign/{taskId}")
    @ResponseBody
    public R<?> addSign(@PathVariable Long taskId, @RequestParam Long userId,
                        @RequestParam(required = false) String comment, Authentication auth) {
        SysUser currentUser = sysUserService.findByUsername(auth.getName());
        boolean success = processInstanceService.addSignTask(taskId, userId, comment);
        if (success) {
            notificationService.sendNotification(userId,
                    "加签审批", "您收到一条加签审批任务", "APPROVAL", "TASK", taskId);
        }
        return success ? R.ok() : R.fail("加签操作失败");
    }

    @PostMapping("/urge/{instanceId}")
    @ResponseBody
    public R<?> urge(@PathVariable Long instanceId, Authentication auth) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        boolean success = processInstanceService.urgeInstance(instanceId, user.getId());
        if (success) {
            List<ProcessTask> pendingTasks = processTaskMapper.selectList(
                    new LambdaQueryWrapper<ProcessTask>()
                            .eq(ProcessTask::getInstanceId, instanceId)
                            .eq(ProcessTask::getStatus, "PENDING"));
            for (ProcessTask task : pendingTasks) {
                if (task.getAssigneeId() != null) {
                    notificationService.sendNotification(task.getAssigneeId(),
                            "催办提醒", "您有一条待审批任务被催办，请尽快处理", "URGE", "INSTANCE", instanceId);
                }
            }
        }
        return success ? R.ok() : R.fail("催办操作失败");
    }

    @PostMapping("/batchApprove")
    @ResponseBody
    public R<?> batchApprove(@RequestBody Map<String, Object> params, Authentication auth) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        @SuppressWarnings("unchecked")
        List<Integer> taskIds = (List<Integer>) params.get("taskIds");
        String comment = (String) params.getOrDefault("comment", "");
        if (taskIds == null || taskIds.isEmpty()) {
            return R.fail("请选择要审批的任务");
        }
        int successCount = 0;
        for (Integer taskId : taskIds) {
            boolean success = processInstanceService.approveTask(taskId.longValue(), user.getId(), comment);
            if (success) successCount++;
        }
        return R.ok("成功审批 " + successCount + " 条任务");
    }
}
