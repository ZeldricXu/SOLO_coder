package com.invoice.mgmt.common.mapper;

import com.invoice.mgmt.common.entity.Invoice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface InvoiceMapper {
    int insert(Invoice invoice);

    int update(Invoice invoice);

    Invoice findById(@Param("invoiceId") String invoiceId);

    Invoice findByNoAndCode(@Param("invoiceNo") String invoiceNo, @Param("invoiceCode") String invoiceCode);

    List<Invoice> findByStatus(@Param("invoiceStatus") String invoiceStatus);

    int updateStatus(@Param("invoiceId") String invoiceId, @Param("status") String status);
}
