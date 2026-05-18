#!/bin/sh
# =====================================================================
# Script de inicialización de Toxiproxy
# Crea los proxies para cada nodo del simulador Bully
#
# Arquitectura de red:
#   [Nodo A] → [proxy-nodoB:18882] → Toxiproxy → [node-2:8080]
#
# Cada proxy intercepta el tráfico hacia un nodo específico,
# permitiendo inyectar fallos de red en tiempo real.
# =====================================================================

set -e

TOXIPROXY_API="http://toxiproxy:8474"

echo "🔧 Esperando que Toxiproxy esté disponible..."
until curl -sf "${TOXIPROXY_API}/version" > /dev/null 2>&1; do
    sleep 1
done
echo "✅ Toxiproxy disponible"

echo ""
echo "🔧 Creando proxies para los 5 nodos..."

# ─────────────────────────────────────────────
# Proxy para Nodo 1 → expuesto en puerto 18881
# ─────────────────────────────────────────────
curl -sf -X POST "${TOXIPROXY_API}/proxies" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "node-1-proxy",
    "listen": "0.0.0.0:18881",
    "upstream": "node-1:8080",
    "enabled": true
  }' && echo "✓ Proxy Nodo 1 creado (puerto 18881 → node-1:8080)"

# ─────────────────────────────────────────────
# Proxy para Nodo 2 → expuesto en puerto 18882
# ─────────────────────────────────────────────
curl -sf -X POST "${TOXIPROXY_API}/proxies" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "node-2-proxy",
    "listen": "0.0.0.0:18882",
    "upstream": "node-2:8080",
    "enabled": true
  }' && echo "✓ Proxy Nodo 2 creado (puerto 18882 → node-2:8080)"

# ─────────────────────────────────────────────
# Proxy para Nodo 3 → expuesto en puerto 18883
# ─────────────────────────────────────────────
curl -sf -X POST "${TOXIPROXY_API}/proxies" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "node-3-proxy",
    "listen": "0.0.0.0:18883",
    "upstream": "node-3:8080",
    "enabled": true
  }' && echo "✓ Proxy Nodo 3 creado (puerto 18883 → node-3:8080)"

# ─────────────────────────────────────────────
# Proxy para Nodo 4 → expuesto en puerto 18884
# ─────────────────────────────────────────────
curl -sf -X POST "${TOXIPROXY_API}/proxies" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "node-4-proxy",
    "listen": "0.0.0.0:18884",
    "upstream": "node-4:8080",
    "enabled": true
  }' && echo "✓ Proxy Nodo 4 creado (puerto 18884 → node-4:8080)"

# ─────────────────────────────────────────────
# Proxy para Nodo 5 → expuesto en puerto 18885
# ─────────────────────────────────────────────
curl -sf -X POST "${TOXIPROXY_API}/proxies" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "node-5-proxy",
    "listen": "0.0.0.0:18885",
    "upstream": "node-5:8080",
    "enabled": true
  }' && echo "✓ Proxy Nodo 5 creado (puerto 18885 → node-5:8080)"

echo ""
echo "✅ Todos los proxies configurados. Listado:"
curl -sf "${TOXIPROXY_API}/proxies" | python3 -m json.tool 2>/dev/null || \
curl -sf "${TOXIPROXY_API}/proxies"
echo ""
echo "🚀 Toxiproxy listo para inyección de fallos de red"
