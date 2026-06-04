package com.flowplatform.controller;

import com.flowplatform.entity.SysUser;
import com.flowplatform.service.NotificationService;
import com.flowplatform.service.ProcessInstanceService;
import com.flowplatform.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final ProcessInstanceService processInstanceService;
    private final NotificationService notificationService;
    private final SysUserService sysUserService;

    @GetMapping({"/", "/dashboard"})
    public String dashboard(Model model, Authentication auth) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        model.addAttribute("user", user);
        int pendingCount = processInstanceService.getPendingTasks(user.getId()).size();
        model.addAttribute("pendingCount", pendingCount);
        int unreadCount = notificationService.countUnread(user.getId());
        model.addAttribute("unreadCount", unreadCount);
        long totalInstances = processInstanceService.count();
        model.addAttribute("totalInstances", totalInstances);
        long myInstances = processInstanceService.getMyInstances(user.getId()).size();
        model.addAttribute("myInstances", myInstances);
        return "dashboard/index";
    }
}
