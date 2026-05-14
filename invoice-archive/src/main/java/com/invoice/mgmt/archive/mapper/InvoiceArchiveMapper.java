package com.invoice.mgmt.archive.mapper;

import com.invoice.mgmt.common.entity.InvoiceArchive;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface InvoiceArchiveMapper {
    int insert(InvoiceArchive archive);

    InvoiceArchive findById(@Param("archiveId") String archiveId);

    List<InvoiceArchive> findByInvoiceId(@Param("invoiceId") String invoiceId);

    List<InvoiceArchive> findByType(@Param("archiveType") String archiveType);
}
