package com.chain.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chain.infrastructure.persistence.entity.ContractEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ContractEventMapper extends BaseMapper<ContractEvent> {
}
