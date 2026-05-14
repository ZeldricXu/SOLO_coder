package com.supplychain.contract.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.supplychain.common.entity.Contract;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ContractMapper extends BaseMapper<Contract> {}
