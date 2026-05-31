package com.contractai.skill.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("employee")
public class Employee extends TenantBaseEntity {

    @TableField("employee_no")
    private String employeeNo;

    @TableField("name")
    private String name;

    @TableField("department")
    private String department;

    @TableField("position")
    private String position;

    @TableField("email")
    private String email;

    @TableField("phone")
    private String phone;

    @TableField(value = "attributes", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> attributes;
}
