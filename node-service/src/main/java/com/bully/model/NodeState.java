package com.bully.model;

/**
 * Estados posibles de un nodo en el Algoritmo de Elección Bully.
 *
 * Transiciones válidas:
 *   ALIVE → ELECTION_STARTED  (al detectar que el líder no responde)
 *   ELECTION_STARTED → WAITING_FOR_OK  (al enviar mensajes ELECTION y esperar OK)
 *   WAITING_FOR_OK → COORDINATOR  (si ningún nodo superior respondió OK)
 *   WAITING_FOR_OK → ALIVE  (si un nodo superior envió OK y tomó el control)
 *   COORDINATOR → ALIVE  (rol estable de coordinador, tratado como ALIVE con privilegios)
 *   CUALQUIERA → DOWN  (cuando Toxiproxy simula caída de red o crash)
 *   DOWN → ALIVE  (cuando el proxy es restaurado y el nodo se recupera)
 */
public enum NodeState {

    /**
     * El nodo está operativo y funcionando con normalidad.
     * Envía heartbeats periódicos al coordinador actual.
     */
    ALIVE,

    /**
     * El nodo detectó que el coordinador no responde y ha iniciado
     * un proceso de elección. Envía mensajes ELECTION a todos los
     * nodos con ID mayor al suyo.
     */
    ELECTION_STARTED,

    /**
     * El nodo ha enviado mensajes ELECTION y está esperando
     * respuestas OK de nodos con mayor prioridad.
     * Si no recibe OK en el timeout configurado, se convierte en COORDINATOR.
     */
    WAITING_FOR_OK,

    /**
     * El nodo se ha proclamado líder/coordinador.
     * Anuncia su posición enviando mensajes COORDINATOR a todos los demás nodos.
     * Acepta heartbeats de los demás nodos.
     */
    COORDINATOR,

    /**
     * El nodo está caído (simulado por Toxiproxy).
     * No responde a ningún mensaje de elección ni heartbeat.
     * Internamente puede seguir "ejecutándose" pero sus mensajes son bloqueados.
     */
    DOWN
}
