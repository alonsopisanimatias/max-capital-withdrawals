package com.maxcapital.withdrawals.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for local frontend development only. In docker-compose, nginx proxies {@code /api} to
 * the backend on the same origin the browser loaded the page from (see frontend/nginx.conf),
 * so the browser never makes a cross-origin request there and this never gets exercised. It
 * exists for {@code npm run dev} (Vite on :5173) hitting a backend on :8081/:8082 directly
 * without going through Vite's own dev proxy.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
