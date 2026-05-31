package com.datamasker.interfaces.dto.masking;

import lombok.Data;

import java.util.List;

@Data
public class MaskDataResponse {

    private List<MaskedField> results;

    @Data
    public static class MaskedField {

        private String fieldName;

        private String maskedValue;

        private String strategy;

        private boolean wasMasked;
    }
}
