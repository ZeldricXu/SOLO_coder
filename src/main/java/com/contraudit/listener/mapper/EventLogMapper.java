package com.contraudit.listener.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.contraudit.listener.entity.EventLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EventLogMapper extends BaseMapper<EventLog> {
}
