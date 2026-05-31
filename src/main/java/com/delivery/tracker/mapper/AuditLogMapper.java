package com.delivery.tracker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.delivery.tracker.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
