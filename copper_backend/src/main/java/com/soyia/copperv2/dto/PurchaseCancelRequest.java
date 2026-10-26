package com.soyia.copperv2.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PurchaseCancelRequest(
        @NotBlank(message = "请填写作废原因")
        @Size(max = 500, message = "作废原因不能超过500字")
        String reason
) {
}
