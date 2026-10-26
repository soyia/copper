package com.soyia.copperv2.dto;

public record WarehouseDto(
        Long id,
        String name,
        String location,
        String receivingAddresses,
        String status
) {
}
