package com.iotplatform.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iotplatform.notification.entity.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    @Select("SELECT * FROM notification WHERE notification_id = #{notificationId}")
    Optional<Notification> findByNotificationId(@Param("notificationId") String notificationId);

    @Select("SELECT * FROM notification WHERE status = 'pending' AND retry_count < max_retries " +
            "ORDER BY created_at ASC LIMIT #{limit}")
    List<Notification> findPendingNotifications(@Param("limit") int limit);

    @Update("UPDATE notification SET status = #{status}, retry_count = retry_count + 1, " +
            "error_detail = #{errorDetail} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("errorDetail") String errorDetail);

    @Update("UPDATE notification SET status = 'sent', sent_at = #{sentAt} WHERE id = #{id}")
    int markAsSent(@Param("id") Long id, @Param("sentAt") LocalDateTime sentAt);

    IPage<Notification> selectNotificationPage(Page<Notification> page,
                                                @Param("channelType") String channelType,
                                                @Param("status") String status,
                                                @Param("recipient") String recipient);

    @Select("SELECT COUNT(*) FROM notification WHERE status = #{status}")
    long countByStatus(@Param("status") String status);
}
