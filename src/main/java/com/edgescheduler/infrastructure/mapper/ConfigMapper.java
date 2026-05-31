package com.edgescheduler.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.domain.entity.ConfigEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConfigMapper extends BaseMapper<ConfigEntity> {
}
