package com.library.librarymgmt.dto;

import lombok.Data;
import javax.validation.constraints.NotBlank;

@Data
public class ReaderRequest {
    @NotBlank(message = "读者姓名不能为空")
    private String reader_name;

    private String reader_phone;

    @NotBlank(message = "读者类型不能为空")
    private String reader_type;

    private Integer borrow_limit;
}
