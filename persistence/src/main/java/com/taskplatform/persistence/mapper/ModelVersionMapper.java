package com.taskplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskplatform.persistence.entity.ModelVersion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ModelVersionMapper extends BaseMapper<ModelVersion> {
}
