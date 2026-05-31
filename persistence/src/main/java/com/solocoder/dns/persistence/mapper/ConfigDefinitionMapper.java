package com.solocoder.dns.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solocoder.dns.persistence.entity.ConfigDefinitionPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConfigDefinitionMapper extends BaseMapper<ConfigDefinitionPO> {
}
