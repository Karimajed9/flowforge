package com.karimmajed.flowforge.security;

import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@Configuration
public class SecurityWebTestConfiguration {

    @Bean
    MockMvcBuilderCustomizer defaultUserJwt() {
        return builder -> builder.defaultRequest(get("/").with(TestAccess.user()));
    }
}
