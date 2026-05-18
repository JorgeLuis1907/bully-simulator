package com.bully.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Mensaje intercambiado entre nodos del simulador Bully.
 * Se serializa como JSON sobre HTTP/REST.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BullyMessage {

    /** ID del nodo que envía el mensaje (1-5) */
    private int senderId;

    /** Tipo de mensaje según el protocolo Bully */
    private MessageType type;

    /** Timestamp de creación del mensaje (epoch ms) */
    private long timestamp;

    /** Información adicional opcional (ej: "nuevo coordinador es nodo 4") */
    private String payload;

    /**
     * Crea un mensaje de tipo ELECTION desde el nodo indicado.
     */
    public static BullyMessage election(int senderId) {
        return BullyMessage.builder()
                .senderId(senderId)
                .type(MessageType.ELECTION)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * Crea un mensaje de tipo OK desde el nodo indicado.
     */
    public static BullyMessage ok(int senderId) {
        return BullyMessage.builder()
                .senderId(senderId)
                .type(MessageType.OK)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * Crea un mensaje de tipo COORDINATOR desde el nodo indicado.
     */
    public static BullyMessage coordinator(int senderId) {
        return BullyMessage.builder()
                .senderId(senderId)
                .type(MessageType.COORDINATOR)
                .timestamp(System.currentTimeMillis())
                .payload("Nodo " + senderId + " es el nuevo coordinador")
                .build();
    }

    /**
     * Crea un mensaje de tipo HEARTBEAT desde el nodo indicado.
     */
    public static BullyMessage heartbeat(int senderId) {
        return BullyMessage.builder()
                .senderId(senderId)
                .type(MessageType.HEARTBEAT)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
