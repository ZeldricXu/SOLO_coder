package com.exam.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class LoginVO {
    private String token;
    private Long userId;
    private String username;
    private String realName;
    private String avatar;
    private List<String> roles;
    private Long subjectId;
}
