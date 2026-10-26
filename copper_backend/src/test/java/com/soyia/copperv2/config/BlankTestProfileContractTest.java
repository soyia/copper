package com.soyia.copperv2.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlankTestProfileContractTest {

    @Test
    void blankTestProfileKeepsAgentRuntimeIsolatedFromTheNormalLocalDatabase() throws IOException {
        String profile = Files.readString(Path.of("src/main/resources/application-blank-test.yml"));

        assertTrue(profile.contains("COPPER_BLANK_TEST_PORT:8193"));
        assertTrue(profile.contains("127.0.0.1:3307/copper_v2_blank_test"));
        assertTrue(profile.contains("COPPER_BLANK_TEST_DATA_ROOT"));
        assertTrue(profile.contains("COPPER_BLANK_TEST_BOOTSTRAP_CREDENTIAL_FILE"));
        assertTrue(profile.contains("COPPER_BLANK_TEST_OCR_ENABLED:false"));
        assertFalse(profile.contains("local-database:3306/copper_v2?"));
        assertFalse(profile.contains("COPPER_PORT:8191"));
    }
}
