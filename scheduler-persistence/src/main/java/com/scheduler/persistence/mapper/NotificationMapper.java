package com.scheduler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scheduler.persistence.entity.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    @Select("SELECT * FROM notifications WHERE status IN ('PENDING', 'FAILED') AND retry_count < max_retries")
    List<Notification> findPendingRetries();

    @Select("SELECT * FROM notifications WHERE notification_id = #{notificationId}")
    Notification findByNotificationId(@Param("notificationId") String notificationId);

    @Select("SELECT * FROM notifications WHERE trace_id = #{traceId}")
    List<Notification> findByTraceId(@Param("traceId") String traceId);
}
