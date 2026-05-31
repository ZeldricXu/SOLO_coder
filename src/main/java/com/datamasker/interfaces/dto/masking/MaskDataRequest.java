package com.datamasker.interfaces.dto.masking;

import lombok.Data;

import java.util.Map;

@Data
public class MaskDataRequest {

    private String userLevel;

    private Map<String, String> fields;

    private Map<String, String> fieldCategories;
}
