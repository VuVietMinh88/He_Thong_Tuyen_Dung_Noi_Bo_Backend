package vn.ttcs.recruitment.config;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ApiCorsConfiguration implements WebMvcConfigurer {
    private final String[] allowedOrigins;

    public ApiCorsConfiguration(
            @Value("${app.cors.allowed-origins:http://localhost:5173}") String origins) {
        allowedOrigins = Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
        if (Arrays.asList(allowedOrigins).contains("*")) {
            throw new IllegalArgumentException("Configure explicit CORS origins instead of '*'");
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/health")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "HEAD", "OPTIONS")
                .allowedHeaders("Accept", "Content-Type")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
