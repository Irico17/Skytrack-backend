# Skytrack Scheduling Core

Sistema backend de planificacion de rutas para transporte aereo de equipajes, integrado con un frontend React/Vite ubicado como proyecto hermano en `../Skytrack-Frontend`.

Este README describe el estado actual del sistema para humanos y agentes que necesiten continuar el trabajo sin redescubrir la arquitectura.

## Estado Actual

- Backend: Java 17, Spring Boot 3.3, Gradle, REST, WebSocket, JPA/Flyway.
- Frontend: React 18, Vite, Tailwind, `VITE_API_BASE` con valor por defecto `/api`.
- Perfil local por defecto: `dev`, con H2 en memoria y sin Flyway.
- Perfil VM/contenedor: `container`, con MySQL local y Flyway.
- El backend sigue usando archivos `.txt` como fuente operativa para aeropuertos, plan de vuelos y envios preliminares.
- MySQL guarda persistencia de simulaciones `DAY_TO_DAY` y tablas de referencia importadas desde los archivos.
- La simulacion de 5 dias exporta resultados finales a JSON en `data/results`.
- La ocupacion de aeropuerto se calcula desde eventos de almacenamiento; al llegar al destino final las maletas permanecen en el inventario del aeropuerto.

## Estructura Relevante

```text
scheduling-core/
  src/main/java/com/equipo2b/scheduler/
    api/                 REST controllers y WebSocket
    api/dto/             DTOs de REST/WebSocket
    service/             Orquestacion de simulaciones y carga de datos
    execution/           SimulationController, Scheduler, Replanner
    algorithm/           GeneticAlgorithm, TabuSearch
    logic/               RouteGenerator, SolutionEvaluator
    model/               Airport, Flight, ShipmentBatch, AssignedRoute, Solution
    monitoring/          CapacityMonitor, StorageInventoryService, semaforos
    persistence/         Entidades, repositorios, importacion y persistencia MySQL/H2
    upload/              Parsers de archivos .txt
  src/main/resources/
    application.properties
    application-dev.properties
    application-container.properties
    db/migration/mysql/
  data/
    c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt
    planes_vuelo.txt
    _envios_preliminar_/
    results/
  deploy/vm/
```

## Datos Estaticos

Los datos usados por las simulaciones salen de estas propiedades:

```properties
DATA_AIRPORTS_PATH=data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt
DATA_FLIGHTS_PATH=data/planes_vuelo.txt
DATA_SHIPMENTS_DIR=data/_envios_preliminar_
DATA_RESULTS_DIR=data/results
```

Formatos esperados:

- Aeropuertos: archivo de catedra con secciones de continente y lineas de aeropuerto.
- Planes de vuelo: `ORIGEN-DESTINO-HH:mm-HH:mm-CAPACIDAD`.
- Envios preliminares: archivos `_envios_XXXX_.txt` con lineas `ID-YYYYMMDD-HH-MM-DESTINO-CANTIDAD-CLIENTE`.

El endpoint `POST /api/data/static` reemplaza el dataset activo. Recibe `multipart/form-data` con:

- `airports`: archivo de aeropuertos.
- `flights`: archivo de planes de vuelo.
- `shipments`: multiples archivos `_envios_*.txt`.

El backend valida todo en una carpeta temporal antes de tocar los archivos activos. Si la validacion pasa, reemplaza aeropuertos, vuelos y borra/recrea `_envios_preliminar_`. Luego importa aeropuertos y vuelos a la BD configurada. Los nuevos datos se usan al iniciar la siguiente simulacion.

## Modos de Simulacion

### Operacion Dia a Dia

- Frontend mode: `realtime`.
- Backend scenario: `DAY_TO_DAY`.
- Permite registrar maletas con `POST /api/simulations/{id}/shipments`.
- Permite cancelar vuelos con `POST /api/simulations/{id}/flights/{flightId}/cancel`.
- Al terminar, persiste simulacion, lotes y rutas en BD.

### Simulacion 5 Dias

- Frontend mode: `5day`.
- Backend scenario: `PERIOD_SIMULATION`.
- Carga todos los envios preliminares filtrados por ventana `[startDateTime, startDateTime + 5 dias)`.
- La fecha/hora de inicio se selecciona desde el frontend y se envia como `startDateTime`.
- Emite `CYCLE_UPDATE` y `STORAGE_UPDATE` por WebSocket.
- Exporta resultados finales a JSON con `SimulationResultExporter`.

### Colapso

- Backend scenario disponible: `COLLAPSE_SIMULATION`.
- El frontend actual conserva una visualizacion local de estres/colapso.
- El boton de carga de datos estaticos esta disponible antes de simular para mantener el dataset backend preparado.

## SLA Operativo

El modelo usa estos limites (plazo de entrega del enunciado) para decidir si una maleta fue entregada a tiempo:

- Mismo continente: 24 horas (1 día) desde el ingreso del lote.
- Continentes diferentes: 48 horas (2 días) desde el ingreso del lote.

No confundir con los tiempos tipicos de traslado de vuelo (medio dia / un dia = 12h/24h en `FlightType`).

Estos valores alimentan `ShipmentBatch.calculateSLA()`, `AssignedRoute.meetsSLA()` y el conteo de rutas con/sin SLA en WebSocket.

## Optimizacion Para Volumen Alto

La VM objetivo es pequena, usualmente 2 CPU y entre 2 GB y 4 GB de RAM. Por eso el backend evita ejecutar una metaheuristica completa cuando un ciclo trae miles de lotes.

Cambios vigentes:

- `RouteGenerator` cachea variantes de rutas factibles por origen, destino, minuto de ingreso y SLA. Esto evita repetir BFS para miles de lotes equivalentes.
- La cache de rutas se limita a 20,000 entradas para no crecer sin control en corridas grandes.
- `GeneticAlgorithm` mantiene GA normal en volumen pequeno/medio, pero usa modo masivo desde 2,500 lotes por ciclo.
- En modo masivo, el GA arma una solucion heuristica ordenada por hora de ingreso, reutiliza la cache de rutas y hace una evaluacion final. Esto prioriza terminar dentro del ciclo real sobre explorar muchas generaciones.
- Para volumen medio, GA reduce poblacion, generaciones y limite de estancamiento de forma adaptativa.
- `TabuSearch` reduce iteraciones y vecindario cuando hay muchas rutas. Desde 3,000 rutas omite refinamiento Tabu para evitar miles de copias profundas de soluciones.
- La suite legacy de tests fue eliminada porque no estaba alineada con el modelo actual ni con los datos reales del caso.

Limites de VM configurados en `deploy/vm`:

- `JAVA_OPTS`: `-Xms192m -Xmx1024m -XX:ActiveProcessorCount=2 -XX:+UseG1GC -XX:MaxGCPauseMillis=250 -XX:+UseStringDeduplication -Djava.util.concurrent.ForkJoinPool.common.parallelism=1`.
- `skytrack-backend.service`: `MemoryMax=1300M`, `CPUQuota=160%`, `Nice=5`.
- `deploy-artifacts.sh` actualiza el unit file y `JAVA_OPTS` durante redeploy normal, sin requerir reinstalar dependencias.

Trade-off importante: en volumen masivo se reduce busqueda global para proteger tiempo de respuesta y RAM. Si se necesita calidad maxima offline, conviene correr experimentos separados con mas poblacion/generaciones fuera de la VM compartida.

## API Principal

```http
GET  /api/data/airports
GET  /api/data/flights?startDateTime=YYYY-MM-DDTHH:mm&days=5
GET  /api/data/flights/stats
POST /api/data/import
POST /api/data/static

POST /api/simulations/start
POST /api/simulations/{id}/stop
POST /api/simulations/{id}/pause
POST /api/simulations/{id}/resume
GET  /api/simulations/{id}/status
GET  /api/simulations/{id}/solution
GET  /api/simulations/{id}/metrics
GET  /api/simulations/{id}/results
POST /api/simulations/{id}/shipments
POST /api/simulations/{id}/flights/{flightId}/cancel

WS   /ws/simulation
```

## Ejecutar Local

Backend:

```powershell
cd C:\Users\Irico\Documents\DP1\scheduling-core
.\gradlew.bat bootRun
```

Frontend:

```powershell
cd C:\Users\Irico\Documents\DP1\Skytrack-Frontend
npm run dev
```

Abrir `http://localhost:5173`. Vite proxya `/api` y `/ws` al backend en `localhost:8080`.

## Build y Verificacion

Backend:

```powershell
.\gradlew.bat bootJar -x test
```

No hay suite activa de tests en este snapshot; los tests legacy fueron eliminados.

Frontend:

```powershell
cd C:\Users\Irico\Documents\DP1\Skytrack-Frontend
npm run build
```

## Deploy VM

La guia operativa esta en `deploy/vm/README_VM_DEPLOY.md`.

Resumen:

```powershell
cd C:\Users\Irico\Documents\DP1\scheduling-core
.\deploy\vm\package-local.ps1
scp deploy\skytrack-vm-deploy.tar.gz 1inf54.981.2b@200.16.7.142:~/skytrack-vm-deploy.tar.gz
```

Redeploy automatico desde Windows:

```powershell
.\deploy\vm\redeploy-vm.ps1
```

Para sobrescribir tambien los datos estaticos de `/opt/skytrack/backend/data` con los incluidos en el paquete:

```powershell
.\deploy\vm\redeploy-vm.ps1 -OverwriteData
```

En la VM:

```bash
mkdir -p ~/skytrack-deploy
rm -rf ~/skytrack-deploy/current
mkdir -p ~/skytrack-deploy/current
tar -xzf ~/skytrack-vm-deploy.tar.gz -C ~/skytrack-deploy/current
cd ~/skytrack-deploy/current/deploy/vm
chmod +x *.sh
sudo ./install-dependencies.sh
sudo ./deploy-artifacts.sh
```

Si MySQL root requiere password:

```bash
MYSQL_ROOT_PASSWORD='password-root-mysql' sudo -E ./install-dependencies.sh
```

## Notas Para Agentes

- No asumir que MySQL es necesario para desarrollo local; el perfil `dev` usa H2.
- No cambiar rutas de datos sin revisar `deploy/vm/*.sh`, Nginx y `application.properties`.
- `deploy-artifacts.sh` preserva datasets ya subidos en `/opt/skytrack/backend/data`; usar `SKYTRACK_OVERWRITE_DATA=true` para restaurar los defaults del paquete.
- El frontend de colapso no esta completamente conectado al backend de colapso; evitar afirmar que todo ese modo usa datos backend.
- Para cambios visuales, revisar `../Skytrack-Frontend/src/app/hooks/useSimulation.ts`, `App.tsx` y los componentes de paneles.