package com.bully.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Configuración del nodo leída desde application.yml y variables de entorno.
 * Las propiedades con prefijo "bully" mapean directamente a esta clase.
 */
@Configuration
@ConfigurationProperties(prefix = "bully")
@Getter
@Setter
public class NodeConfig {

    /**
     * ID único de este nodo. Inyectado via variable de entorno NODE_ID.
     * Un ID mayor significa mayor prioridad en la elección.
     */
    private int nodeId;

    /**
     * Puerto en el que este nodo escucha peticiones REST.
     */
    private int port = 8080;

    /**
     * Mapa de nodos conocidos: ID → URL base del proxy Toxiproxy.
     * Ejemplo: {1: "http://proxy-node1:8881", 2: "http://proxy-node2:8882"}
     * El tráfico pasa siempre por Toxiproxy para simular fallos de red.
     */
    private Map<Integer, String> peers;

    /**
     * Tiempo máximo (ms) para esperar una respuesta OK después de enviar ELECTION.
     * En teoría es instantáneo; en la práctica depende de la latencia de red.
     * Ajustar según los timeouts configurados en Toxiproxy.
     */
    private long electionTimeoutMs = 3000;

    /**
     * Intervalo (ms) entre heartbeats enviados al coordinador.
     */
    private long heartbeatIntervalMs = 2000;

    /**
     * Tiempo máximo (ms) para esperar respuesta a un heartbeat.
     * Si el coordinador no responde en este tiempo, se considera caído.
     */
    private long heartbeatTimeoutMs = 4000;

    /**
     * Tiempo de espera (ms) antes de reintentar contactar al coordinador
     * después de detectar una posible falla.
     */
    private long coordinatorCheckDelayMs = 1000;

    /**
     * Número de heartbeats fallidos consecutivos antes de iniciar elección.
     * Reduce falsos positivos por latencia temporal.
     */
    private int maxMissedHeartbeats = 2;

    /**
     * Retorna todos los IDs de nodos con mayor prioridad que este nodo.
     */
    public List<Integer> getHigherPriorityNodes() {
        return peers.keySet().stream()
                .filter(id -> id > nodeId)
                .sorted()
                .toList();
    }

    /**
     * Retorna todos los IDs de nodos con menor prioridad que este nodo.
     */
    public List<Integer> getLowerPriorityNodes() {
        return peers.keySet().stream()
                .filter(id -> id < nodeId)
                .sorted()
                .toList();
    }

    /**
     * Retorna la URL del proxy Toxiproxy para comunicarse con el nodo dado.
     */
    public String getPeerUrl(int targetNodeId) {
        return peers.get(targetNodeId);
    }
}
