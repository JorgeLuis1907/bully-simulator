package com.bully.controller;

import com.bully.model.BullyMessage;
import com.bully.model.NodeInfo;
import com.bully.service.BullyElectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controlador REST del nodo Bully.
 *
 * Expone los endpoints necesarios para:
 *   1. Comunicación entre nodos (protocolo Bully)
 *   2. Monitoreo del estado del nodo
 *   3. Simulación de caídas y recuperaciones
 */
@Slf4j
@RestController
@RequestMapping("/bully")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")  // Permite acceso desde el dashboard web (CORS)
public class BullyController {

    private final BullyElectionService electionService;

    // ── Protocolo Bully entre nodos ──────────────────────────────

    /**
     * Punto de entrada para mensajes del protocolo Bully.
     * POST /bully/message
     */
    @PostMapping("/message")
    public ResponseEntity<BullyMessage> recibirMensaje(@RequestBody BullyMessage mensaje) {
        BullyMessage respuesta = electionService.procesarMensaje(mensaje);
        return respuesta != null ? ResponseEntity.ok(respuesta) : ResponseEntity.ok().build();
    }

    // ── Monitoreo ────────────────────────────────────────────────

    /**
     * Estado actual del nodo para el dashboard.
     * GET /bully/status
     */
    @GetMapping("/status")
    public ResponseEntity<NodeInfo> obtenerEstado() {
        return ResponseEntity.ok(electionService.obtenerInfo());
    }

    /**
     * Fuerza el inicio de una elección.
     * POST /bully/election/start
     */
    @PostMapping("/election/start")
    public ResponseEntity<String> forzarEleccion() {
        log.info("[Admin] Forzando elección manualmente");
        electionService.iniciarEleccionSiNecesario();
        return ResponseEntity.ok("Elección iniciada en Nodo " + electionService.obtenerInfo().getNodeId());
    }

    // ── Simulación de caos ───────────────────────────────────────

    /**
     * Simula caída del nodo.
     * POST /bully/chaos/kill
     */
    @PostMapping("/chaos/kill")
    public ResponseEntity<String> simularCaida() {
        log.warn("[Caos] ☠️  Comando KILL recibido");
        electionService.simularCaida();
        return ResponseEntity.ok("Nodo marcado como DOWN");
    }

    /**
     * Simula recuperación del nodo.
     * POST /bully/chaos/recover
     */
    @PostMapping("/chaos/recover")
    public ResponseEntity<String> simularRecuperacion() {
        log.info("[Caos] ♻️  Comando RECOVER recibido");
        electionService.simularRecuperacion();
        return ResponseEntity.ok("Nodo iniciando recuperación");
    }

    /**
     * Health check.
     * GET /bully/health
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}