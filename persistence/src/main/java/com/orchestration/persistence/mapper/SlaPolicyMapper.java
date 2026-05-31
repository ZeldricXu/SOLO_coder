package com.orchestration.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orchestration.persistence.entity.SlaPolicy;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SlaPolicyMapper extends BaseMapper<SlaPolicy> {
}
