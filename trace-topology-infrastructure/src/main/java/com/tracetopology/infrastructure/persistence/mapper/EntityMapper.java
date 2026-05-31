package com.tracetopology.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tracetopology.infrastructure.persistence.entity.EntityPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EntityMapper extends BaseMapper<EntityPO> {
}
