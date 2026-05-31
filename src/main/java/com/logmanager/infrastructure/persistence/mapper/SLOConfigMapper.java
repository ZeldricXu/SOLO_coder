package com.logmanager.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.logmanager.infrastructure.persistence.entity.SLOConfigPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SLOConfigMapper extends BaseMapper<SLOConfigPO> {
}
