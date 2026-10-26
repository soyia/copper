package com.soyia.copperv2.config;

import com.soyia.copperv2.controller.AccountingController;
import com.soyia.copperv2.controller.CopperController;
import com.soyia.copperv2.controller.OrganizationSecurityController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.view.FragmentsRendering;
import org.springframework.web.servlet.view.UrlBasedViewResolver;
import org.springframework.web.servlet.view.xslt.XsltView;
import org.springframework.web.servlet.view.xslt.XsltViewResolver;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Re-evaluate the two Spring rendering advisories if a future feature opens these paths. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EnabledIfEnvironmentVariable(named = "COPPER_RELEASE_SECURITY_E2E", matches = "true")
class RenderingAttackSurfaceMySqlAcceptanceTest {
    @Autowired WebApplicationContext context;
    @Autowired Map<String, RequestMappingHandlerMapping> mappings;

    @Test void productionContextHasNoXsltOrSseFragmentRenderingPath() {
        assertTrue(context.getBeansOfType(XsltView.class).isEmpty());
        assertTrue(context.getBeansOfType(XsltViewResolver.class).isEmpty());
        context.getBeansOfType(UrlBasedViewResolver.class).forEach((name, resolver) -> {
            Class<?> viewClass = ReflectionTestUtils.invokeMethod(resolver, "getViewClass");
            assertTrue(viewClass == null || !XsltView.class.isAssignableFrom(viewClass), name);
        });
        assertFalse(ClassUtils.isPresent("org.springframework.web.reactive.DispatcherHandler",
                context.getClassLoader()), "A WebFlux stack requires a new rendering exposure review");

        int applicationHandlers = 0;
        Set<Class<?>> controllers = new HashSet<>();
        for (var mapping : mappings.values()) for (var entry : mapping.getHandlerMethods().entrySet()) {
            var handler = entry.getValue();
            if (!handler.getBeanType().getPackageName().startsWith("com.soyia.copperv2.")) continue;
            applicationHandlers++;
            controllers.add(handler.getBeanType());
            String label = handler.toString();
            assertTrue(AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), RestController.class), label);
            assertFalse(entry.getKey().getPatternValues().stream().anyMatch(path -> path.contains("**")), label);
            assertFalse(entry.getKey().getProducesCondition().getProducibleMediaTypes().stream()
                    .anyMatch(type -> type.getType().equals(MediaType.TEXT_EVENT_STREAM.getType())
                            && type.getSubtype().equals(MediaType.TEXT_EVENT_STREAM.getSubtype())), label);
            assertNoRenderingType(ResolvableType.forMethodReturnType(handler.getMethod()), new HashSet<>(), label);
        }
        assertTrue(controllers.containsAll(Set.of(CopperController.class, AccountingController.class,
                OrganizationSecurityController.class)), "The actual application controllers must be loaded");
        assertTrue(applicationHandlers > 100, "The actual application mappings must be loaded");
        System.out.printf("SPRING_RENDERING_EXPOSURE=NOT_PRESENT controllers=%d handlers=%d xslt=0 sseFragments=0%n",
                controllers.size(), applicationHandlers);
    }

    private void assertNoRenderingType(ResolvableType type, Set<ResolvableType> seen, String label) {
        if (type == ResolvableType.NONE || !seen.add(type)) return;
        Class<?> raw = type.resolve();
        if (raw != null) {
            assertFalse(View.class.isAssignableFrom(raw) || ModelAndView.class.isAssignableFrom(raw)
                    || FragmentsRendering.class.isAssignableFrom(raw) || ResponseBodyEmitter.class.isAssignableFrom(raw)
                    || raw.getName().equals("org.springframework.http.codec.ServerSentEvent"), label);
        }
        for (ResolvableType generic : type.getGenerics()) assertNoRenderingType(generic, seen, label);
    }
}
