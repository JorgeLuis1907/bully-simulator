package com.bully.service;

import com.bully.config.NodeConfig;
import com.bully.model.BullyMessage;
import com.bully.model.MessageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

/**
 * Servicio de comunicación de red entre nodos.
 *
 * Toda comunicación pasa por Toxiproxy, lo que permite simular:
 *   - Latencia artificial (latency toxic)
 *   - Variación de latencia (jitter toxic)
 *   - Ancho de banda limitado (bandwidth toxic)
 *   - Timeouts completos / nodo caído (timeout toxic)
 *
 * DIFERENCIA TEORÍA vs PRÁCTICA:
 * La teoría del algoritmo Bully asume comunicación instantánea y
 * determinista. En la práctica, con Toxiproxy:
 *   1. Un mensaje ELECTION puede llegar después del timeout → el nodo
 *      iniciador se corona coordinador sin saber que hay un nodo superior vivo.
 *   2. Dos nodos pueden simultáneamente creer que son coordinadores
 *      durante el intervalo entre enviar COORDINATOR y recibirlo.
 *   3. Una partición de red puede crear "cerebros divididos" temporales.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NetworkService {

    private final RestTemplate restTemplate;
    private final NodeConfig config;

    /**
     * Envía un mensaje al nodo destino y espera respuesta opcional.
     *
     * @param targetNodeId ID del nodo destino
     * @param message      Mensaje a enviar
     * @return Optional con la respuesta, vacío si el nodo no responde
     */
    public Optional<BullyMessage> sendMessage(int targetNodeId, BullyMessage message) {
        String targetUrl = config.getPeerUrl(targetNodeId);
        if (targetUrl == null) {
            log.warn("[Nodo {}] URL desconocida para nodo destino {}", config.getNodeId(), targetNodeId);
            return Optional.empty();
        }

        String endpoint = targetUrl + "/bully/message";
        long startMs = System.currentTimeMillis();

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<BullyMessage> request = new HttpEntity<>(message, headers);

            log.debug("[Nodo {}] → Nodo {} | Enviando {} vía {}",
                    config.getNodeId(), targetNodeId, message.getType(), endpoint);

            ResponseEntity<BullyMessage> response = restTemplate.postForEntity(
                    endpoint, request, BullyMessage.class
            );

            long latencyMs = System.currentTimeMillis() - startMs;
            log.info("[Nodo {}] → Nodo {} | {} ✓ | Latencia: {}ms",
                    config.getNodeId(), targetNodeId, message.getType(), latencyMs);

            return Optional.ofNullable(response.getBody());

        } catch (ResourceAccessException e) {
            // Timeout o conexión rechazada — Toxiproxy cortó la comunicación
            long latencyMs = System.currentTimeMillis() - startMs;
            log.warn("[Nodo {}] → Nodo {} | {} ✗ TIMEOUT/CAÍDO después de {}ms | {}",
                    config.getNodeId(), targetNodeId, message.getType(), latencyMs, e.getMessage());
            return Optional.empty();

        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startMs;
            log.error("[Nodo {}] → Nodo {} | {} ✗ ERROR después de {}ms | {}",
                    config.getNodeId(), targetNodeId, message.getType(), latencyMs, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Envía un mensaje ELECTION al nodo destino y retorna true si recibió OK.
     *
     * Según el algoritmo Bully, si el nodo destino está vivo y tiene mayor
     * prioridad, DEBE responder OK. Si no responde, se asume caído.
     */
    public boolean sendElectionAndWaitOk(int targetNodeId) {
        BullyMessage electionMsg = BullyMessage.election(config.getNodeId());
        Optional<BullyMessage> response = sendMessage(targetNodeId, electionMsg);

        boolean receivedOk = response
                .map(r -> r.getType() == MessageType.OK)
                .orElse(false);

        if (receivedOk) {
            log.info("[Nodo {}] Recibió OK del Nodo {} — nodo superior tomará el control",
                    config.getNodeId(), targetNodeId);
        } else {
            log.info("[Nodo {}] No recibió OK del Nodo {} — asumiendo caído",
                    config.getNodeId(), targetNodeId);
        }
        return receivedOk;
    }

    /**
     * Anuncia a un nodo que este nodo es el nuevo coordinador.
     */
    public void announceCoordinator(int targetNodeId) {
        BullyMessage coordMsg = BullyMessage.coordinator(config.getNodeId());
        sendMessage(targetNodeId, coordMsg);
    }

    /**
     * Envía heartbeat al coordinador y retorna true si el coordinador responde.
     */
    public boolean sendHeartbeat(int coordinatorId) {
        BullyMessage hb = BullyMessage.heartbeat(config.getNodeId());
        Optional<BullyMessage> response = sendMessage(coordinatorId, hb);

        return response
                .map(r -> r.getType() == MessageType.HEARTBEAT_ACK)
                .orElse(false);
    }
}
