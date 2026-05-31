package com.didauth.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_address_book")
public class AddressBook extends BaseEntity {

    private String address;
    private String chainType;
    private String name;
    private String label;
    private String tags;
    private String userId;
    private Boolean isWhitelist;
    private Boolean isBlacklist;
}
