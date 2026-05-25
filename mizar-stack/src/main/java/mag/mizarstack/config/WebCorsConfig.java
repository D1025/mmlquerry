package mag.mizarstack.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    private static final String[] DEFAULT_ALLOWED_ORIGIN_PATTERNS = new String[]{
            "http://localhost:*",
            "http://127.0.0.1:*",
            "https://localhost:*",
            "https://127.0.0.1:*"
    };

    private final String[] allowedOriginPatterns;

    public WebCorsConfig(
            @Value("${app.cors.allowed-origins:http://localhost:*,http://127.0.0.1:*}") String allowedOriginsRaw
    ) {
        this.allowedOriginPatterns = Arrays.stream(allowedOriginsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(
                        allowedOriginPatterns.length == 0
                                ? DEFAULT_ALLOWED_ORIGIN_PATTERNS
                                : allowedOriginPatterns
                )
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
