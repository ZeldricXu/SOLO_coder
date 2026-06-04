package com.flowplatform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowplatform.common.R;
import com.flowplatform.entity.Notification;
import com.flowplatform.entity.NotificationPreference;
import com.flowplatform.entity.SysUser;
import com.flowplatform.service.NotificationPreferenceService;
import com.flowplatform.service.NotificationService;
import com.flowplatform.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/notification")
public class NotificationController {

    private final NotificationService notificationService;
    private final SysUserService sysUserService;
    private final NotificationPreferenceService notificationPreferenceService;

    @GetMapping
    public String list(Authentication auth, Model model,
                       @RequestParam(required = false) String type,
                       @RequestParam(required = false) String startDate,
                       @RequestParam(required = false) String endDate,
                       @RequestParam(defaultValue = "1") int page,
                       @RequestParam(defaultValue = "10") int size) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, user.getId())
                .orderByDesc(Notification::getCreateTime);
        if (type != null && !type.isEmpty()) {
            wrapper.eq(Notification::getNotificationType, type);
        }
        if (startDate != null && !startDate.isEmpty()) {
            wrapper.ge(Notification::getCreateTime, startDate + " 00:00:00");
        }
        if (endDate != null && !endDate.isEmpty()) {
            wrapper.le(Notification::getCreateTime, endDate + " 23:59:59");
        }
        IPage<Notification> pageResult = notificationService.page(new Page<>(page, size), wrapper);
        model.addAttribute("notifications", pageResult.getRecords());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", pageResult.getPages());
        model.addAttribute("type", type);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("activeTab", "all");
        model.addAttribute("unreadCount", notificationService.countUnread(user.getId()));
        return "notification/list";
    }

    @GetMapping("/unread")
    public String unread(Authentication auth, Model model) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        List<Notification> notifications = notificationService.list(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, user.getId())
                .eq(Notification::getIsRead, 0)
                .orderByDesc(Notification::getCreateTime));
        model.addAttribute("notifications", notifications);
        model.addAttribute("activeTab", "unread");
        return "notification/list :: notificationList";
    }

    @PostMapping("/read/{id}")
    @ResponseBody
    public R<?> markRead(@PathVariable Long id) {
        boolean success = notificationService.markRead(id);
        return success ? R.ok() : R.fail("操作失败");
    }

    @PostMapping("/read-all")
    @ResponseBody
    public R<?> markAllRead(Authentication auth) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        boolean success = notificationService.markAllRead(user.getId());
        return success ? R.ok() : R.fail("操作失败");
    }

    @GetMapping("/preferences")
    public String preferences(Authentication auth, Model model) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        NotificationPreference pref = notificationPreferenceService.getByUserId(user.getId());
        if (pref == null) {
            pref = new NotificationPreference();
            pref.setUserId(user.getId());
            pref.setEnableInApp(1);
            pref.setEnableEmail(0);
            pref.setEnableWechat(0);
            pref.setTaskArrival(1);
            pref.setTaskTimeout(1);
            pref.setTaskComplete(1);
        }
        model.addAttribute("preference", pref);
        return "notification/preferences";
    }

    @PostMapping("/preferences/save")
    @ResponseBody
    public R<?> savePreferences(@ModelAttribute NotificationPreference preference, Authentication auth) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        preference.setUserId(user.getId());
        boolean success = notificationPreferenceService.saveOrUpdatePreference(preference);
        return success ? R.ok() : R.fail("保存失败");
    }

    @GetMapping("/api/unread-count")
    @ResponseBody
    public R<Integer> unreadCount(Authentication auth) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        int count = notificationService.countUnread(user.getId());
        return R.ok(count);
    }
}
