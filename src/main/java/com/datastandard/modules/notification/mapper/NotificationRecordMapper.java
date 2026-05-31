package com.datastandard.modules.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datastandard.modules.notification.entity.NotificationRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface NotificationRecordMapper extends BaseMapper<NotificationRecord> {

    @Select("SELECT * FROM notification_records WHERE trace_id = #{traceId} AND deleted = 0 ORDER BY created_at DESC")
    List<NotificationRecord> findByTraceId(@Param("traceId") String traceId);

    @Select("SELECT * FROM notification_records WHERE recipient = #{recipient} " +
            "AND created_at >= #{startTime} AND created_at < #{endTime} AND deleted = 0 " +
            "ORDER BY created_at DESC")
    List<NotificationRecord> findByRecipientAndTimeRange(
            @Param("recipient") String recipient,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);

    @Select("SELECT * FROM notification_records WHERE status = #{status} AND deleted = 0 " +
            "ORDER BY priority DESC, scheduled_time ASC LIMIT #{limit}")
    List<NotificationRecord> findPendingRecords(@Param("status") String status, @Param("limit") int limit);

    @Select("SELECT * FROM notification_records WHERE template_code = #{templateCode} " +
            "AND created_at >= #{startTime} AND created_at < #{endTime} AND deleted = 0")
    List<NotificationRecord> findByTemplateAndTimeRange(
            @Param("templateCode") String templateCode,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);

    @Select("SELECT * FROM notification_records WHERE retry_count < max_retries " +
            "AND status = 'FAILED' AND deleted = 0 " +
            "ORDER BY priority DESC, created_at ASC LIMIT #{limit}")
    List<NotificationRecord> findRetryableRecords(@Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM notification_records WHERE channel = #{channel} " +
            "AND created_at >= #{startTime} AND created_at < #{endTime} AND deleted = 0")
    long countByChannelAndTimeRange(
            @Param("channel") String channel,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);
}
