package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileStorageServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesCopiesReadsAndDeletesWithinConfiguredRoot() {
        LocalFileStorageService storage = new LocalFileStorageService(temporaryDirectory.toString());
        byte[] content = "delivery-note".getBytes(StandardCharsets.UTF_8);

        String original = storage.store(content, "inbounds/42", ".jpg");
        String snapshot = storage.copy(original, "inbound-review-snapshots/7", ".jpg");

        assertArrayEquals(content, storage.read(original));
        assertArrayEquals(content, storage.read(snapshot));
        assertTrue(storage.exists(original));
        assertTrue(storage.exists(snapshot));

        storage.deleteNow(original);
        assertFalse(storage.exists(original));
        assertTrue(storage.exists(snapshot));
    }

    @Test
    void rejectsPathsOutsideConfiguredRoot() {
        LocalFileStorageService storage = new LocalFileStorageService(temporaryDirectory.toString());

        assertThrows(IllegalArgumentException.class,
                () -> storage.store(new byte[]{1}, "../outside", ".jpg"));
        assertThrows(BusinessException.class, () -> storage.read("../outside.jpg"));
        assertThrows(BusinessException.class, () -> storage.read(temporaryDirectory.resolve("absolute.jpg").toString()));
    }

    @Test
    void removesNewFileWhenDatabaseTransactionRollsBack() {
        LocalFileStorageService storage = new LocalFileStorageService(temporaryDirectory.toString());
        TransactionSynchronizationManager.initSynchronization();
        try {
            String path = storage.store(new byte[]{1, 2, 3}, "inbounds/9", ".png");
            assertTrue(storage.exists(path));

            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            assertFalse(storage.exists(path));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
