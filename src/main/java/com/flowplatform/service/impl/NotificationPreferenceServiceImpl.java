package com.flowplatform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flowplatform.entity.NotificationPreference;
import com.flowplatform.mapper.NotificationPreferenceMapper;
import com.flowplatform.service.NotificationPreferenceService;
import org.springframework.stereotype.Service;

@Service
public class NotificationPreferenceServiceImpl extends ServiceImpl<NotificationPreferenceMapper, NotificationPreference>
        implements NotificationPreferenceService {

    @Override
    public NotificationPreference getByUserId(Long userId) {
        return getOne(new LambdaQueryWrapper<NotificationPreference>()
                .eq(NotificationPreference::getUserId, userId));
    }

    @Override
    public boolean saveOrUpdatePreference(NotificationPreference preference) {
        NotificationPreference existing = getByUserId(preference.getUserId());
        if (existing != null) {
            preference.setId(existing.getId());
            return updateById(preference);
        }
        return save(preference);
    }
}
