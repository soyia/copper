package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StorageCapacityServiceTest {
    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    void classifiesCapacityWithoutArtificialPlanQuota() {
        assertEquals("NORMAL", StorageCapacityService.classify(100 * GIB, 30 * GIB));
        assertEquals("WARNING", StorageCapacityService.classify(100 * GIB, 15 * GIB));
        assertEquals("CRITICAL", StorageCapacityService.classify(100 * GIB, 7 * GIB));
        assertEquals("DANGER", StorageCapacityService.classify(100 * GIB, 4 * GIB));
        assertEquals("UNKNOWN", StorageCapacityService.classify(0, 0));
    }

    @Test
    void absoluteSafetyFloorTakesPriorityOnSmallDisk() {
        assertEquals("DANGER", StorageCapacityService.classify(50 * GIB, GIB - 1));
        assertEquals("CRITICAL", StorageCapacityService.classify(20 * GIB, 2 * GIB - 1));
        assertEquals("WARNING", StorageCapacityService.classify(20 * GIB, 5 * GIB - 1));
    }
}
