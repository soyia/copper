package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.StorageCapacityDtos.View;
import com.soyia.copperv2.service.StorageCapacityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StorageCapacityController {
    private final StorageCapacityService storageCapacityService;

    public StorageCapacityController(StorageCapacityService storageCapacityService) {
        this.storageCapacityService = storageCapacityService;
    }

    @GetMapping("/system/storage-capacity")
    public ApiResponse<View> capacity() {
        return ApiResponse.ok(storageCapacityService.view());
    }
}
