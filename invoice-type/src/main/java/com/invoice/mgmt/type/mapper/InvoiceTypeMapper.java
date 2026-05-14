package com.invoice.mgmt.type.mapper;

import com.invoice.mgmt.common.entity.InvoiceType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface InvoiceTypeMapper {
    int insert(InvoiceType invoiceType);

    int update(InvoiceType invoiceType);

    int deleteById(@Param("typeId") String typeId);

    InvoiceType findById(@Param("typeId") String typeId);

    InvoiceType findByCode(@Param("typeCode") String typeCode);

    List<InvoiceType> findAll();

    List<InvoiceType> findByEnabled(@Param("enabled") Boolean enabled);
}
