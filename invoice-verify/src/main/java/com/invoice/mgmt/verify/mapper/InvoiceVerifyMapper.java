package com.invoice.mgmt.verify.mapper;

import com.invoice.mgmt.common.entity.InvoiceVerify;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface InvoiceVerifyMapper {
    int insert(InvoiceVerify verify);

    InvoiceVerify findById(@Param("verifyId") String verifyId);

    List<InvoiceVerify> findByInvoiceId(@Param("invoiceId") String invoiceId);

    InvoiceVerify findLatestByInvoiceId(@Param("invoiceId") String invoiceId);
}
