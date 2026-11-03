package com.focaAI.learningService.infraestructure.web.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/assistant/**")
                .allowedOrigins("http://localhost:4200") // troque pela origem do seu front
                .allowedMethods("POST", "GET", "OPTIONS")
                .allowedHeaders("*");
    }
}