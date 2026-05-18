package com.bully.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO que representa el estado actual de un nodo.
 * Expuesto vía REST para el dashboard de monitoreo.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeInfo {

    /** Identificador único del nodo (1-5) */
    private int nodeId;

    /** Estado actual del nodo */
    private NodeState state;

    /** ID del coordinador conocido por este nodo (-1 si desconocido) */
    private int currentCoordinatorId;

    /** Timestamp del último heartbeat exitoso (epoch ms) */
    private long lastHeartbeatMs;

    /** Número de elecciones iniciadas por este nodo */
    private int electionsStarted;

    /** Número de mensajes OK enviados */
    private int okMessagesSent;

    /** Número de veces que este nodo ganó una elección */
    private int timesElectedCoordinator;

    /** Timestamp del último cambio de estado (epoch ms) */
    private long lastStateChangeMs;

    /** Descripción textual del estado para el dashboard */
    private String statusDescription;
}
