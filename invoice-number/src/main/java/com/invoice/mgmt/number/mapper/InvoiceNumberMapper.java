package com.invoice.mgmt.number.mapper;

import com.invoice.mgmt.common.entity.InvoiceNumber;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface InvoiceNumberMapper {
    int insert(InvoiceNumber invoiceNumber);

    int update(InvoiceNumber invoiceNumber);

    int deleteById(@Param("id") Long id);

    InvoiceNumber findById(@Param("id") Long id);

    List<InvoiceNumber> findByType(@Param("invoiceType") String invoiceType);

    List<InvoiceNumber> findAvailableByType(@Param("invoiceType") String invoiceType);

    InvoiceNumber findFirstAvailable(@Param("invoiceType") String invoiceType);

    int updateUsedCount(@Param("id") Long id, @Param("usedCount") int usedCount, @Param("remainingCount") int remainingCount, @Param("currentNo") String currentNo);
}
