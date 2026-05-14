package com.library.librarymgmt.dto;

import lombok.Data;
import javax.validation.constraints.NotBlank;

@Data
public class ReturnRequest {
    @NotBlank(message = "借阅ID不能为空")
    private String borrow_id;
}
