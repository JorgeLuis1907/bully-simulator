package com.bully.model;

/**
 * Tipos de mensajes intercambiados entre nodos en el Algoritmo Bully.
 *
 * Flujo estándar del algoritmo:
 *   1. Nodo P detecta falla del coordinador → envía ELECTION a todos los nodos con ID > P
 *   2. Nodo Q (ID > P) recibe ELECTION → responde OK a P, inicia su propia elección
 *   3. Si ningún nodo responde OK → el iniciador se convierte en COORDINATOR
 *   4. El nuevo coordinador envía COORDINATOR a todos los nodos
 *   5. Los nodos confirman con COORDINATOR_ACK
 */
public enum MessageType {

    /**
     * Mensaje de elección. Enviado por un nodo a todos los nodos
     * con ID mayor. Significa: "Propongo iniciar una elección, ¿alguien
     * con más prioridad está disponible?"
     */
    ELECTION,

    /**
     * Respuesta afirmativa a ELECTION. Enviado por nodos con ID mayor.
     * Significa: "Yo estoy aquí y soy más prioritario, yo me encargo."
     * Cancela la candidatura del receptor original.
     */
    OK,

    /**
     * Anuncio de nuevo coordinador. Enviado por el ganador de la elección
     * a TODOS los nodos. Significa: "Soy el nuevo líder."
     */
    COORDINATOR,

    /**
     * Confirmación de recepción del anuncio COORDINATOR.
     * El nodo receptor acepta la nueva autoridad.
     */
    COORDINATOR_ACK,

    /**
     * Pulso de vida enviado periódicamente al coordinador.
     * Si el coordinador no responde en tiempo, se inicia elección.
     */
    HEARTBEAT,

    /**
     * Respuesta al HEARTBEAT. Confirma que el coordinador sigue activo.
     */
    HEARTBEAT_ACK
}
