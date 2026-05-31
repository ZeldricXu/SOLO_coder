package com.chainetl.modules.events.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chainetl.modules.events.model.ContractEventListener;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ContractEventListenerMapper extends BaseMapper<ContractEventListener> {
}
