package com.bully.service;

import com.bully.config.NodeConfig;
import com.bully.model.BullyMessage;
import com.bully.model.MessageType;
import com.bully.model.NodeInfo;
import com.bully.model.NodeState;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * ============================================================
 * ALGORITMO DE ELECCIÓN BULLY — Garcia-Molina 1982
 * ============================================================
 *
 * REGLAS DEL ALGORITMO ORIGINAL:
 *
 * 1. Un nodo P detecta que el coordinador no responde.
 *    → Envía ELECTION a todos los nodos con ID > P.
 *
 * 2. Si NADIE responde en T_election ms:
 *    → P se proclama COORDINATOR y anuncia a todos.
 *
 * 3. Si algún nodo Q (ID > P) responde OK:
 *    → P cede: pasa a ALIVE y espera el anuncio COORDINATOR.
 *    → Q inicia SU PROPIA elección (paso 1 con ID=Q).
 *
 * 4. El nodo de mayor ID activo siempre gana porque:
 *    → Nadie puede responderle OK (no hay nadie mayor).
 *    → Se proclama coordinador directamente.
 *
 * CORRECCIONES vs versión anterior:
 * - Sin flag electionInProgress que bloqueaba re-elecciones.
 * - WAITING_FOR_OK tiene timeout propio: si no llega COORDINATOR
 *   en T_coordinator ms, se reinicia la elección completa.
 * - Cada elección tiene un ID único (epoch) para ignorar
 *   mensajes de elecciones antiguas y evitar condiciones de carrera.
 */
@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class BullyElectionService {

    private final NodeConfig    config;
    private final NetworkService network;

    // ── Estado principal ─────────────────────────────────────────
    @Getter
    private final AtomicReference<NodeState> state =
            new AtomicReference<>(NodeState.ALIVE);

    @Getter
    private final AtomicInteger currentCoordinatorId = new AtomicInteger(-1);

    // Epoch de la elección actual — permite descartar mensajes obsoletos
    private final AtomicLong electionEpoch = new AtomicLong(0);

    // Heartbeat
    private final AtomicLong    lastHeartbeatMs  = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger missedHeartbeats = new AtomicInteger(0);

    // Métricas
    private final AtomicInteger electionsStarted        = new AtomicInteger(0);
    private final AtomicInteger okMessagesSent          = new AtomicInteger(0);
    private final AtomicInteger timesElectedCoordinator = new AtomicInteger(0);
    private final AtomicLong    lastStateChangeMs       = new AtomicLong(System.currentTimeMillis());

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(4);

    // ── Arranque ─────────────────────────────────────────────────

    @PostConstruct
    public void inicializar() {
        log.info("╔══════════════════════════════════════════╗");
        log.info("║  NODO {} — Bully Algorithm (Garcia-Molina)║", config.getNodeId());
        log.info("║  Peers conocidos: {}                      ║", config.getPeers().keySet());
        log.info("╚══════════════════════════════════════════╝");

        // Delay escalonado: nodo con mayor ID arranca primero → menos elecciones iniciales
        long delay = (long)(6 - config.getNodeId()) * 1000L + 500L;
        scheduler.schedule(this::iniciarEleccion, delay, TimeUnit.MILLISECONDS);
        log.info("[Nodo {}] Inicio en {}ms", config.getNodeId(), delay);
    }

    // ── Heartbeat al coordinador ─────────────────────────────────

    @Scheduled(fixedDelayString = "${bully.heartbeatIntervalMs:2000}")
    public void monitorearCoordinador() {
        NodeState st = state.get();
        if (st == NodeState.DOWN || st == NodeState.ELECTION_STARTED
                || st == NodeState.WAITING_FOR_OK) return;

        int coordId = currentCoordinatorId.get();

        // Soy coordinador — no me monitoreo a mí mismo
        if (coordId == config.getNodeId()) return;

        // Sin coordinador asignado → iniciar elección
        if (coordId < 0) {
            log.info("[Nodo {}] Sin coordinador asignado → iniciando elección",
                    config.getNodeId());
            scheduler.execute(this::iniciarEleccion);
            return;
        }

        boolean vivo = network.sendHeartbeat(coordId);
        if (vivo) {
            missedHeartbeats.set(0);
            lastHeartbeatMs.set(System.currentTimeMillis());
        } else {
            int fallos = missedHeartbeats.incrementAndGet();
            log.warn("[Nodo {}] Heartbeat fallido #{} → Coordinador Nodo {} no responde",
                    config.getNodeId(), fallos, coordId);

            if (fallos >= config.getMaxMissedHeartbeats()) {
                log.warn("[Nodo {}] ⚠️  Coordinador {} CAÍDO — iniciando elección",
                        config.getNodeId(), coordId);
                missedHeartbeats.set(0);
                currentCoordinatorId.set(-1);
                scheduler.execute(this::iniciarEleccion);
            }
        }
    }

    // ── PASO 1: Iniciar elección ─────────────────────────────────

    /**
     * Punto de entrada del algoritmo Bully.
     *
     * Envía ELECTION a todos los nodos con ID mayor.
     * Espera T_election ms.
     *   → Si recibe OK de alguno: cede (WAITING_FOR_OK).
     *   → Si no recibe OK: se corona COORDINATOR.
     *
     * Este método es sincronizado para evitar elecciones paralelas
     * del mismo nodo, pero NO bloquea elecciones de otros nodos.
     */
    public synchronized void iniciarEleccion() {
        if (state.get() == NodeState.DOWN) return;

        long epoch = System.currentTimeMillis();
        electionEpoch.set(epoch);

        cambiarEstado(NodeState.ELECTION_STARTED);
        electionsStarted.incrementAndGet();
        log.info("[Nodo {}] 🗳️  ELECCIÓN INICIADA (epoch={}, #{})",
                config.getNodeId(), epoch, electionsStarted.get());

        List<Integer> superiores = config.getHigherPriorityNodes();

        // Soy el de mayor ID → gano directamente sin enviar nada
        if (superiores.isEmpty()) {
            log.info("[Nodo {}] 👑 Mayor ID en el clúster — gano automáticamente",
                    config.getNodeId());
            proclamarCoordinador(epoch);
            return;
        }

        // Enviar ELECTION a todos los nodos superiores en paralelo
        log.info("[Nodo {}] Enviando ELECTION a nodos superiores: {}",
                config.getNodeId(), superiores);

        // Usamos CountDownLatch para esperar todas las respuestas
        // o el timeout, lo que ocurra primero
        AtomicBoolean recibiOk = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(superiores.size());

        for (int supId : superiores) {
            final int targetId = supId;
            scheduler.execute(() -> {
                try {
                    boolean ok = network.sendElectionAndWaitOk(targetId);
                    if (ok) {
                        recibiOk.set(true);
                        log.info("[Nodo {}] OK recibido del Nodo {} — cedo la elección",
                                config.getNodeId(), targetId);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Esperar T_election ms a que lleguen los OK
        try {
            latch.await(config.getElectionTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verificar epoch: si llegó un COORDINATOR mientras esperábamos, ya no actuamos
        if (electionEpoch.get() != epoch) {
            log.info("[Nodo {}] Epoch cambió durante espera — elección cancelada (ya hay coordinador)",
                    config.getNodeId());
            return;
        }

        if (state.get() == NodeState.DOWN) return;

        if (recibiOk.get()) {
            // ── CASO: Alguien superior respondió OK ──────────────
            // Cedo la elección y espero el anuncio COORDINATOR
            cambiarEstado(NodeState.WAITING_FOR_OK);
            log.info("[Nodo {}] ⏳ Cedí — esperando anuncio COORDINATOR ({}ms)",
                    config.getNodeId(), config.getElectionTimeoutMs() * 2);

            // Timeout de seguridad: si no llega COORDINATOR en 2*T,
            // el nodo superior también cayó → reinicio elección
            long epochActual = epoch;
            scheduler.schedule(() -> {
            synchronized (BullyElectionService.this) {
            if (state.get() == NodeState.WAITING_FOR_OK
                    && electionEpoch.get() == epochActual) {
                cambiarEstado(NodeState.ALIVE);
                iniciarEleccion();
            }
        }
            }, config.getElectionTimeoutMs() * 2, TimeUnit.MILLISECONDS);

        } else {
            // ── CASO: Nadie respondió → soy el mayor activo ──────
            log.info("[Nodo {}] Ningún nodo superior respondió → me proclamo COORDINATOR",
                    config.getNodeId());
            proclamarCoordinador(epoch);
        }
    }

    // ── PASO 4: Proclamarse coordinador ─────────────────────────

    private void proclamarCoordinador(long epoch) {
        if (state.get() == NodeState.DOWN) return;
        // Verificar que nuestra elección sigue vigente
        if (electionEpoch.get() != epoch && epoch != -1) {
            log.info("[Nodo {}] Epoch obsoleto — no me proclamo coordinador", config.getNodeId());
            return;
        }

        cambiarEstado(NodeState.COORDINATOR);
        currentCoordinatorId.set(config.getNodeId());
        timesElectedCoordinator.incrementAndGet();
        missedHeartbeats.set(0);

        log.info("[Nodo {}] 👑 ¡SOY EL NUEVO COORDINADOR! (vez #{})",
                config.getNodeId(), timesElectedCoordinator.get());

        // Anunciar a todos los demás nodos
        List<Integer> todos = config.getPeers().keySet().stream()
                .filter(id -> id != config.getNodeId())
                .sorted()
                .toList();

        log.info("[Nodo {}] Enviando COORDINATOR a: {}", config.getNodeId(), todos);

        scheduler.execute(() -> {
            for (int peerId : todos) {
                network.announceCoordinator(peerId);
            }
            log.info("[Nodo {}] ✅ Broadcast COORDINATOR completado", config.getNodeId());
        });
    }

    // ── Manejo de mensajes entrantes ─────────────────────────────

    public BullyMessage procesarMensaje(BullyMessage mensaje) {
        int remitente = mensaje.getSenderId();
        MessageType tipo = mensaje.getType();

        log.info("[Nodo {}] ← Nodo {} | {}", config.getNodeId(), remitente, tipo);

        return switch (tipo) {
            case ELECTION    -> manejarElection(remitente);
            case OK          -> null; // OK es respuesta síncrona, no llega aquí
            case COORDINATOR -> manejarCoordinator(remitente);
            case HEARTBEAT   -> manejarHeartbeat(remitente);
            default          -> null;
        };
    }

    /**
     * Recibo ELECTION de un nodo con menor ID.
     *
     * Respondo OK inmediatamente (el nodo remitente cederá).
     * Luego inicio MI PROPIA elección para buscar si hay alguien mayor que yo.
     */
    private BullyMessage manejarElection(int remitenteId) {
        log.info("[Nodo {}] Recibí ELECTION del Nodo {} → envío OK e inicio mi propia elección",
                config.getNodeId(), remitenteId);

        okMessagesSent.incrementAndGet();

        // Iniciar mi propia elección en background, no bloqueamos la respuesta OK
        NodeState currentState = state.get();
        if (currentState != NodeState.DOWN && currentState != NodeState.ELECTION_STARTED && currentState != NodeState.WAITING_FOR_OK) {
            scheduler.execute(this::iniciarEleccion);
        } else {
            log.info("[Nodo {}] No inicio elección propia (estado actual: {})", config.getNodeId(), currentState);
        }

        // Responder OK al remitente — esto hace que él ceda
        return BullyMessage.ok(config.getNodeId());
    }

    /**
     * Recibo COORDINATOR de un nodo que ganó la elección.
     *
     * Acepto al nuevo líder SOLO si tiene mayor ID que el actual coordinador
     * (protección contra mensajes tardíos de elecciones antiguas).
     */
    private BullyMessage manejarCoordinator(int remitenteId) {
        int actual = currentCoordinatorId.get();

        if (remitenteId > actual || actual < 0) {
            log.info("[Nodo {}] ✅ Acepto a Nodo {} como COORDINADOR (reemplaza a Nodo {})",
                    config.getNodeId(), remitenteId, actual);

            // Actualizar epoch para cancelar cualquier timeout de elección pendiente
            electionEpoch.set(System.currentTimeMillis());

            currentCoordinatorId.set(remitenteId);
            missedHeartbeats.set(0);
            lastHeartbeatMs.set(System.currentTimeMillis());

            if (state.get() != NodeState.COORDINATOR) {
                cambiarEstado(NodeState.ALIVE);
            } else if (remitenteId > config.getNodeId()) {
                // Otro nodo con mayor ID se proclamó coordinador — cedo
                log.info("[Nodo {}] Cedo liderazgo al Nodo {} (mayor ID)",
                        config.getNodeId(), remitenteId);
                cambiarEstado(NodeState.ALIVE);
            }
        } else {
            log.warn("[Nodo {}] ⚠️  COORDINATOR del Nodo {} ignorado (coordinador actual: Nodo {})",
                    config.getNodeId(), remitenteId, actual);
        }

        return BullyMessage.builder()
                .senderId(config.getNodeId())
                .type(MessageType.COORDINATOR_ACK)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * Recibo HEARTBEAT de un nodo subordinado.
     * Solo respondo si soy el coordinador actual.
     */
    private BullyMessage manejarHeartbeat(int remitenteId) {
        if (state.get() == NodeState.COORDINATOR) {
            log.debug("[Nodo {}] 💓 Heartbeat del Nodo {} → ACK", config.getNodeId(), remitenteId);
            return BullyMessage.builder()
                    .senderId(config.getNodeId())
                    .type(MessageType.HEARTBEAT_ACK)
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
        log.debug("[Nodo {}] Heartbeat del Nodo {} ignorado (no soy coordinador, estado: {})",
                config.getNodeId(), remitenteId, state.get());
        return null;
    }

    // ── Utilidades ───────────────────────────────────────────────

    private void cambiarEstado(NodeState nuevo) {
        NodeState anterior = state.getAndSet(nuevo);
        lastStateChangeMs.set(System.currentTimeMillis());
        if (anterior != nuevo) {
            log.info("[Nodo {}] 🔄 Estado: {} → {}", config.getNodeId(), anterior, nuevo);
        }
    }

    public NodeInfo obtenerInfo() {
        NodeState st = state.get();
        String desc = switch (st) {
            case ALIVE            -> "Funcionando normalmente. Coordinador: Nodo " + currentCoordinatorId.get();
            case ELECTION_STARTED -> "Elección iniciada. Contactando nodos superiores...";
            case WAITING_FOR_OK   -> "Cedí la elección. Esperando anuncio COORDINATOR...";
            case COORDINATOR      -> "SOY EL COORDINADOR. Aceptando heartbeats.";
            case DOWN             -> "NODO CAÍDO — Toxiproxy bloqueó las comunicaciones.";
        };
        return NodeInfo.builder()
                .nodeId(config.getNodeId())
                .state(st)
                .currentCoordinatorId(currentCoordinatorId.get())
                .lastHeartbeatMs(lastHeartbeatMs.get())
                .electionsStarted(electionsStarted.get())
                .okMessagesSent(okMessagesSent.get())
                .timesElectedCoordinator(timesElectedCoordinator.get())
                .lastStateChangeMs(lastStateChangeMs.get())
                .statusDescription(desc)
                .build();
    }

    public void simularCaida() {
        log.warn("[Nodo {}] 💀 SIMULANDO CAÍDA", config.getNodeId());
        cambiarEstado(NodeState.DOWN);
        currentCoordinatorId.set(-1);
    }

    public void simularRecuperacion() {
        log.info("[Nodo {}] ♻️  RECUPERÁNDOSE — iniciando rejoin", config.getNodeId());
        currentCoordinatorId.set(-1);
        missedHeartbeats.set(0);
        cambiarEstado(NodeState.ALIVE);
        scheduler.schedule(this::iniciarEleccion, 1500, TimeUnit.MILLISECONDS);
    }

    public void iniciarEleccionSiNecesario() {
        if (state.get() != NodeState.DOWN) {
            scheduler.execute(this::iniciarEleccion);
        }
    }
}
