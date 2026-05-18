package com.bully.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.web.client.RestTemplate;

/**
 * Configuración del cliente HTTP REST usado para comunicación entre nodos.
 */
@Configuration
@EnableRetry
public class RestClientConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1500);
        factory.setReadTimeout(3500);
        return new RestTemplate(factory);
    }
}
