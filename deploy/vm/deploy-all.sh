#!/usr/bin/env bash
# ============================================================================
# Skytrack — deploy TODO-EN-UNO para la VM.
#
# Detecta solo si es la primera vez (instala dependencias + MySQL) o un
# redeploy (solo artefactos), despliega y verifica la salud. Un comando:
#
#   sudo ./deploy-all.sh                # primera vez o redeploy, lo detecta
#   sudo ./deploy-all.sh --demo         # primera vez SIN MySQL (H2, sin persistencia)
#   SKYTRACK_OVERWRITE_DATA=true sudo -E ./deploy-all.sh   # restaurar dataset del zip
#
# Requiere: los dos ZIP extraídos como carpetas hermanas (scheduling-core/ y
# Skytrack-Frontend/), con JAR y dist/ ya incluidos (los zips oficiales los traen).
# Si faltan los compilados, intenta compilarlos con build-from-source.sh.
# ============================================================================
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Ejecuta con sudo: sudo ./deploy-all.sh"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEPLOY_ROOT="$(cd "$BACKEND_ROOT/.." && pwd)"
DEMO_MODE=false
[ "${1:-}" = "--demo" ] && DEMO_MODE=true

chmod +x "$SCRIPT_DIR"/*.sh 2>/dev/null || true

echo "==> Skytrack deploy-all"
echo "    backend : $BACKEND_ROOT"
echo "    frontend: $DEPLOY_ROOT/Skytrack-Frontend"

# ── 1. Artefactos: si faltan, compilar desde fuente ─────────────────────────
JAR=""
if [ -f "$BACKEND_ROOT/backend/scheduling-core.jar" ]; then
  JAR="$BACKEND_ROOT/backend/scheduling-core.jar"
else
  JAR="$(find "$BACKEND_ROOT/build/libs" -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' 2>/dev/null | head -n 1 || true)"
fi

DIST=""
if [ -f "$BACKEND_ROOT/frontend/dist/index.html" ]; then
  DIST="$BACKEND_ROOT/frontend/dist/index.html"
elif [ -f "$DEPLOY_ROOT/Skytrack-Frontend/dist/index.html" ]; then
  DIST="$DEPLOY_ROOT/Skytrack-Frontend/dist/index.html"
fi

if [ -z "$JAR" ] || [ ! -f "$DIST" ]; then
  echo "==> Faltan compilados (jar o dist). Compilando desde fuente..."
  if ! command -v java >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
    echo "==> Instalando herramientas de build (JDK 17 + Node)..."
    apt-get update -y
    apt-get install -y openjdk-17-jdk nodejs npm
  fi
  # build-from-source.sh no requiere root; se ejecuta como el dueño de la carpeta.
  OWNER="$(stat -c '%U' "$BACKEND_ROOT")"
  sudo -u "$OWNER" bash "$SCRIPT_DIR/build-from-source.sh" --all
fi

# ── 2. Dependencias del sistema: solo la primera vez ────────────────────────
FIRST_TIME=false
if [ ! -f /etc/systemd/system/skytrack-backend.service ] || ! command -v nginx >/dev/null 2>&1; then
  FIRST_TIME=true
fi

if $FIRST_TIME; then
  if $DEMO_MODE; then
    echo "==> Primera vez (modo DEMO sin MySQL): install-demo-no-db.sh"
    bash "$SCRIPT_DIR/install-demo-no-db.sh"
  else
    echo "==> Primera vez: install-dependencies.sh (Java, Nginx, MySQL, systemd)"
    bash "$SCRIPT_DIR/install-dependencies.sh"
  fi
else
  echo "==> VM ya configurada (systemd + nginx presentes): salto instalación"
fi

# ── 3. Desplegar artefactos (JAR, dist, nginx, servicio) ────────────────────
echo "==> Desplegando artefactos..."
bash "$SCRIPT_DIR/deploy-artifacts.sh"

# ── 4. Verificación de salud ────────────────────────────────────────────────
echo "==> Verificando salud (hasta 90 s)..."
ok=false
for i in $(seq 1 18); do
  if curl -fsS http://127.0.0.1:8081/actuator/health 2>/dev/null | grep -q '"UP"'; then
    ok=true; break
  fi
  sleep 5
done

if $ok; then
  echo "✅ Backend UP en 127.0.0.1:8081"
else
  echo "❌ Backend no respondió. Últimas líneas del log:"
  journalctl -u skytrack-backend -n 40 --no-pager || true
  exit 1
fi

if curl -fsSI http://127.0.0.1/ >/dev/null 2>&1; then
  echo "✅ Frontend servido por Nginx en el puerto 80"
else
  echo "⚠️  Nginx no respondió en 80 — revisa: sudo nginx -t && sudo systemctl reload nginx"
fi

echo ""
echo "🎉 Deploy completo. Abrir: http://1inf54-981-2b.inf.pucp.edu.pe/  (o la IP de la VM)"
echo "   Logs backend : sudo journalctl -u skytrack-backend -f"
echo "   Importar BD  : curl -X POST http://127.0.0.1:8081/api/data/import"
