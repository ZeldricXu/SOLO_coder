package com.dynamiclog.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dynamiclog.common.entity.Notification;
import com.dynamiclog.common.enums.NotificationStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    @Select("SELECT * FROM notification WHERE status = #{status} AND deleted = 0 ORDER BY priority DESC, created_at ASC")
    List<Notification> findByStatus(@Param("status") NotificationStatus status);

    @Select("SELECT * FROM notification WHERE trace_id = #{traceId} AND deleted = 0")
    List<Notification> findByTraceId(@Param("traceId") String traceId);
}
