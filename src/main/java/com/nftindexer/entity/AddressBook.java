package com.nftindexer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("address_book")
public class AddressBook extends BaseEntity {

    private String entryId;
    private String address;
    private String chainId;
    private String label;
    private String category;
    private String description;
    private String tags;
    private String createdBy;
    private Map<String, Object> metadata;
}
