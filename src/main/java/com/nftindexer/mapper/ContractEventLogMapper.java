package com.nftindexer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nftindexer.entity.ContractEventLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ContractEventLogMapper extends BaseMapper<ContractEventLog> {
}
