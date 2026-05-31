package com.contraudit.listener.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.contraudit.listener.entity.EventListener;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EventListenerMapper extends BaseMapper<EventListener> {
}
