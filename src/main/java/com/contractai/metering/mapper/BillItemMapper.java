package com.contractai.metering.mapper;

import com.contractai.common.mapper.BaseMapper;
import com.contractai.metering.entity.BillItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BillItemMapper extends BaseMapper<BillItem> {
}
