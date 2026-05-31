package com.chaoslab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chaoslab.entity.CommandLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CommandLogMapper extends BaseMapper<CommandLog> {
}
