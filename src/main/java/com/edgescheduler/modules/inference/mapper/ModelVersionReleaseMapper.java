package com.edgescheduler.modules.inference.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.modules.inference.domain.ModelVersionRelease;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ModelVersionReleaseMapper extends BaseMapper<ModelVersionRelease> {
}
