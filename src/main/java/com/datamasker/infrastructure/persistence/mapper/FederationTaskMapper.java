package com.datamasker.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datamasker.infrastructure.persistence.entity.FederationTaskEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FederationTaskMapper extends BaseMapper<FederationTaskEntity> {
}
