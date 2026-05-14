package com.invoice.mgmt.status.mapper;

import com.invoice.mgmt.common.entity.InvoiceStatusLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface InvoiceStatusLogMapper {
    int insert(InvoiceStatusLog log);

    InvoiceStatusLog findById(@Param("id") Long id);

    List<InvoiceStatusLog> findByInvoiceId(@Param("invoiceId") String invoiceId);

    InvoiceStatusLog findLatestByInvoiceId(@Param("invoiceId") String invoiceId);
}
