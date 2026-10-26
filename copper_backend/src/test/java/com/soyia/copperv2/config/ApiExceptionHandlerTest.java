package com.soyia.copperv2.config;

import com.soyia.copperv2.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiExceptionHandlerTest {
    @Test
    void internalErrorDoesNotExposeOriginalExceptionMessage() {
        String jdbcError = "jdbc:mysql://host/db?pass" + "word=" + "secret-" + "value";
        ApiResponse<Void> response = new ApiExceptionHandler()
                .server(new IllegalStateException(jdbcError));

        assertFalse(response.message().contains("secret-" + "value"));
        assertFalse(response.message().contains("jdbc:mysql"));
        assertTrue(response.message().contains("追踪号"));
    }

    @Test
    void oversizedUploadReturnsPayloadTooLargeWithoutInternalError() throws NoSuchMethodException {
        ApiResponse<Void> response = new ApiExceptionHandler()
                .uploadTooLarge(new MaxUploadSizeExceededException(10 * 1024 * 1024));
        ResponseStatus status = ApiExceptionHandler.class
                .getMethod("uploadTooLarge", MaxUploadSizeExceededException.class)
                .getAnnotation(ResponseStatus.class);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, status.value());
        assertEquals("上传文件过大，单个文件不得超过 10MB，请压缩或拆分后重试", response.message());
        assertFalse(response.message().contains("追踪号"));
    }

    @Test
    void unsupportedMultipartMediaTypeReturnsClientErrorWithoutInternalTrace() throws NoSuchMethodException {
        ApiResponse<Void> response = new ApiExceptionHandler()
                .unsupportedMediaType(new HttpMediaTypeNotSupportedException("multipart part"));
        ResponseStatus status = ApiExceptionHandler.class
                .getMethod("unsupportedMediaType", HttpMediaTypeNotSupportedException.class)
                .getAnnotation(ResponseStatus.class);

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, status.value());
        assertEquals("上传请求格式不正确，请刷新页面后重试", response.message());
        assertFalse(response.message().contains("追踪号"));
    }
}
