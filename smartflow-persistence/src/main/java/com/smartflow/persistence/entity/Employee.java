package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_employee")
public class Employee extends BaseEntity {

    private String employeeNo;
    private String name;
    private String email;
    private String phone;
    private String department;
    private String position;
    private Integer level;
    private Integer currentLoad;
    private Integer maxLoad;
    private Integer available;
    private String skills;
    private String workingHours;
    private String timezone;
}
