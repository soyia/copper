package com.soyia.copperv2.config;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.service.BusinessException;
import com.soyia.copperv2.security.ForbiddenException;
import com.soyia.copperv2.security.UnauthorizedException;
import com.soyia.copperv2.security.SecurityRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> unauthorized(UnauthorizedException ex) {
        return ApiResponse.fail(ex.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> forbidden(ForbiddenException ex) {
        return ApiResponse.fail(ex.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> business(BusinessException ex) {
        return ApiResponse.fail(ex.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> badRequest(Exception ex) {
        return ApiResponse.fail("请求参数不正确");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public ApiResponse<Void> unsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return ApiResponse.fail("上传请求格式不正确，请刷新页面后重试");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public ApiResponse<Void> uploadTooLarge(MaxUploadSizeExceededException ex) {
        return ApiResponse.fail("上传文件过大，单个文件不得超过 10MB，请压缩或拆分后重试");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> server(Exception ex) {
        SecurityRequestContext.Metadata metadata = SecurityRequestContext.get();
        String traceId = metadata == null ? "unknown" : metadata.traceId();
        log.error("未处理的服务器异常 traceId={} type={}", traceId, ex.getClass().getName());
        log.debug("未处理的服务器异常调试栈 traceId={}", traceId, ex);
        return ApiResponse.fail("服务器内部错误，请联系管理员并提供追踪号：" + traceId);
    }
}
