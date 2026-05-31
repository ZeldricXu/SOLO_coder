package com.tsdbproxy.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tsdbproxy.common.entity.LifecycleLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LifecycleLogMapper extends BaseMapper<LifecycleLog> {
}
