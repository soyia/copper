package com.soyia.copperv2.security;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

@ControllerAdvice
public class SecurityRequestBodyAdvice extends RequestBodyAdviceAdapter {
    private static final Map<String, String> MASTER_REFERENCES = Map.ofEntries(
            Map.entry("partnerid", "PARTNER"),
            Map.entry("customerid", "PARTNER"),
            Map.entry("supplierid", "PARTNER"),
            Map.entry("productid", "PRODUCT"),
            Map.entry("warehouseid", "WAREHOUSE")
    );
    private static final Map<String, String> DOCUMENT_REFERENCES = Map.ofEntries(
            Map.entry("purchaseorderid", "PURCHASE_ORDER"),
            Map.entry("inboundid", "INBOUND"),
            Map.entry("salesorderid", "SALES_ORDER"),
            Map.entry("outboundid", "OUTBOUND")
    );

    private final BusinessDataScopeService dataScopeService;

    public SecurityRequestBodyAdvice(BusinessDataScopeService dataScopeService) {
        this.dataScopeService = dataScopeService;
    }

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        if (SecurityContext.get() != null) inspect(body, new IdentityHashMap<>(), 0);
        return body;
    }

    private void inspect(Object value, IdentityHashMap<Object, Boolean> visited, int depth) {
        if (value == null || depth > 8 || terminal(value.getClass()) || visited.put(value, Boolean.TRUE) != null) return;
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> inspect(item, visited, depth + 1));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                assertReference(String.valueOf(key), item);
                inspect(item, visited, depth + 1);
            });
            return;
        }
        if (!value.getClass().isRecord()) return;
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            try {
                Object item = component.getAccessor().invoke(value);
                assertReference(component.getName(), item);
                inspect(item, visited, depth + 1);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("无法检查请求数据范围", ex);
            }
        }
    }

    private void assertReference(String name, Object value) {
        if (!(value instanceof Number number) || number.longValue() == 0) return;
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String master = MASTER_REFERENCES.get(normalized);
        if (master != null) dataScopeService.assertMasterAllowed(master, number.longValue());
        String document = DOCUMENT_REFERENCES.get(normalized);
        if (document != null) dataScopeService.assertDocumentAllowed(document, number.longValue());
    }

    private boolean terminal(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || type.getName().startsWith("java.lang.")
                || type.getName().startsWith("java.time.") || type.getName().startsWith("java.math.");
    }
}
