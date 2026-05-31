package com.solo.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.config.entity.Event;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EventMapper extends BaseMapper<Event> {
}
