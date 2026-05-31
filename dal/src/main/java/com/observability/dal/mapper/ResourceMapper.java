package com.observability.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.observability.common.entity.ResourceEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ResourceMapper extends BaseMapper<ResourceEntity> {
}
