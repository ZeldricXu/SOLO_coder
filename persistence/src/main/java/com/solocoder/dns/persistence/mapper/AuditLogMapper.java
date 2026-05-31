package com.solocoder.dns.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solocoder.dns.persistence.entity.AuditLogPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogPO> {
}
