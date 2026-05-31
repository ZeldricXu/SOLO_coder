package com.contraudit.wallet.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("address_book")
public class AddressBook extends BaseEntity {

    private String address;

    private String chainType;

    private String label;

    private String description;

    private String category;

    private Integer isWhitelist;

    private Integer isBlacklist;
}
