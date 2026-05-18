# Documentación Técnica — Simulador del Algoritmo de Elección Bully

## Índice

1. [El Algoritmo de Bully — Base Teórica](#1-el-algoritmo-de-bully--base-teórica)
2. [Arquitectura General del Sistema](#2-arquitectura-general-del-sistema)
3. [Modelo Caja Negra — Visión por Componente](#3-modelo-caja-negra--visión-por-componente)
4. [Cómo Funcionan los Componentes en Conjunto](#4-cómo-funcionan-los-componentes-en-conjunto)
5. [Justificación de Tecnologías](#5-justificación-de-tecnologías)
6. [Documentación Función por Función](#6-documentación-función-por-función)
   - [BullyElectionService.java](#61-bullyelectionservicejava)
   - [NetworkService.java](#62-networkservicejava)
   - [BullyController.java](#63-bullycontrollerjava)
   - [NodeConfig.java](#64-nodeconfigjava)
   - [RestClientConfig.java](#65-restclientconfigjava)
   - [BullyMessage.java](#66-bullymessagejava)
   - [MessageType.java](#67-messagetypejava)
   - [NodeState.java](#68-nodestatejava)
   - [NodeInfo.java](#69-nodeinfojava)
   - [BullyNodeApplication.java](#610-bullynodeapplicationjava)
   - [chaos.py](#611-chaospy)
   - [index.html](#612-indexhtml)
   - [nginx.conf](#613-nginxconf)
   - [init-proxies.sh](#614-init-proxiessh)
   - [docker-compose.yml](#615-docker-composeyml)

---

## 1. El Algoritmo de Bully — Base Teórica

El **Algoritmo de Elección Bully** fue propuesto por Héctor Garcia-Molina en 1982. Resuelve el problema de **elegir un coordinador único** en un sistema distribuido cuando el coordinador actual falla.

### Premisas del algoritmo original

- Cada nodo tiene un identificador numérico único.
- Un nodo con ID mayor tiene mayor "prioridad" — por eso se llama Bully (matón): siempre gana el más grande.
- Los nodos pueden detectar si otro nodo no responde.
- La comunicación es confiable y prácticamente instantánea (supuesto teórico).

### Flujo de 4 pasos

```
Paso 1 — Detección
  Un nodo P detecta que el coordinador no responde.
  P envía mensaje ELECTION a todos los nodos con ID > P.

Paso 2 — Respuesta
  Cada nodo Q con ID > P que recibe ELECTION:
    a) Responde OK a P (P cede).
    b) Inicia su propia elección (envía ELECTION a nodos con ID > Q).

Paso 3 — Proclamación
  Si nadie respondió OK a P en el tiempo T_election:
    P se proclama coordinador.
    P envía COORDINATOR a todos los nodos.

Paso 4 — Aceptación
  Los nodos que reciben COORDINATOR actualizan su registro
  del coordinador actual y vuelven a estado normal.
```

### Por qué siempre gana el nodo con mayor ID activo

El nodo con mayor ID disponible envía ELECTION a sus superiores, pero no hay ninguno activo. Ninguno responde OK. Entonces él mismo se proclama coordinador. Es el único que puede hacerlo sin recibir un OK primero.

### Diferencia teoría vs. práctica implementada

| Aspecto | Teoría | Esta implementación |
|---|---|---|
| Detección de falla | Instantánea | Via heartbeats periódicos con `maxMissedHeartbeats` |
| Comunicación | Confiable y rápida | HTTP/REST sobre Toxiproxy con latencia configurable |
| Elecciones paralelas | No contempladas | Posibles; manejadas con `electionEpoch` |
| Split-brain | Imposible | Transitorio y documentado |
| Inicio del sistema | Nodos ya corriendo | Delay escalonado para evitar tormenta inicial |

---

## 2. Arquitectura General del Sistema

```
┌──────────────────────────────────────────────────────────────────┐
│                        HOST (tu máquina)                         │
│                                                                  │
│  ┌─────────────┐   ┌──────────────────────────────────────────┐ │
│  │  chaos.py   │   │            Docker Network                │ │
│  │  (Python)   │   │                                          │ │
│  │  :CLI       │   │  ┌────────┐ ┌────────┐ ┌────────┐       │ │
│  └──────┬──────┘   │  │ Nodo 1 │ │ Nodo 2 │ │ Nodo 3 │       │ │
│         │          │  │ :8080  │ │ :8080  │ │ :8080  │       │ │
│  ┌──────▼──────┐   │  └───┬────┘ └───┬────┘ └───┬────┘       │ │
│  │  Dashboard  │   │      │          │          │             │ │
│  │  :3000      │   │  ┌───▼──────────▼──────────▼────┐       │ │
│  │  (Nginx +   │◄──┼──│           TOXIPROXY           │       │ │
│  │  index.html)│   │  │  :18881 :18882 :18883         │       │ │
│  └─────────────┘   │  │  :18884 :18885   API: :8474   │       │ │
│                    │  └──────────────────────────────-┘       │ │
│  Puertos expuestos:│                                           │ │
│  8081-8085 (nodos) │  ┌────────┐ ┌────────┐                  │ │
│  8474 (toxiproxy)  │  │ Nodo 4 │ │ Nodo 5 │                  │ │
│  3000 (dashboard)  │  │ :8080  │ │ :8080  │                  │ │
│                    │  └────────┘ └────────┘                  │ │
└──────────────────────────────────────────────────────────────────┘
```

### Capas del sistema

**Capa de aplicación** — Los 5 nodos Spring Boot. Cada uno ejecuta una instancia independiente del algoritmo Bully. Se comunican entre sí exclusivamente a través de Toxiproxy.

**Capa de red simulada** — Toxiproxy intercepta todo el tráfico entre nodos. Permite inyectar latencia, jitter, bandwidth limitado y timeouts completos sin modificar el código de los nodos.

**Capa de observabilidad** — El dashboard (Nginx + HTML/JS) consulta directamente los puertos 8081-8085 y presenta el estado de cada nodo en tiempo real.

**Capa de control** — `chaos.py` controla tanto la API de Toxiproxy (red) como los endpoints REST de los nodos (estado), permitiendo simular escenarios completos.

---

## 3. Modelo Caja Negra — Visión por Componente

Cada componente se describe como una caja negra: qué recibe, qué hace internamente (resumen), qué produce, y qué representa en el mundo real.

---

### 3.1 Nodo Spring Boot (`node-service`)

**Qué representa en el mundo real**
Un servidor en un clúster distribuido — por ejemplo, un nodo en un clúster de base de datos, un servidor en un sistema de archivos distribuido, o un proceso en un sistema de mensajería. Cada nodo es autónomo: tiene su propia lógica, su propio estado, y no comparte memoria con los demás.

**Entradas**
- Variables de entorno al arrancar (`NODE_ID`, URLs de peers, timeouts)
- Mensajes HTTP entrantes de otros nodos (`ELECTION`, `COORDINATOR`, `HEARTBEAT`)
- Comandos de administración HTTP (`/chaos/kill`, `/chaos/recover`, `/election/start`)

**Procesamiento interno**
Ejecuta el algoritmo Bully: detecta si el coordinador responde via heartbeats, inicia elecciones cuando es necesario, responde a mensajes de otros nodos según su rol actual.

**Salidas**
- Respuestas HTTP a mensajes entrantes (`OK`, `COORDINATOR_ACK`, `HEARTBEAT_ACK`)
- Mensajes HTTP salientes hacia otros nodos (via Toxiproxy)
- Estado actual consultable via `/bully/status`
- Logs en consola y en volumen Docker

---

### 3.2 Toxiproxy

**Qué representa en el mundo real**
La red física entre servidores. En producción real, los servidores se comunican por redes que pueden tener latencia variable, paquetes perdidos, ancho de banda limitado, o simplemente estar desconectadas. Toxiproxy simula todas estas condiciones de forma controlada.

**Entradas**
- Tráfico HTTP entre nodos (lo intercepta automáticamente)
- Comandos de configuración via su API REST en `:8474` (agregar/quitar toxics)

**Procesamiento interno**
Actúa como proxy TCP. Cada "toxic" que se le configura modifica el tráfico de una forma específica: `latency` añade demora, `timeout` corta la conexión completamente, `bandwidth` limita el caudal, `jitter` añade variación aleatoria a la latencia.

**Salidas**
- Tráfico modificado (o bloqueado) hacia el nodo destino
- Respuestas de error de conexión hacia el nodo origen cuando el toxic es `timeout`

---

### 3.3 `chaos.py`

**Qué representa en el mundo real**
Un operador de infraestructura que puede apagar servidores, degradar la red, y monitorear el estado del sistema. En equipos de ingeniería esto se hace con herramientas de Chaos Engineering (Netflix Chaos Monkey, Gremlin, etc.).

**Entradas**
- Comandos del usuario (CLI o menú interactivo)
- Respuestas HTTP de los nodos y de Toxiproxy

**Procesamiento interno**
Traduce comandos de alto nivel ("matar nodo 5") en dos acciones coordinadas: primero configura Toxiproxy para bloquear el tráfico, luego notifica al nodo para que cambie su estado interno a DOWN.

**Salidas**
- Requests a la API de Toxiproxy para agregar/quitar toxics
- Requests a los endpoints `/chaos/kill` y `/chaos/recover` de los nodos
- Tablas de estado en consola (usando `rich`)

---

### 3.4 Dashboard (`index.html` + Nginx)

**Qué representa en el mundo real**
Un panel de monitoreo operacional — equivalente a Grafana, Datadog, o cualquier herramienta que muestre el estado de un clúster en tiempo real. Permite observar el algoritmo sin intervenir en él.

**Entradas**
- Polling HTTP a los endpoints `/bully/status` de los 5 nodos cada 2 segundos

**Procesamiento interno**
Detecta cambios de estado comparando la respuesta actual con la anterior para cada nodo. Registra transiciones y las muestra como eventos con timestamp. Calcula métricas agregadas (cuántos nodos vivos, quién es el coordinador, total de elecciones).

**Salidas**
- Tarjetas visuales con el estado de cada nodo (color + animación según estado)
- Log de transiciones de estado con descripción semántica
- Log de actividad en tiempo real
- Métricas globales del clúster

---

### 3.5 `init-proxies.sh`

**Qué representa en el mundo real**
La configuración inicial de infraestructura de red — equivalente a configurar un balanceador de carga, un firewall, o las reglas de routing antes de levantar los servidores.

**Entradas**
- Nada (script sin argumentos)
- Disponibilidad de la API de Toxiproxy en `http://toxiproxy:8474`

**Procesamiento interno**
Espera a que Toxiproxy esté disponible (polling con `curl`), luego crea los 5 proxies mediante 5 llamadas POST a la API de Toxiproxy. Cada proxy mapea un puerto externo (`18881`-`18885`) al puerto interno de su nodo correspondiente (`:8080`).

**Salidas**
- 5 proxies creados en Toxiproxy, listos para interceptar tráfico

---

### 3.6 `docker-compose.yml`

**Qué representa en el mundo real**
La especificación de infraestructura — equivalente a un manifiesto de Kubernetes o una plantilla de CloudFormation. Define qué contenedores existen, cómo se comunican, qué variables de entorno reciben, y cuál es el orden de arranque.

**Entradas**
- Comando `docker compose up`

**Procesamiento interno**
Docker lee la especificación y orquesta la creación de: la red virtual, los volúmenes de logs, el contenedor de Toxiproxy, el contenedor de inicialización de proxies, los 5 contenedores de nodos, y el contenedor del dashboard.

**Salidas**
- Sistema completo corriendo con todos los contenedores configurados y conectados

---

## 4. Cómo Funcionan los Componentes en Conjunto

### Secuencia de arranque

```
1. docker compose up
   │
   ├─► Toxiproxy arranca  (:8474, :18881-:18885)
   │
   ├─► toxiproxy-init ejecuta init-proxies.sh
   │     └─► Crea los 5 proxies TCP en Toxiproxy
   │
   ├─► Los 5 nodos Spring Boot arrancan en paralelo
   │     └─► Cada uno lee su NODE_ID y URLs de peers del entorno
   │     └─► @PostConstruct llama a inicializar()
   │     └─► Delay escalonado: nodo N arranca en (6-N)*1000ms
   │           Nodo 5 → 1.5s, Nodo 4 → 2.5s, ... Nodo 1 → 5.5s
   │
   └─► Dashboard Nginx arranca (:3000)
         └─► Sirve index.html
         └─► El JS empieza a hacer polling a :8081-:8085
```

### Secuencia de elección normal

```
T=0   Nodo 5 arranca primero (delay menor)
      → No hay coordinador → iniciarEleccion()
      → No hay nodos con ID > 5 → proclamarCoordinador()
      → Anuncia COORDINATOR a nodos 1,2,3,4
      → Estado: COORDINATOR

T=2s  Nodos 1-4 arrancan (escalonados)
      → Reciben COORDINATOR de nodo 5
      → Actualizan currentCoordinatorId = 5
      → Estado: ALIVE
      → Empiezan a enviar heartbeats al nodo 5

T=5s  Sistema estable: nodo 5 COORDINATOR, nodos 1-4 ALIVE
```

### Secuencia cuando el coordinador falla

```
T=0   chaos.py --kill 5
      → Toxiproxy agrega toxic "timeout" en proxy del nodo 5
      → /chaos/kill marca internamente al nodo 5 como DOWN

T=0-10s  Nodos 1-4 envían heartbeats al nodo 5
         → Toxiproxy bloquea el tráfico → timeout
         → missedHeartbeats++ en cada nodo
         → Al llegar a maxMissedHeartbeats (2): iniciarEleccion()

T=10s  Nodo 4 (mayor ID activo) inicia elección
       → Envía ELECTION a nodo 5 → timeout (Toxiproxy)
       → Nadie responde OK
       → proclamarCoordinador()
       → Envía COORDINATOR a nodos 1,2,3

       Nodos 1,2,3 también inician elecciones (simultáneo)
       → Nodo 3 envía ELECTION a nodo 4 → nodo 4 responde OK
       → Nodo 3 cede: WAITING_FOR_OK
       → Recibe COORDINATOR del nodo 4 → ALIVE

T=10-15s  Sistema estable: nodo 4 COORDINATOR, nodos 1-3 ALIVE
```

### Flujo de un mensaje entre nodos

```
Nodo 3 quiere enviar ELECTION al Nodo 4:

BullyElectionService (nodo 3)
  └─► NetworkService.sendElectionAndWaitOk(4)
        └─► RestTemplate.postForEntity("http://toxiproxy:18884/bully/message", ...)
              └─► [Toxiproxy intercepta: sin toxics → pasa directo]
                    └─► BullyController.recibirMensaje() en Nodo 4
                          └─► BullyElectionService.procesarMensaje()
                                └─► manejarElection(3)
                                      └─► Retorna BullyMessage.ok(4)
              └─► [Toxiproxy devuelve respuesta]
        └─► Retorna true (recibió OK)
  └─► recibiOk = true → cambia a WAITING_FOR_OK
```

---

## 5. Justificación de Tecnologías

### Java + Spring Boot

**Por qué:** Spring Boot permite levantar un servidor HTTP completamente funcional con pocas líneas de configuración. La inyección de dependencias hace que cada componente (NetworkService, BullyElectionService) sea independiente y testeable. `@Scheduled` ofrece heartbeats periódicos sin gestionar hilos manualmente. `@PostConstruct` garantiza que la inicialización ocurra después de que todas las dependencias estén inyectadas.

**Alternativas descartadas:** Un servidor HTTP manual en Java puro requeriría mucho más código de infraestructura. Python o Node.js son válidos pero tienen modelos de concurrencia distintos que complican la gestión de `synchronized` + `AtomicReference` que el algoritmo necesita.

### Docker + Docker Compose

**Por qué:** Garantiza que el sistema funcione igual en cualquier máquina sin instalar Java, sin configurar puertos, sin preocuparse de versiones. `docker-compose.yml` define la topología completa (5 nodos + Toxiproxy + dashboard) en un solo archivo. La red virtual de Docker aísla los contenedores y les da hostnames predecibles (`node-1`, `node-2`, etc.).

**Por qué 5 nodos como contenedores separados y no hilos:** Los hilos comparten memoria. Los contenedores no. Un sistema distribuido real tiene procesos completamente separados — los contenedores modelan esto con fidelidad. Cada nodo puede "caerse" de forma independiente sin afectar a los demás.

### Toxiproxy

**Por qué:** Es la única forma de simular condiciones de red sin modificar el código de aplicación. Permite demostrar empíricamente las diferencias entre el algoritmo teórico (red perfecta) y el comportamiento real (latencia, particiones). La alternativa sería añadir delays artificiales en el código Java, lo que contamina la lógica de negocio.

**Qué permite demostrar:** Elecciones falsas por timeout de heartbeat cuando el coordinador vive pero la red es lenta. Split-brain transitorio cuando el broadcast COORDINATOR tarda en propagarse. Comportamiento no determinista con jitter alto.

### `AtomicReference` + `AtomicInteger` + `AtomicLong`

**Por qué:** El algoritmo Bully en un entorno real es inherentemente concurrente. El hilo del scheduler de heartbeats, el hilo que procesa mensajes HTTP entrantes, y el hilo de elección pueden ejecutarse simultáneamente. Los tipos atómicos garantizan que las lecturas y escrituras de estado sean visibles entre hilos sin necesidad de `synchronized` en cada acceso. `synchronized` se usa solo donde se necesita atomicidad de múltiples operaciones juntas (el método `iniciarEleccion`).

### `CountDownLatch` para esperar respuestas OK

**Por qué:** El algoritmo envía ELECTION a múltiples nodos superiores en paralelo (para reducir latencia) y luego espera hasta que todos respondan o venza el timeout. `CountDownLatch` modela exactamente esto: se inicializa con el número de nodos a contactar, cada hilo hace `countDown()` al terminar, y el hilo principal hace `await(timeout)`. Esto es más limpio que `Future.get()` con timeout manual y evita bloquear más tiempo del necesario si todos responden rápido.

### `electionEpoch` (AtomicLong con timestamp)

**Por qué:** Sin esta protección, mensajes de elecciones antiguas (retrasados por Toxiproxy) podrían confundir al nodo. Por ejemplo: el nodo 3 inicia una elección, el nodo 4 responde OK con 5 segundos de delay (jitter), pero para entonces el nodo 3 ya se coronó coordinador. Al llegar el OK tardío, sin epoch el nodo 3 podría interpretar que debe ceder el liderazgo. Con epoch, compara el epoch del mensaje con el actual y descarta el mensaje si no coincide.

### Python + `rich` para `chaos.py`

**Por qué:** Python es el lenguaje estándar para scripting de operaciones. La librería `rich` provee tablas, colores y paneles en consola con una línea de código, haciendo el output legible sin esfuerzo. `requests` simplifica las llamadas HTTP. El script no necesita concurrencia compleja — solo el hilo de monitor usa `threading`.

### Nginx como servidor del dashboard

**Por qué:** El dashboard es un archivo HTML estático. Nginx sirve archivos estáticos de forma eficiente y estable. Adicionalmente, `nginx.conf` define rutas proxy (`/api/node1/`, etc.) que permiten al dashboard hacer requests a los nodos sin problemas de CORS cuando se accede desde un dominio distinto. En la implementación actual el dashboard accede directamente a los puertos, pero el proxy de Nginx queda disponible como alternativa.

---

## 6. Documentación Función por Función

---

### 6.1 `BullyElectionService.java`

Es el núcleo del sistema. Implementa el algoritmo Bully completo. Todas las demás clases existen para darle soporte.

---

#### `inicializar()` — `@PostConstruct`

```java
public void inicializar()
```

**Cuándo se ejecuta:** Una sola vez, automáticamente, justo después de que Spring inyecta todas las dependencias del bean. Equivale al constructor pero con acceso garantizado a `config` y `network`.

**Qué hace:** Imprime un banner de identificación del nodo en los logs. Luego programa un delay escalonado antes de iniciar la primera elección. La fórmula es `(6 - nodeId) * 1000 + 500` ms, lo que hace que el nodo 5 espere 1500ms, el nodo 4 espere 2500ms, y así sucesivamente hasta el nodo 1 que espera 5500ms.

**Por qué el delay escalonado:** Si todos los nodos arrancaran simultáneamente y lanzaran elecciones al mismo tiempo, se produciría una "tormenta" de mensajes ELECTION/OK. El nodo 5, al arrancar primero, se corona coordinador y anuncia COORDINATOR antes de que los demás nodos inicien sus elecciones. Los nodos inferiores, al recibir COORDINATOR, desisten de iniciar elecciones propias.

---

#### `monitorearCoordinador()` — `@Scheduled`

```java
@Scheduled(fixedDelayString = "${bully.heartbeatIntervalMs:2000}")
public void monitorearCoordinador()
```

**Cuándo se ejecuta:** Periódicamente, con un intervalo configurable via `heartbeatIntervalMs` (default 2000ms). `fixedDelay` significa que el intervalo se cuenta desde que termina la ejecución anterior, no desde que empieza.

**Qué hace, paso a paso:**

1. Si el nodo está `DOWN`, `ELECTION_STARTED`, o `WAITING_FOR_OK`, retorna inmediatamente sin hacer nada. En esos estados el monitoreo no es responsabilidad de este nodo.

2. Si el nodo es el coordinador (`coordId == nodeId`), retorna inmediatamente. No tiene sentido mandarse heartbeats a uno mismo.

3. Si no hay coordinador conocido (`coordId < 0`), lanza una elección directamente.

4. Si hay coordinador conocido, envía un heartbeat. Si responde, resetea `missedHeartbeats` a 0 y actualiza `lastHeartbeatMs`. Si no responde, incrementa `missedHeartbeats`.

5. Cuando `missedHeartbeats` alcanza `maxMissedHeartbeats` (default 2), declara al coordinador como caído, resetea el contador, limpia `currentCoordinatorId`, y lanza una elección.

**Por qué `maxMissedHeartbeats` y no fallar al primer timeout:** Un solo heartbeat fallido puede deberse a un pico de carga temporal, una pausa de GC en el coordinador, o latencia momentánea de red. Esperar 2 fallos consecutivos reduce los falsos positivos sin sacrificar demasiado tiempo de detección.

---

#### `iniciarEleccion()` — synchronized

```java
public synchronized void iniciarEleccion()
```

**Cuándo se llama:** Desde `monitorearCoordinador()` cuando el coordinador falla, desde `simularRecuperacion()` cuando un nodo vuelve a la vida, desde `inicializar()` al arrancar, y desde el endpoint `/election/start`.

**Por qué `synchronized`:** Evita que el mismo nodo ejecute dos elecciones paralelas. Sin `synchronized`, si dos hilos llaman a este método simultáneamente, ambos podrían cambiar el estado a `ELECTION_STARTED`, enviar mensajes ELECTION duplicados, y producir condiciones de carrera en las variables de control.

**Qué hace, paso a paso:**

1. Si está `DOWN`, retorna inmediatamente. Un nodo caído no participa en elecciones.

2. Genera un nuevo `electionEpoch` con el timestamp actual. Este valor sirve para identificar esta elección específica y descartar mensajes de elecciones anteriores.

3. Cambia estado a `ELECTION_STARTED` e incrementa el contador de métricas.

4. Obtiene la lista de nodos con ID mayor (`getHigherPriorityNodes()`).

5. Si la lista está vacía, este nodo es el de mayor ID disponible → llama a `proclamarCoordinador(epoch)` directamente sin enviar ningún mensaje.

6. Si hay nodos superiores, crea un `CountDownLatch` inicializado con el número de nodos a contactar, y un `AtomicBoolean` `recibiOk` inicializado en `false`.

7. Para cada nodo superior, lanza una tarea en el scheduler que llama a `network.sendElectionAndWaitOk(targetId)`. Si recibe OK, pone `recibiOk = true` y hace `latch.countDown()`.

8. El hilo principal espera en `latch.await(electionTimeoutMs)`. Si el latch llega a 0 antes del timeout (todos respondieron), continúa. Si vence el timeout, continúa de todas formas.

9. Verifica si el epoch cambió durante la espera. Si cambió, significa que llegó un COORDINATOR mientras esperaba — la elección ya está resuelta, no hace nada.

10. Si `recibiOk` es `true`: alguien con mayor prioridad está vivo. Cambia a `WAITING_FOR_OK` y programa un timeout de seguridad (`electionTimeoutMs * 2`) por si el nodo superior también cae antes de proclamarse.

11. Si `recibiOk` es `false`: nadie respondió. Este nodo es el mayor activo → llama a `proclamarCoordinador(epoch)`.

---

#### `proclamarCoordinador(long epoch)`

```java
private void proclamarCoordinador(long epoch)
```

**Cuándo se llama:** Desde `iniciarEleccion()` cuando nadie respondió OK, o directamente cuando el nodo detecta que es el de mayor ID.

**Qué hace, paso a paso:**

1. Si está `DOWN`, retorna inmediatamente.

2. Verifica que el epoch no haya cambiado. Si cambió (otro nodo se coronó mientras tanto), retorna sin proclamarse. El parámetro especial `-1` se usa internamente para saltar esta verificación en llamadas directas.

3. Cambia estado a `COORDINATOR`, se registra como coordinador actual, incrementa métricas, y resetea `missedHeartbeats`.

4. Obtiene la lista de todos los peers excepto sí mismo y lanza una tarea asíncrona en el scheduler para anunciar COORDINATOR a cada uno de ellos, en secuencia.

**Por qué el anuncio es asíncrono:** Bloquear el hilo principal durante el broadcast podría retrasar la respuesta a heartbeats entrantes y otros mensajes. Al hacerlo en el scheduler, el coordinador puede empezar a responder heartbeats de inmediato.

---

#### `procesarMensaje(BullyMessage mensaje)`

```java
public BullyMessage procesarMensaje(BullyMessage mensaje)
```

**Cuándo se llama:** Cada vez que llega un mensaje HTTP a `/bully/message`. Lo llama el controller.

**Qué hace:** Es el dispatcher central. Lee el tipo de mensaje y delega al handler correspondiente usando un `switch` expression de Java 14+:

- `ELECTION` → `manejarElection(remitente)`
- `OK` → retorna `null` (los OK llegan de forma síncrona como respuesta HTTP, no como mensajes independientes)
- `COORDINATOR` → `manejarCoordinator(remitente)`
- `HEARTBEAT` → `manejarHeartbeat(remitente)`
- Cualquier otro → retorna `null`

---

#### `manejarElection(int remitenteId)`

```java
private BullyMessage manejarElection(int remitenteId)
```

**Cuándo se llama:** Cuando este nodo recibe un mensaje ELECTION de un nodo con menor ID.

**Qué hace:**

1. Incrementa el contador de OKs enviados (métrica).
2. Si no está `DOWN`, lanza su propia elección en background (no bloqueante para no retrasar la respuesta OK).
3. Retorna `BullyMessage.ok(nodeId)` como respuesta HTTP síncrona.

**Por qué inicia su propia elección:** El algoritmo Bully requiere que el nodo que responde OK no solo rechace al candidato inferior, sino que también busque si hay alguien aún mayor que él activo. Si este nodo tiene ID 4 y recibe ELECTION del nodo 2, responde OK y luego intenta contactar al nodo 5. Si el nodo 5 no responde, el nodo 4 se convierte en el nuevo coordinador.

**La respuesta OK es síncrona:** El remitente está esperando la respuesta HTTP de este POST. Si no llega en `electionTimeoutMs`, el remitente asume que este nodo está caído. Por eso el OK se retorna directamente como respuesta del controlador, sin pasar por el scheduler.

---

#### `manejarCoordinator(int remitenteId)`

```java
private BullyMessage manejarCoordinator(int remitenteId)
```

**Cuándo se llama:** Cuando este nodo recibe un anuncio COORDINATOR de otro nodo.

**Qué hace:**

1. Verifica si el remitente tiene mayor ID que el coordinador actual conocido, o si no hay coordinador (`actual < 0`).

2. Si se acepta al nuevo coordinador: actualiza `electionEpoch` (esto cancela cualquier timeout de elección pendiente), actualiza `currentCoordinatorId`, resetea `missedHeartbeats` y `lastHeartbeatMs`.

3. Si este nodo no es coordinador, cambia a `ALIVE`. Si este nodo era coordinador pero llegó un anuncio de alguien con mayor ID, también cambia a `ALIVE` (cede el liderazgo).

4. Si el remitente tiene menor ID que el coordinador actual conocido, ignora el mensaje (es un mensaje tardío de una elección antigua).

5. En todos los casos, retorna `COORDINATOR_ACK`.

**Por qué la verificación de ID mayor:** Protege contra mensajes tardíos de Toxiproxy. Si el nodo 3 se coronó coordinador en una elección anterior y ese mensaje COORDINATOR llega con 10 segundos de delay cuando ya el nodo 5 es el nuevo coordinador, la verificación `remitenteId > actual` lo descarta correctamente.

---

#### `manejarHeartbeat(int remitenteId)`

```java
private BullyMessage manejarHeartbeat(int remitenteId)
```

**Cuándo se llama:** Cuando este nodo recibe un heartbeat de otro nodo.

**Qué hace:** Si el estado actual es `COORDINATOR`, retorna `HEARTBEAT_ACK`. Si no es coordinador, retorna `null` (sin respuesta).

**Por qué solo el coordinador responde:** Los heartbeats van dirigidos al coordinador actual. Si un nodo subordinado respondiera, el remitente podría confundirlo con el coordinador y dejar de detectar que el coordinador real está caído.

---

#### `cambiarEstado(NodeState nuevo)`

```java
private void cambiarEstado(NodeState nuevo)
```

**Qué hace:** Actualiza `state` de forma atómica con `getAndSet()`, registra el timestamp del cambio en `lastStateChangeMs`, y si el estado cambió (el anterior era distinto), imprime un log con la transición `anterior → nuevo`.

**Por qué centralizado:** Tener un único punto de cambio de estado garantiza que el timestamp siempre se actualiza y que el log siempre se imprime. Evita que distintas partes del código actualicen `state` directamente olvidando actualizar las métricas.

---

#### `obtenerInfo()`

```java
public NodeInfo obtenerInfo()
```

**Qué hace:** Construye y retorna un DTO `NodeInfo` con todos los datos de estado actuales: `nodeId`, `state`, `currentCoordinatorId`, `lastHeartbeatMs`, métricas de elecciones, y una descripción textual del estado para el dashboard. La descripción es un `switch` expression que traduce cada `NodeState` a una frase legible por humanos.

---

#### `simularCaida()`

```java
public void simularCaida()
```

**Qué hace:** Cambia el estado a `DOWN` y limpia `currentCoordinatorId` a `-1`. No detiene el proceso Java ni el servidor HTTP (los puertos siguen escuchando), pero Toxiproxy ya bloqueó el tráfico externo antes de llamar a este método. La combinación de ambos simula una caída real: el tráfico de red está bloqueado y el estado interno refleja que el nodo sabe que está "caído".

---

#### `simularRecuperacion()`

```java
public void simularRecuperacion()
```

**Qué hace:** Limpia `currentCoordinatorId`, resetea `missedHeartbeats`, cambia estado a `ALIVE`, y programa una elección con 1500ms de delay. El delay permite que Toxiproxy termine de restaurar la conectividad antes de que el nodo intente comunicarse.

**Por qué inicia una elección al recuperarse:** Al volver, el nodo no sabe quién es el coordinador actual. Según el algoritmo Bully, un nodo que se recupera debe iniciar una elección para descubrir o proclamarse coordinador. Si tiene mayor ID que el coordinador actual (ej: el nodo 5 vuelve cuando el nodo 4 es coordinador), ganará la elección y recuperará el liderazgo.

---

#### `iniciarEleccionSiNecesario()`

```java
public void iniciarEleccionSiNecesario()
```

**Qué hace:** Wrapper público simple. Si el nodo no está `DOWN`, lanza una elección en el scheduler. Existe para que el endpoint `/election/start` del controller pueda forzar una elección sin acceso directo al scheduler interno.

---

### 6.2 `NetworkService.java`

Abstrae toda la comunicación HTTP entre nodos. `BullyElectionService` nunca llama a `RestTemplate` directamente — siempre pasa por `NetworkService`.

---

#### `sendMessage(int targetNodeId, BullyMessage message)`

```java
public Optional<BullyMessage> sendMessage(int targetNodeId, BullyMessage message)
```

**Qué hace:**

1. Obtiene la URL del nodo destino desde `config.getPeerUrl(targetNodeId)`. Si la URL no existe (nodo desconocido), retorna `Optional.empty()` y logea un warning.

2. Construye la URL del endpoint: `{peerUrl}/bully/message`.

3. Registra el timestamp de inicio para calcular latencia.

4. Construye la request HTTP con headers `Content-Type: application/json` y ejecuta el POST con `RestTemplate`.

5. Si la respuesta es exitosa, logea la latencia y retorna `Optional.of(response.getBody())`.

6. Si hay `ResourceAccessException` (timeout o conexión rechazada — que es lo que Toxiproxy produce al bloquear el tráfico), logea el fallo con la latencia y retorna `Optional.empty()`.

7. Cualquier otra excepción también retorna `Optional.empty()` y se logea como error.

**Por qué `Optional`:** El llamador debe siempre manejar el caso de que el nodo no responda. `Optional` hace esto explícito en la firma del método — no se puede ignorar accidentalmente el caso de fallo como sí ocurre con un `null`.

---

#### `sendElectionAndWaitOk(int targetNodeId)`

```java
public boolean sendElectionAndWaitOk(int targetNodeId)
```

**Qué hace:** Construye un mensaje `ELECTION`, lo envía con `sendMessage()`, y verifica si la respuesta tiene tipo `OK`. Retorna `true` si recibió OK, `false` en cualquier otro caso (timeout, error, respuesta de tipo distinto).

**Por qué retorna `boolean` y no `Optional`:** El llamador solo necesita saber "¿alguien respondió OK?". El booleano comunica exactamente eso sin over-engineering.

---

#### `announceCoordinator(int targetNodeId)`

```java
public void announceCoordinator(int targetNodeId)
```

**Qué hace:** Construye un mensaje `COORDINATOR` y lo envía con `sendMessage()`. Descarta la respuesta. El ACK que retornan los nodos se ignora aquí — el coordinador no necesita confirmación para funcionar correctamente en esta implementación.

---

#### `sendHeartbeat(int coordinatorId)`

```java
public boolean sendHeartbeat(int coordinatorId)
```

**Qué hace:** Construye un mensaje `HEARTBEAT`, lo envía con `sendMessage()`, y verifica si la respuesta tiene tipo `HEARTBEAT_ACK`. Retorna `true` si el coordinador está vivo y respondió, `false` si no.

---

### 6.3 `BullyController.java`

Es la puerta de entrada HTTP del nodo. Traduce requests HTTP en llamadas al servicio y respuestas del servicio en responses HTTP.

---

#### `recibirMensaje(@RequestBody BullyMessage mensaje)`

```java
@PostMapping("/message")
public ResponseEntity<BullyMessage> recibirMensaje(@RequestBody BullyMessage mensaje)
```

**Qué hace:** Recibe un mensaje JSON, lo deserializa automáticamente (Spring lo hace via Jackson), lo pasa a `electionService.procesarMensaje()`, y retorna la respuesta. Si el servicio retorna `null` (ej: para mensajes tipo OK que no generan respuesta), retorna HTTP 200 con body vacío. Si retorna un mensaje, lo serializa a JSON y lo retorna.

**Por qué `ResponseEntity.ok().build()` para null:** Spring necesita un status code explícito. HTTP 200 vacío es la respuesta correcta para "recibí tu mensaje pero no tengo nada que decirte", que es el caso de recibir un OK (que ya fue procesado síncronamente como respuesta HTTP en el otro sentido).

---

#### `obtenerEstado()`

```java
@GetMapping("/status")
public ResponseEntity<NodeInfo> obtenerEstado()
```

**Qué hace:** Llama a `electionService.obtenerInfo()` y retorna el DTO como JSON. Este endpoint es el que consultan el dashboard y `chaos.py` para mostrar el estado del nodo.

---

#### `forzarEleccion()`

```java
@PostMapping("/election/start")
public ResponseEntity<String> forzarEleccion()
```

**Qué hace:** Llama a `electionService.iniciarEleccionSiNecesario()` y retorna un string de confirmación. Útil para pruebas manuales con `curl` sin necesitar `chaos.py`.

---

#### `simularCaida()` y `simularRecuperacion()`

```java
@PostMapping("/chaos/kill")
@PostMapping("/chaos/recover")
```

**Qué hacen:** Llaman a los métodos correspondientes del servicio. `chaos.py` los usa como segundo paso después de configurar Toxiproxy. También pueden llamarse directamente con `curl` para pruebas.

---

#### `health()`

```java
@GetMapping("/health")
public ResponseEntity<String> health()
```

**Qué hace:** Retorna HTTP 200 con el string `"OK"`. Es un health check simple que permite a herramientas externas verificar si el proceso Java está vivo, independientemente del estado del algoritmo.

---

### 6.4 `NodeConfig.java`

DTO de configuración mapeado desde `application.yml` y variables de entorno.

---

#### `getHigherPriorityNodes()`

```java
public List<Integer> getHigherPriorityNodes()
```

**Qué hace:** Filtra el mapa de peers para retornar solo los IDs mayores que `nodeId`, ordenados ascendentemente. Usado por `iniciarEleccion()` para saber a quién enviar mensajes ELECTION.

---

#### `getLowerPriorityNodes()`

```java
public List<Integer> getLowerPriorityNodes()
```

**Qué hace:** Filtra el mapa de peers para retornar solo los IDs menores que `nodeId`, ordenados ascendentemente. Disponible pero no usado en el flujo principal — puede usarse para auditoría o extensiones.

---

#### `getPeerUrl(int targetNodeId)`

```java
public String getPeerUrl(int targetNodeId)
```

**Qué hace:** Retorna la URL base del proxy Toxiproxy para el nodo destino. Retorna `null` si el ID no existe en el mapa. Esta URL ya apunta a Toxiproxy, no al nodo directamente, lo que garantiza que todo el tráfico pase por la simulación de red.

---

### 6.5 `RestClientConfig.java`

---

#### `restTemplate()`

```java
@Bean
public RestTemplate restTemplate()
```

**Qué hace:** Crea y registra un bean `RestTemplate` con timeouts explícitos: `connectTimeout` de 1500ms y `readTimeout` de 3500ms. Spring lo inyecta automáticamente en `NetworkService`.

**Por qué estos timeouts:** El `connectTimeout` (tiempo para establecer la conexión TCP) debe ser corto porque si Toxiproxy bloquea la conexión, falla de inmediato o no. El `readTimeout` (tiempo para recibir la respuesta) debe ser mayor que la latencia máxima esperada de Toxiproxy. El `electionTimeoutMs` configurado en los nodos (6000ms) es mayor que el `readTimeout` (3500ms), lo que garantiza que el timeout de red ocurra antes que el timeout del algoritmo.

---

### 6.6 `BullyMessage.java`

DTO que representa todos los mensajes del protocolo. Se serializa/deserializa automáticamente a JSON por Jackson.

**Campos:**
- `senderId` — ID del nodo que envía el mensaje
- `type` — tipo del mensaje (`MessageType` enum)
- `timestamp` — epoch ms del momento de creación
- `payload` — información adicional opcional (usado en COORDINATOR para incluir el texto "Nodo X es el nuevo coordinador")

**Métodos de factoría estáticos:** `election()`, `ok()`, `coordinator()`, `heartbeat()`. Cada uno crea un mensaje del tipo correspondiente con el `senderId` y `timestamp` correctos. El patrón factory simplifica la creación de mensajes y centraliza los valores por defecto.

---

### 6.7 `MessageType.java`

Enum con los 6 tipos de mensajes del protocolo:

| Tipo | Dirección | Propósito |
|---|---|---|
| `ELECTION` | Nodo P → nodos con ID > P | Iniciar elección |
| `OK` | Nodo Q → nodo P | "Yo me encargo, tú cedes" |
| `COORDINATOR` | Ganador → todos | "Soy el nuevo líder" |
| `COORDINATOR_ACK` | Receptor → ganador | "Recibí y acepto tu liderazgo" |
| `HEARTBEAT` | Subordinado → coordinador | "¿Sigues vivo?" |
| `HEARTBEAT_ACK` | Coordinador → subordinado | "Sí, sigo aquí" |

---

### 6.8 `NodeState.java`

Enum con los 5 estados posibles de un nodo y las transiciones válidas:

```
ALIVE ──────────────────► ELECTION_STARTED
  ▲                              │
  │                              ▼
  │                        WAITING_FOR_OK
  │                         │         │
  │                   (OK llegó   (timeout)
  │                   a tiempo)      │
  │                         │         │
  └─────────────────────────┘         │
                                      ▼
CUALQUIERA ──► DOWN ──► ALIVE    COORDINATOR
                                      │
                                      └──► ALIVE (cede a nodo mayor)
```

---

### 6.9 `NodeInfo.java`

DTO de solo lectura que expone el estado completo del nodo para consumo externo (dashboard, `chaos.py`). Se construye en `obtenerInfo()` con un snapshot puntual de todos los atómicos.

**Campos expuestos:**
- `nodeId`, `state`, `currentCoordinatorId` — estado fundamental
- `lastHeartbeatMs` — timestamp del último heartbeat exitoso
- `electionsStarted`, `okMessagesSent`, `timesElectedCoordinator` — métricas de actividad
- `lastStateChangeMs` — para calcular cuánto tiempo lleva en el estado actual
- `statusDescription` — texto legible para el dashboard

---

### 6.10 `BullyNodeApplication.java`

Punto de entrada de Spring Boot.

**Anotaciones relevantes:**
- `@SpringBootApplication` — habilita autoconfiguración, component scan y configuración de beans
- `@EnableScheduling` — activa el soporte para `@Scheduled`, necesario para `monitorearCoordinador()`
- `@EnableConfigurationProperties` — activa el mapeo de propiedades a beans (`NodeConfig`)

---

### 6.11 `chaos.py`

Script de control y observabilidad del sistema.

---

#### `agregar_toxic(nid, tipo, attrs, nombre)`

**Qué hace:** Envía un POST a la API de Toxiproxy para agregar un "toxic" al proxy del nodo indicado. Un toxic modifica el tráfico: `timeout` con `timeout=0` corta todas las conexiones inmediatamente, `latency` añade demora en ms, `bandwidth` limita el caudal en KB/s. Maneja el caso de toxic ya existente (HTTP 409) sin error.

---

#### `limpiar_toxics(nid)`

**Qué hace:** Consulta todos los toxics activos en el proxy del nodo, luego hace DELETE para cada uno. Restaura el nodo a conectividad normal sin reiniciar Toxiproxy.

---

#### `matar_nodo(nid)`

**Qué hace:** Combina dos acciones: agrega un toxic `timeout` en Toxiproxy (bloquea el tráfico de red) y hace POST a `/bully/chaos/kill` en el nodo (actualiza el estado interno a DOWN). El POST puede fallar si el timeout de Toxiproxy se aplica antes de que llegue la request — por eso está en un `try/except` silencioso.

---

#### `recuperar_nodo(nid)`

**Qué hace:** Combina dos acciones: llama a `limpiar_toxics()` para restaurar la red y hace POST a `/bully/chaos/recover` para que el nodo inicie el proceso de rejoin.

---

#### `obtener_estado(nid)`

**Qué hace:** GET a `/bully/status` del nodo indicado. Retorna el dict JSON o `None` si el nodo no responde.

---

#### `mostrar_tabla(titulo)`

**Qué hace:** Consulta el estado de los 5 nodos en paralelo (llamadas secuenciales en un loop) y construye una tabla `rich` con: ID del nodo, estado (con color e ícono), coordinador conocido, número de elecciones iniciadas, número de veces electo coordinador, y descripción del estado. Los nodos que no responden se muestran como "INACCESIBLE".

---

#### `_hilo_monitor()`

**Qué hace:** Función ejecutada en un hilo daemon. Cada 2 segundos consulta el estado de todos los nodos y compara con el estado anterior. Si detecta un cambio, imprime la transición en consola con timestamp y mensajes descriptivos adicionales (ej: "¡NODO 4 ES EL NUEVO COORDINADOR!"). El diccionario `_prev` guarda el estado anterior de cada nodo entre iteraciones.

---

#### `iniciar_monitor()` / `detener_monitor()`

**Qué hacen:** Inician y detienen el hilo de monitoreo continuo. El hilo es daemon (`daemon=True`), lo que significa que se termina automáticamente cuando el proceso principal termina — no bloquea la salida del script.

---

#### `esperar(seg, msg, intervalo)`

**Qué hace:** Espera `seg` segundos mostrando la tabla de estado cada `intervalo` segundos. Permite observar la evolución del algoritmo durante los escenarios sin saturar la pantalla con actualizaciones constantes.

---

#### `escenario_1()` al `escenario_5()`

Cada función implementa un escenario de caos completo con sus propias esperas y observaciones:

- **Escenario 1:** Mata el nodo 5 (coordinador), espera que el nodo 4 tome el control, luego recupera el nodo 5 y observa que recupera el liderazgo.
- **Escenario 2:** Mata nodos 5 y 4 en secuencia, el nodo 3 debe convertirse en coordinador.
- **Escenario 3:** Inyecta latencia alta (6000ms + jitter 1000ms) al nodo 5 para demostrar elecciones falsas por timeout de heartbeat.
- **Escenario 4:** Cascada completa 5→4→3→2, el nodo 1 queda solo, luego recuperación en orden inverso.
- **Escenario 5:** Red degradada con jitter 2s y bandwidth 50KB/s en nodos 4 y 5, comportamiento no determinista.

---

#### `demo_completa()`

**Qué hace:** Ejecuta los 5 escenarios en secuencia. Entre cada escenario, limpia todos los toxics y recupera todos los nodos, luego espera 20 segundos para que el sistema se estabilice. Inicia el monitor de hilo al comienzo para capturar todas las transiciones en tiempo real.

---

#### `menu()` y bloque `__main__`

**Qué hacen:** Si se ejecuta sin argumentos, muestra un menú interactivo en bucle. Si se ejecuta con argumentos (`--demo`, `--status`, `--kill N`, `--recover N`, `--monitor`), ejecuta la acción correspondiente directamente sin mostrar el menú. `argparse` gestiona el parseo de argumentos.

---

### 6.12 `index.html`

Dashboard de monitoreo de una sola página. Todo el código (HTML, CSS, JS) está en un único archivo servido por Nginx.

---

#### `fetchNode(id)`

```javascript
async function fetchNode(id)
```

**Qué hace:** Fetch a `/bully/status` del nodo indicado con un `AbortSignal.timeout(2500)` para cancelar la request si tarda más de 2.5 segundos. Retorna el JSON parseado o `null` si hay error o timeout.

---

#### `refresh()`

```javascript
async function refresh()
```

**Qué hace:** Llama a `fetchNode` para los 5 nodos en paralelo con `Promise.all()`, actualiza las tarjetas con `updateCard()`, y recalcula las métricas globales (coordinador actual, nodos vivos, total de elecciones). Se ejecuta inmediatamente al cargar y luego cada 2000ms con `setInterval`.

---

#### `updateCard(id, info)`

```javascript
function updateCard(id, info)
```

**Qué hace:** Actualiza la tarjeta visual de un nodo. Compara el estado actual con el anterior (`prevState[id]`). Si detecta un cambio, llama a `onStateChange()`. Actualiza el `data-state` del elemento DOM (que CSS usa para aplicar colores y animaciones), el badge de estado, el título (añade 👑 solo al coordinador), y las 4 métricas (coordinador conocido, elecciones, veces líder, tiempo en estado actual).

---

#### `onStateChange(nid, prev, next)`

```javascript
function onStateChange(nid, prev, next)
```

**Qué hace:** Registra la transición en `stEvents` (con timestamp, nodo, estados anterior y siguiente, y descripción semántica del mapa `TRANS_DESC`). Determina el mensaje de actividad apropiado según el estado nuevo y lo agrega al log de actividad. Actualiza el contador global de eventos. Llama a `renderStateLog()` para refrescar la lista visible.

---

#### `buildCard(id)`

```javascript
function buildCard(id)
```

**Qué hace:** Crea y retorna el elemento DOM de una tarjeta de nodo con su estructura HTML interna (header con título y badge, grilla de 4 métricas, descripción). Se llama una vez al inicio para cada nodo y el resultado se inserta en el grid.

---

### 6.13 `nginx.conf`

Configuración del servidor web Nginx que sirve el dashboard.

**`location /api/nodeN/`:** Cinco bloques de proxy inverso, uno por nodo. Redirigen requests de la forma `/api/node1/status` al endpoint `http://node-1:8080/bully/status` dentro de la red Docker. Usan `set $up node-1` con una variable para evitar que Nginx falle al arrancar si el nodo no está disponible todavía (con una variable, Nginx resuelve el hostname en cada request, no al arrancar). `add_header Access-Control-Allow-Origin *` permite requests cross-origin desde el browser.

**`location /`:** Sirve el `index.html` para cualquier ruta no macheada por las anteriores. `try_files $uri $uri/ /index.html` soporta el patrón SPA (Single Page Application).

**`resolver 127.0.0.11`:** Es el resolver DNS interno de Docker. Necesario para que `set $up node-1` pueda resolverse dinámicamente dentro de la red Docker.

---

### 6.14 `init-proxies.sh`

Script de inicialización de Toxiproxy.

**Bucle de espera:** `until curl -sf "${TOXIPROXY_API}/version"` ejecuta `curl` en un bucle con `sleep 1` hasta que Toxiproxy responde. `-s` suprime output, `-f` retorna código de error en respuestas HTTP >= 400. Esto garantiza que el script no intente crear proxies antes de que Toxiproxy esté listo.

**Creación de proxies:** Cinco llamadas POST idénticas en estructura, una por nodo. Cada una crea un proxy con nombre único (`node-N-proxy`), que escucha en `0.0.0.0:1888N` dentro del contenedor de Toxiproxy (y está expuesto al host por `docker-compose.yml`), y que redirige tráfico a `node-N:8080` (hostname resuelto por DNS interno de Docker).

**`set -e`:** Hace que el script falle inmediatamente si cualquier comando retorna un código de error distinto de 0. Evita que el script continúe si falla la creación de un proxy.

---

### 6.15 `docker-compose.yml`

Orquestación completa del sistema.

**`networks: bully-network`:** Red virtual bridge compartida por todos los contenedores. Permite que se comuniquen por hostname (`node-1`, `toxiproxy`, etc.) sin exponer ports innecesariamente entre ellos.

**`volumes: logs-node-N`:** Volúmenes nombrados para los logs de cada nodo. Persisten entre reinicios del contenedor. Permiten leer logs históricos con `docker compose logs` incluso después de reiniciar.

**`toxiproxy`:** Imagen oficial de Shopify. Expone el puerto de administración `:8474` y los 5 puertos de proxy `:18881-:18885` tanto en la red interna como en el host.

**`toxiproxy-init`:** Contenedor de un solo uso que ejecuta `init-proxies.sh`. Usa `curlimages/curl` (imagen mínima con solo `curl` y `sh`). `restart: "no"` asegura que no se reinicie si falla — falla visible. `depends_on: toxiproxy` garantiza que Toxiproxy arranque antes.

**`node-N`:** Los 5 nodos Spring Boot. Variables de entorno críticas:
- `NODE_ID` — el identificador único del nodo
- `PEER_N_URL` — las URLs de Toxiproxy para cada peer (no directamente a los otros nodos)
- Timeouts configurables sin recompilar el código

`depends_on: toxiproxy-init` garantiza que los proxies estén creados antes de que los nodos intenten comunicarse. `restart: unless-stopped` reinicia el contenedor automáticamente si crashea, simulando la resiliencia de un sistema real.

**`dashboard`:** Nginx sirviendo el dashboard. `depends_on` de los 5 nodos garantiza que el dashboard solo arranque cuando los nodos están disponibles. Monta tanto `index.html` como `nginx.conf` como volúmenes de solo lectura desde el host, lo que permite editar el dashboard sin reconstruir la imagen.
