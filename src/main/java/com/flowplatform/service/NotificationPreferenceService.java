package com.flowplatform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.flowplatform.entity.NotificationPreference;

public interface NotificationPreferenceService extends IService<NotificationPreference> {
    NotificationPreference getByUserId(Long userId);
    boolean saveOrUpdatePreference(NotificationPreference preference);
}
