package com.contractai.metering.mapper;

import com.contractai.common.mapper.BaseMapper;
import com.contractai.metering.entity.Bill;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BillMapper extends BaseMapper<Bill> {
}
