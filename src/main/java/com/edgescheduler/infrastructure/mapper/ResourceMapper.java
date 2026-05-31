package com.edgescheduler.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.domain.entity.ResourceEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ResourceMapper extends BaseMapper<ResourceEntity> {
}
