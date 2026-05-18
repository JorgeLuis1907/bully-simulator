#!/usr/bin/env python3
"""
Script de Caos v3 — Simulador Bully
Caídas más largas para observar el algoritmo completo
"""
import sys, time, threading, argparse
from typing import Optional

try:
    import requests
    from rich.console import Console
    from rich.table   import Table
    from rich.panel   import Panel
    from rich         import box
except ImportError:
    print("pip install requests rich")
    sys.exit(1)

TOXIPROXY_API = "http://localhost:8474"
NODE_URLS   = {i: f"http://localhost:808{i}" for i in range(1, 6)}
PROXY_NAMES = {i: f"node-{i}-proxy"          for i in range(1, 6)}
console = Console()

# ── Toxiproxy ────────────────────────────────────────────────────

def agregar_toxic(nid, tipo, attrs, nombre=None):
    nombre = nombre or f"{tipo}_{nid}"
    try:
        r = requests.post(
            f"{TOXIPROXY_API}/proxies/{PROXY_NAMES[nid]}/toxics",
            json={"name":nombre,"type":tipo,"stream":"downstream",
                  "toxicity":1.0,"attributes":attrs}, timeout=5)
        if r.status_code in (200,201):
            console.print(f"  ✅ [yellow]{tipo}[/] aplicado → Nodo {nid}")
        elif r.status_code == 409:
            console.print(f"  ⚠️  Toxic ya existe en Nodo {nid} — omitiendo")
        else:
            console.print(f"  ❌ Error {r.status_code}: {r.text[:80]}", style="red")
    except Exception as e:
        console.print(f"  ❌ Toxiproxy no responde: {e}", style="red")

def limpiar_toxics(nid):
    try:
        r = requests.get(
            f"{TOXIPROXY_API}/proxies/{PROXY_NAMES[nid]}/toxics", timeout=5)
        toxics = r.json() if r.status_code==200 else []
        for t in toxics:
            requests.delete(
                f"{TOXIPROXY_API}/proxies/{PROXY_NAMES[nid]}/toxics/{t['name']}",
                timeout=5)
        if toxics:
            console.print(f"  🧹 {len(toxics)} toxic(s) eliminados de Nodo {nid}")
    except Exception as e:
        console.print(f"  ❌ {e}", style="red")

def matar_nodo(nid):
    console.print(f"\n[bold red]☠️  MATANDO NODO {nid}[/]")
    agregar_toxic(nid, "timeout", {"timeout": 0}, f"kill_{nid}")
    try: requests.post(f"{NODE_URLS[nid]}/bully/chaos/kill", timeout=2)
    except: pass

def recuperar_nodo(nid):
    console.print(f"\n[bold green]♻️  RECUPERANDO NODO {nid}[/]")
    limpiar_toxics(nid)
    try: requests.post(f"{NODE_URLS[nid]}/bully/chaos/recover", timeout=2)
    except: pass

# ── Estado ───────────────────────────────────────────────────────

COLORES = {
    "ALIVE":"green","ELECTION_STARTED":"yellow",
    "WAITING_FOR_OK":"dark_orange","COORDINATOR":"bold gold1","DOWN":"red"
}
ICONOS = {
    "ALIVE":"✅","ELECTION_STARTED":"🗳️ ",
    "WAITING_FOR_OK":"⏳","COORDINATOR":"👑","DOWN":"💀"
}

def obtener_estado(nid) -> Optional[dict]:
    try:
        r = requests.get(f"{NODE_URLS[nid]}/bully/status", timeout=3)
        return r.json() if r.status_code==200 else None
    except: return None

def mostrar_tabla(titulo="Estado del Clúster"):
    t = Table(title=f"🗳️  {titulo}", box=box.ROUNDED,
              header_style="bold magenta", show_header=True)
    t.add_column("Nodo",        style="cyan", justify="center", width=6)
    t.add_column("Estado",      justify="center", width=22)
    t.add_column("Coordinador", justify="center", width=12)
    t.add_column("Elecciones",  justify="center", width=11)
    t.add_column("Veces Líder", justify="center", width=12)
    t.add_column("Info")
    for nid in range(1,6):
        info = obtener_estado(nid)
        if info:
            st  = info.get("state","?")
            col = COLORES.get(st,"white")
            ico = ICONOS.get(st,"")
            t.add_row(
                f"[bold]{nid}[/]",
                f"[{col}]{ico} {st}[/]",
                f"Nodo {info.get('currentCoordinatorId','?')}",
                str(info.get("electionsStarted","?")),
                str(info.get("timesElectedCoordinator","?")),
                (info.get("statusDescription","") or "")[:50])
        else:
            t.add_row(f"[bold]{nid}[/]","[red]💀 INACCESIBLE[/]",
                      "?","?","?","Sin respuesta HTTP")
    console.print(t)

# ── Monitor en tiempo real ────────────────────────────────────────

_activo = False
_prev   = {}

def _hilo_monitor():
    global _activo, _prev
    while _activo:
        for nid in range(1,6):
            info = obtener_estado(nid)
            st   = info["state"] if info else "DOWN"
            prev = _prev.get(nid)
            if prev is not None and prev != st:
                col = COLORES.get(st,"white")
                ico = ICONOS.get(st,"")
                ts  = time.strftime('%H:%M:%S')
                console.print(f"  [{col}]{ico} [{ts}] NODO {nid}: {prev} → {st}[/]")
                if st == "COORDINATOR":
                    console.print(
                        f"  [bold gold1]  👑 ¡NODO {nid} ES EL NUEVO COORDINADOR![/]")
                elif st == "ELECTION_STARTED":
                    console.print(f"  [yellow]  🗳️  Nodo {nid} inició elección[/]")
                elif st == "DOWN":
                    console.print(
                        f"  [red]  ☠️  Nodo {nid} caído — otros detectarán la falla[/]")
                elif st == "ALIVE" and prev == "DOWN":
                    console.print(
                        f"  [green]  ♻️  Nodo {nid} recuperado — iniciando rejoin[/]")
                if info:
                    console.print(
                        f"  [dim]     Elecciones: {info.get('electionsStarted',0)} | "
                        f"Veces líder: {info.get('timesElectedCoordinator',0)} | "
                        f"Coord: Nodo {info.get('currentCoordinatorId','?')}[/]")
            _prev[nid] = st
        time.sleep(2)

def iniciar_monitor():
    global _activo
    _activo = True
    th = threading.Thread(target=_hilo_monitor, daemon=True)
    th.start()
    return th

def detener_monitor():
    global _activo
    _activo = False

# ── Espera con tabla periódica ────────────────────────────────────

def esperar(seg, msg="", intervalo=10):
    """Espera 'seg' segundos mostrando la tabla cada 'intervalo' segundos."""
    if msg:
        console.print(f"\n⏳ {msg} ({seg}s)...")
    transcurrido = 0
    while transcurrido < seg:
        dormir = min(intervalo, seg - transcurrido)
        time.sleep(dormir)
        transcurrido += dormir
        if transcurrido < seg:
            mostrar_tabla(f"T+{transcurrido}s")

# ── Escenarios ────────────────────────────────────────────────────

def escenario_1():
    """Matar coordinador (Nodo 5) — Nodo 4 debe ganar."""
    console.print(Panel.fit(
        "[bold]ESCENARIO 1: Matar Coordinador (Nodo 5)[/]\n"
        "• Nodo 5 cae → los demás detectan via heartbeat (~8-12s)\n"
        "• Nodo 4 inicia elección → no hay nadie mayor → COORDINATOR\n"
        "• Duración caída: 60 segundos para observar bien",
        title="💀 Escenario 1", border_style="red"))

    mostrar_tabla("ANTES — Sistema estable")
    matar_nodo(5)

    console.print("\n[dim]Esperando que los nodos detecten la caída (~10s)...[/]")
    esperar(15, "Detección de caída y elección", intervalo=5)
    mostrar_tabla("ELECCIÓN EN PROGRESO")

    esperar(20, "Nodo 4 consolida su liderazgo", intervalo=10)
    mostrar_tabla("DESPUÉS — Nodo 4 debería ser COORDINATOR")

    console.print("\n⏳ Nodo 5 caído por 30s más para observar estabilidad...")
    esperar(30, "Sistema estable con Nodo 4 como coordinador", intervalo=15)
    mostrar_tabla("SISTEMA ESTABLE CON NUEVO COORDINADOR")

    console.print("\n♻️  Recuperando Nodo 5...")
    recuperar_nodo(5)
    esperar(30, "Nodo 5 se reintegra — debería recuperar liderazgo", intervalo=10)
    mostrar_tabla("DESPUÉS DE RECUPERACIÓN — Nodo 5 debería retomar liderazgo")

def escenario_2():
    """Matar Nodos 5 y 4 — Nodo 3 debe ganar."""
    console.print(Panel.fit(
        "[bold]ESCENARIO 2: Caída de Nodo 5 y Nodo 4[/]\n"
        "• Nodo 5 cae → Nodo 4 gana elección\n"
        "• Nodo 4 cae → Nodo 3 gana la siguiente elección\n"
        "• Luego recuperamos ambos: Nodo 5 retoma liderazgo",
        title="💥 Escenario 2 — Doble Caída", border_style="red"))

    mostrar_tabla("ANTES")

    # Matar Nodo 5 primero
    matar_nodo(5)
    esperar(30, "Esperando que Nodo 4 tome el control", intervalo=10)
    mostrar_tabla("Nodo 5 caído — Nodo 4 debería ser COORDINATOR")

    # Ahora matar Nodo 4
    matar_nodo(4)
    esperar(30, "Esperando que Nodo 3 tome el control", intervalo=10)
    mostrar_tabla("Nodos 4 y 5 caídos — Nodo 3 debería ser COORDINATOR")

    # Mantener caídos para observar estabilidad
    esperar(30, "Sistema estable con solo Nodos 1, 2, 3", intervalo=15)
    mostrar_tabla("SISTEMA ESTABLE — 3 nodos activos")

    # Recuperar en orden
    console.print("\n♻️  Recuperando Nodo 4...")
    recuperar_nodo(4)
    esperar(25, "Nodo 4 se reintegra", intervalo=10)
    mostrar_tabla("Nodo 4 recuperado")

    console.print("\n♻️  Recuperando Nodo 5...")
    recuperar_nodo(5)
    esperar(25, "Nodo 5 retoma liderazgo", intervalo=10)
    mostrar_tabla("SISTEMA COMPLETO — Nodo 5 debería ser COORDINATOR")

def escenario_3():
    """Latencia alta — elección falsa positiva."""
    console.print(Panel.fit(
        "[bold]ESCENARIO 3: Latencia Alta en Nodo 5 (6000ms)[/]\n"
        "• Latencia > heartbeat timeout → nodos creen que el\n"
        "  coordinador cayó aunque esté técnicamente vivo\n"
        "• Demuestra diferencia TEORÍA vs PRÁCTICA",
        title="🐢 Escenario 3 — Latencia Extrema", border_style="yellow"))

    mostrar_tabla("ANTES")
    console.print("\n🦥 Inyectando latencia 6000ms + jitter 1000ms al Nodo 5...")
    agregar_toxic(5, "latency", {"latency": 6000, "jitter": 1000}, "lat_alta")

    esperar(30, "Observando elección falsa positiva", intervalo=10)
    mostrar_tabla("CON LATENCIA ALTA — posible coordinador falso")

    esperar(30, "Sistema con red degradada", intervalo=15)
    mostrar_tabla("Estado con latencia continua")

    console.print("\n🧹 Restaurando latencia normal...")
    limpiar_toxics(5)
    esperar(30, "Estabilización tras restaurar latencia", intervalo=10)
    mostrar_tabla("DESPUÉS de restaurar red")

def escenario_4():
    """Cascada completa 5→4→3→2."""
    console.print(Panel.fit(
        "[bold]ESCENARIO 4: Falla en Cascada (5→4→3→2)[/]\n"
        "• Cada caída dispara una nueva elección\n"
        "• Al final solo Nodo 1 está vivo\n"
        "• Recuperación en orden inverso",
        title="🌊 Escenario 4 — Cascada Total", border_style="red"))

    mostrar_tabla("ANTES")

    for nid in [5, 4, 3, 2]:
        matar_nodo(nid)
        esperar(35, f"Esperando elección tras caída de Nodo {nid}", intervalo=10)
        mostrar_tabla(f"Tras caída de Nodo {nid}")

    console.print("\n[bold red]Solo Nodo 1 activo — no puede ser coordinador sin peers[/]")
    esperar(20, "Observando Nodo 1 solo", intervalo=10)

    console.print("\n♻️  Recuperando nodos en orden...")
    for nid in [2, 3, 4, 5]:
        recuperar_nodo(nid)
        esperar(25, f"Nodo {nid} reintegrándose", intervalo=10)
        mostrar_tabla(f"Nodo {nid} recuperado")

    mostrar_tabla("SISTEMA COMPLETAMENTE RESTAURADO")

def escenario_5():
    """Red degradada en nodos superiores."""
    console.print(Panel.fit(
        "[bold]ESCENARIO 5: Red Degradada — Jitter + Bandwidth[/]\n"
        "• Nodos 4 y 5 con jitter 2s y bandwidth 50KB/s\n"
        "• Mensajes llegan pero fuera de orden y lentamente\n"
        "• Comportamiento no determinista del algoritmo",
        title="📡 Escenario 5 — Red Degradada", border_style="yellow"))

    mostrar_tabla("ANTES")

    for nid in [4, 5]:
        console.print(f"\n🌊 Degradando red del Nodo {nid}...")
        agregar_toxic(nid, "latency",   {"latency": 2000, "jitter": 2000}, "jitter")
        agregar_toxic(nid, "bandwidth", {"rate": 50},                       "bw_limit")

    for i in range(1, 7):
        esperar(15)
        mostrar_tabla(f"T+{i*15}s con red degradada")

    console.print("\n🧹 Restaurando red...")
    for nid in [4, 5]:
        limpiar_toxics(nid)
    esperar(30, "Estabilización final", intervalo=10)
    mostrar_tabla("DESPUÉS de restaurar red")

def demo_completa():
    console.print(Panel.fit(
        "[bold cyan]DEMO COMPLETA — 5 Escenarios de Caos[/]\n"
        "Duración estimada: ~15 minutos\n"
        "Cada escenario tiene tiempo suficiente para observar\n"
        "la elección completa en el dashboard.",
        title="🎭 Demo Automática", border_style="cyan"))

    th = iniciar_monitor()
    time.sleep(2)

    escenarios = [
        escenario_1, escenario_2, escenario_3,
        escenario_4, escenario_5
    ]

    for i, fn in enumerate(escenarios, 1):
        fn()
        console.print(f"\n[dim]── Pausa entre escenarios (20s) ──[/]")
        for nid in range(1, 6):
            limpiar_toxics(nid)
            try: requests.post(f"{NODE_URLS[nid]}/bully/chaos/recover", timeout=2)
            except: pass
        esperar(20, "Estabilizando sistema", intervalo=10)
        mostrar_tabla(f"Sistema listo para Escenario {i+1}")

    detener_monitor()
    console.print("\n[bold green]✅ Demo completada.[/]")

# ── Menú ─────────────────────────────────────────────────────────

MENU = """
╔══════════════════════════════════════════════════════════════╗
║         Script de Caos v3 — Algoritmo Bully                 ║
╠══════════════════════════════════════════════════════════════╣
║  [1] Ver estado actual del clúster                           ║
║  [2] Escenario 1: Matar Nodo 5 — Nodo 4 toma control (60s)  ║
║  [3] Escenario 2: Matar N5 y N4 — Nodo 3 toma control       ║
║  [4] Escenario 3: Latencia alta → elección falsa             ║
║  [5] Escenario 4: Cascada total (5→4→3→2)                   ║
║  [6] Escenario 5: Red degradada (jitter+bandwidth)           ║
║  [7] Matar nodo específico (caída indefinida)                ║
║  [8] Recuperar nodo específico                               ║
║  [9] Limpiar todos los toxics                                ║
║  [M] Monitor en tiempo real (logs continuos en consola)      ║
║  [D] Demo completa automática (~15 min)                      ║
║  [0] Salir                                                   ║
╚══════════════════════════════════════════════════════════════╝
"""

def menu():
    monitor_th = None
    while True:
        console.print(MENU)
        op = input("Opción: ").strip().upper()

        if   op == "1": mostrar_tabla()
        elif op == "2": escenario_1()
        elif op == "3": escenario_2()
        elif op == "4": escenario_3()
        elif op == "5": escenario_4()
        elif op == "6": escenario_5()
        elif op == "7":
            try:
                nid = int(input("¿Qué nodo matar? (1-5): "))
                matar_nodo(nid)
                console.print(f"[red]Nodo {nid} caído indefinidamente. Usa opción 8 para recuperar.[/]")
            except ValueError:
                console.print("❌ Número inválido", style="red")
        elif op == "8":
            try:
                nid = int(input("¿Qué nodo recuperar? (1-5): "))
                recuperar_nodo(nid)
                esperar(20, f"Nodo {nid} reintegrándose", intervalo=5)
                mostrar_tabla()
            except ValueError:
                console.print("❌ Número inválido", style="red")
        elif op == "9":
            for nid in range(1, 6):
                limpiar_toxics(nid)
            console.print("✅ Todos los toxics eliminados — red restaurada")
        elif op == "M":
            if monitor_th and monitor_th.is_alive():
                detener_monitor()
                console.print("[yellow]Monitor detenido[/]")
                monitor_th = None
            else:
                monitor_th = iniciar_monitor()
                console.print("[green]Monitor iniciado — cambios de estado aparecerán aquí[/]")
        elif op == "D":
            demo_completa()
        elif op == "0":
            detener_monitor()
            console.print("👋 Hasta luego")
            break
        else:
            console.print("❌ Opción inválida", style="red")
        print()

# ── Punto de entrada ─────────────────────────────────────────────

if __name__ == "__main__":
    p = argparse.ArgumentParser(description="Script de Caos v3 — Simulador Bully")
    p.add_argument("--demo",    action="store_true", help="Demo completa")
    p.add_argument("--status",  action="store_true", help="Ver estado")
    p.add_argument("--kill",    type=int, metavar="N", help="Matar nodo N")
    p.add_argument("--recover", type=int, metavar="N", help="Recuperar nodo N")
    p.add_argument("--monitor", action="store_true",  help="Monitor continuo")
    args = p.parse_args()

    if   args.demo:    demo_completa()
    elif args.status:  mostrar_tabla()
    elif args.kill:
        matar_nodo(args.kill)
        console.print(f"[red]Nodo {args.kill} caído. Usa --recover {args.kill} para restaurar.[/]")
    elif args.recover:
        recuperar_nodo(args.recover)
        esperar(20, "Reintegrando nodo", intervalo=5)
        mostrar_tabla()
    elif args.monitor:
        th = iniciar_monitor()
        try:
            while True: time.sleep(1)
        except KeyboardInterrupt:
            detener_monitor()
            console.print("\n👋 Monitor detenido")
    else:
        menu()