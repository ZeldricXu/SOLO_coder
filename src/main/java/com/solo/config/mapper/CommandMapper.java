package com.solo.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.config.entity.Command;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CommandMapper extends BaseMapper<Command> {
}
