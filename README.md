# Simulador del Algoritmo de Elección Bully

Implementación del **Algoritmo de Elección Bully** (Garcia-Molina, 1982) usando microservicios Java/Spring Boot, Docker y Toxiproxy para inyección de fallos de red en tiempo real.

> Funciona en **Windows, macOS y Linux** sin instalar Java, Python ni ninguna dependencia adicional. Todo corre dentro de Docker.

---

## Requisitos

| Herramienta | Versión mínima | Descarga |
|---|---|---|
| Docker Desktop | 24+ | https://www.docker.com/products/docker-desktop |
| Git | cualquiera | https://git-scm.com |

> En Linux puedes usar Docker Engine en lugar de Docker Desktop. En Windows y macOS, Docker Desktop es lo más sencillo.

---

## Instalación

```bash
git clone https://github.com/TU_USUARIO/bully-simulator.git
cd bully-simulator
```

---

## Levantar el sistema

```bash
docker compose up --build
```

La primera vez tarda unos minutos mientras descarga las imágenes y compila el código Java. Las veces siguientes es mucho más rápido.

Cuando veas en los logs algo como:

```
node-5  | SOY EL NUEVO COORDINADOR
```

el sistema está listo. El nodo 5 (mayor ID) siempre gana la primera elección.

Para ver los logs de un nodo específico en otra terminal:

```bash
docker compose logs -f node-5
docker compose logs -f node-1 node-2 node-3
```

---

## Acceder a los servicios

| Servicio | URL |
|---|---|
| Dashboard web | http://localhost:3000 |
| API Nodo 1 | http://localhost:8081/bully/status |
| API Nodo 2 | http://localhost:8082/bully/status |
| API Nodo 3 | http://localhost:8083/bully/status |
| API Nodo 4 | http://localhost:8084/bully/status |
| API Nodo 5 | http://localhost:8085/bully/status |
| Toxiproxy API | http://localhost:8474/proxies |

---

## Estados del nodo

| Estado | Descripción |
|---|---|
| `ALIVE` | Operativo, enviando heartbeats al coordinador |
| `ELECTION_STARTED` | Detectó falla del coordinador, enviando mensajes ELECTION a nodos superiores |
| `WAITING_FOR_OK` | Cedió la elección, esperando anuncio COORDINATOR |
| `COORDINATOR` | Ganó la elección, aceptando heartbeats de todos |
| `DOWN` | Caído (simulado por Toxiproxy) |

**Prioridad de nodos:** Nodo 5 (más alta) > Nodo 4 > Nodo 3 > Nodo 2 > Nodo 1 (más baja)

---

## Probar los escenarios

### Ver estado de todos los nodos

```bash
# Con el script de caos (requiere Python 3 con pip)
pip install requests rich
python3 chaos-scripts/chaos.py --status

# Sin Python, directo por curl
curl http://localhost:8085/bully/status
curl http://localhost:8081/bully/status
```

---

### Escenario 1 — Matar al coordinador

```bash
python3 chaos-scripts/chaos.py --kill 5
```

**Resultado esperado:** el nodo 4 detecta heartbeats fallidos → inicia elección → se corona coordinador.

---

### Escenario 2 — Latencia alta (elección falsa)

```bash
python3 chaos-scripts/chaos.py --latency 5 3500
```

**Resultado esperado:** los heartbeats al nodo 5 llegan tarde y algún nodo inferior inicia una elección innecesaria aunque el nodo 5 sigue vivo.

---

### Escenario 3 — Falla en cascada

```bash
python3 chaos-scripts/chaos.py --kill 5
sleep 8
python3 chaos-scripts/chaos.py --kill 4
sleep 8
python3 chaos-scripts/chaos.py --kill 3
```

**Resultado esperado:** 3 elecciones consecutivas, el nodo 2 termina como coordinador.

---

### Escenario 4 — Recuperar un nodo caído

```bash
python3 chaos-scripts/chaos.py --recover 5
```

**Resultado esperado:** el nodo 5 vuelve, inicia elección y recupera el liderazgo por tener el ID más alto.

---

### Escenario 5 — Demo automática completa

```bash
python3 chaos-scripts/chaos.py --demo
```

Ejecuta todos los escenarios en secuencia (~3 minutos). Puedes seguirlo en paralelo desde el dashboard en http://localhost:3000.

---

### Forzar elección manualmente (sin Python)

```bash
curl -X POST http://localhost:8083/bully/election/start
```

---

### Inyectar fallos manualmente con Toxiproxy

```bash
# Agregar latencia de 2 segundos al nodo 4
curl -X POST http://localhost:8474/proxies/node-4-proxy/toxics \
  -H "Content-Type: application/json" \
  -d '{"name":"lat","type":"latency","attributes":{"latency":2000}}'

# Cortar conexión del nodo 5 (timeout inmediato)
curl -X POST http://localhost:8474/proxies/node-5-proxy/toxics \
  -H "Content-Type: application/json" \
  -d '{"name":"kill","type":"timeout","attributes":{"timeout":0}}'

# Eliminar un toxic (restaurar nodo)
curl -X DELETE http://localhost:8474/proxies/node-5-proxy/toxics/kill

# Ver todos los proxies activos
curl http://localhost:8474/proxies
```

---

## Apagar el sistema

```bash
# Detener contenedores (los logs se conservan)
docker compose down

# Detener y borrar todo (logs incluidos)
docker compose down -v
```

---

## Arquitectura

```
┌────────────────────────────────────────────────┐
│                 bully-network                  │
│                                                │
│  ┌───────┐ ┌───────┐ ┌───────┐ ┌───────┐ ┌───────┐  │
│  │Nodo 1 │ │Nodo 2 │ │Nodo 3 │ │Nodo 4 │ │Nodo 5 │  │
│  │ :8080 │ │ :8080 │ │ :8080 │ │ :8080 │ │ :8080 │  │
│  └───┬───┘ └───┬───┘ └───┬───┘ └───┬───┘ └───┬───┘  │
│      └─────────┴─────────┴─────────┴─────────┘      │
│                    Todo el tráfico                    │
│                    pasa por Toxiproxy                 │
│  ┌────────────────────────────────────────────┐      │
│  │             TOXIPROXY  :8474               │      │
│  │  :18881→node-1  :18882→node-2              │      │
│  │  :18883→node-3  :18884→node-4              │      │
│  │  :18885→node-5                             │      │
│  └────────────────────────────────────────────┘      │
│                                                │
│  ┌─────────────────────────┐                  │
│  │  Dashboard  :80 / :3000 │                  │
│  └─────────────────────────┘                  │
└────────────────────────────────────────────────┘
```

---

## Estructura del proyecto

```
bully-simulator/
├── docker-compose.yml
├── chaos-scripts/
│   └── chaos.py
├── dashboard/
│   ├── index.html
│   └── nginx.conf
├── toxiproxy-init/
│   └── init-proxies.sh
└── node-service/
    ├── Dockerfile
    ├── pom.xml
    └── src/main/java/com/bully/
        ├── BullyNodeApplication.java
        ├── config/
        │   ├── NodeConfig.java
        │   └── RestClientConfig.java
        ├── controller/
        │   └── BullyController.java
        ├── model/
        │   ├── BullyMessage.java
        │   ├── MessageType.java
        │   ├── NodeInfo.java
        │   └── NodeState.java
        └── service/
            ├── BullyElectionService.java
            └── NetworkService.java
```

---

## Referencia de la API REST

Todos los nodos exponen los mismos endpoints en su puerto correspondiente (8081–8085).

| Método | Endpoint | Descripción |
|---|---|---|
| GET | `/bully/status` | Estado actual del nodo |
| GET | `/bully/health` | Health check |
| POST | `/bully/message` | Enviar mensaje del protocolo Bully |
| POST | `/bully/election/start` | Forzar inicio de elección |
| POST | `/bully/chaos/kill` | Simular caída del nodo |
| POST | `/bully/chaos/recover` | Simular recuperación del nodo |

---

## Ajustar timeouts

En `docker-compose.yml`, para cada nodo:

```yaml
environment:
  ELECTION_TIMEOUT_MS: "6000"      # Aumentar si Toxiproxy añade mucha latencia
  HEARTBEAT_INTERVAL_MS: "5000"    # Intervalo entre heartbeats
  HEARTBEAT_TIMEOUT_MS: "10000"    # Tolerancia antes de asumir coordinador caído
```

Regla general: `ELECTION_TIMEOUT_MS` debe ser mayor que la latencia máxima configurada en Toxiproxy.

---

## Solución de problemas

**`docker compose` no se reconoce**
Actualiza Docker Desktop a la versión 24 o superior. En versiones antiguas el comando era `docker-compose` (con guion).

**El sistema no levanta o los nodos no se conectan**
Verifica que Docker Desktop esté corriendo antes de ejecutar el comando. En Linux, asegúrate de que tu usuario esté en el grupo `docker`:
```bash
sudo usermod -aG docker $USER
```

**Veo dos coordinadores al mismo tiempo en el dashboard**
Es un split-brain transitorio esperado cuando hay latencia alta activa en Toxiproxy. Debe resolverse solo en segundos. Si persiste sin Toxiproxy activo, revisa los logs:
```bash
docker compose logs -f node-1 node-2 node-3 node-4 node-5 | grep COORDINADOR
```

**Quiero reiniciar desde cero**
```bash
docker compose down -v
docker compose up --build
```

---

## Referencias

- Garcia-Molina, H. (1982). *Elections in a Distributed Computing System*. IEEE Transactions on Computers.
- [Toxiproxy](https://github.com/Shopify/toxiproxy) — Shopify
- [Spring Boot](https://spring.io/projects/spring-boot)
