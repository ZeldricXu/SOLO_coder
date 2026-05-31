package com.observability.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.observability.common.entity.RunInstanceEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RunInstanceMapper extends BaseMapper<RunInstanceEntity> {
}
