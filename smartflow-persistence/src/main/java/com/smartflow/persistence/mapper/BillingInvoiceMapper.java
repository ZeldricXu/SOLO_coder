package com.smartflow.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartflow.persistence.entity.BillingInvoice;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BillingInvoiceMapper extends BaseMapper<BillingInvoice> {
}
