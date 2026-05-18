package com.bully;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Aplicación principal del Nodo Bully.
 *
 * Cada instancia de esta aplicación representa un nodo independiente
 * en el sistema distribuido. Se despliega como contenedor Docker con
 * una variable de entorno NODE_ID única.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties
public class BullyNodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(BullyNodeApplication.class, args);
    }
}
