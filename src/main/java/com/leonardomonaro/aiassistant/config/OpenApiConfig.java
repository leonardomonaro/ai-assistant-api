package com.leonardomonaro.aiassistant.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI aiAssistantOpenApi() {
        return new OpenAPI().info(new Info()
                .title("AI Assistant API")
                .description("API REST para conversas com um assistente de inteligência artificial.")
                .version("1.0.0")
                .contact(new Contact().name("Leonardo Monaro"))
                .license(new License().name("MIT License")));
    }
}
