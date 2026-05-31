package com.solo.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.config.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
