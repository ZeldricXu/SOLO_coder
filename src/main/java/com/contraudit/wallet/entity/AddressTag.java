package com.contraudit.wallet.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("address_tag")
public class AddressTag extends BaseEntity {

    private String addressBookId;

    private String tagName;

    private String tagValue;
}
