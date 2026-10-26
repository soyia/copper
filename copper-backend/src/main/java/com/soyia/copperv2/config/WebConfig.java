package com.soyia.copperv2.config;

import com.soyia.copperv2.security.SecurityInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final SecurityInterceptor securityInterceptor;
    private final List<String> allowedOrigins;
    private final boolean allowPrivateNetworkOrigins;

    public WebConfig(SecurityInterceptor securityInterceptor,
                     @Value("${copper.security.allowed-origins}") String configuredAllowedOrigins,
                     @Value("${copper.security.allow-private-network-origins:false}") boolean allowPrivateNetworkOrigins) {
        this.securityInterceptor = securityInterceptor;
        this.allowedOrigins = parseOrigins(configuredAllowedOrigins);
        this.allowPrivateNetworkOrigins = allowPrivateNetworkOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        CorsRegistration registration = registry.addMapping("/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new));
        if (allowPrivateNetworkOrigins) {
            registration.allowedOriginPatterns(privateNetworkOriginPatterns().toArray(String[]::new));
        }
        registration
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(securityInterceptor).addPathPatterns("/**");
    }

    static List<String> parseOrigins(String configuredAllowedOrigins) {
        if (configuredAllowedOrigins == null || configuredAllowedOrigins.isBlank()) {
            throw new IllegalStateException("COPPER_ALLOWED_ORIGINS 不能为空");
        }
        List<String> origins = Arrays.stream(configuredAllowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (origins.isEmpty() || origins.stream().anyMatch(value -> value.contains("*"))) {
            throw new IllegalStateException("COPPER_ALLOWED_ORIGINS 必须填写明确来源，禁止使用通配符");
        }
        return origins;
    }

    static List<String> privateNetworkOriginPatterns() {
        Stream<String> privatePrefixes = Stream.concat(
                Stream.of("10", "192.168"),
                IntStream.rangeClosed(16, 31).mapToObj(value -> "172." + value));
        return privatePrefixes
                .flatMap(prefix -> Stream.of(
                        "http://" + prefix + ".*:[*]",
                        "https://" + prefix + ".*:[*]"))
                .toList();
    }
}
