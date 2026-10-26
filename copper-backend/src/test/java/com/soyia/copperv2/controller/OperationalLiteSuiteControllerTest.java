package com.soyia.copperv2.controller;

import com.soyia.copperv2.config.ApiExceptionHandler;
import com.soyia.copperv2.service.OperationalLiteSuiteService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OperationalLiteSuiteControllerTest {
    @Test
    void packageLabelMultipartBindsExpectedProductIdAsFormField() throws Exception {
        OperationalLiteSuiteService service = mock(OperationalLiteSuiteService.class);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new OperationalLiteSuiteController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        MockMultipartFile file = new MockMultipartFile(
                "file", "hongtai-label.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/document-intakes")
                        .file(file)
                        .param("documentType", "PACKAGE_LABEL")
                        .param("expectedProductId", "123"))
                .andExpect(status().isOk());

        verify(service).createIntake(
                eq("PACKAGE_LABEL"), eq(null), eq(123L), any(MockMultipartFile.class));
    }
}
