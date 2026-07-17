package com.equipo2b.scheduler.api.websocket;

import com.equipo2b.scheduler.api.dto.CycleUpdateDTO;
import com.equipo2b.scheduler.api.dto.ActiveSimulationDTO;
import com.equipo2b.scheduler.api.dto.SimulationStatusDTO;
import com.equipo2b.scheduler.api.dto.SolutionDTO;
import com.equipo2b.scheduler.api.dto.StorageUpdateDTO;
import com.equipo2b.scheduler.execution.SimulationController;
import com.equipo2b.scheduler.execution.SimulationStatus;
import com.equipo2b.scheduler.model.Solution;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handler WebSocket que emite actualizaciones en tiempo real al frontend.
 *
 * Conexión: ws://host:8080/ws/simulation
 *
 * Mensajes emitidos:
 *   - CONNECTED          — al conectar
 *   - CYCLE_UPDATE       — al finalizar cada ciclo (con CycleUpdateDTO)
 *   - SIMULATION_FINISHED — al terminar la simulación
 */
public class SimulationWebSocketHandler extends TextWebSocketHandler
        implements SimulationController.SimulationListener {

    private static final String FOLLOW_ACTIVE = "__ACTIVE__";

    /** Presupuesto de envío por cliente antes de desconectarlo (cliente lento ≠ simulador lento). */
    private static final int SEND_TIME_LIMIT_MS = 2_000;
    // Los activeFlights ya están acotados a la ventana visible; 1 MB protege la VM
    // de 2 GB cuando hay varios clientes lentos conectados.
    private static final int SEND_BUFFER_LIMIT_BYTES = 1024 * 1024;
    private static final DateTimeFormatter ISO_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    // Sesiones DECORADAS (ConcurrentWebSocketSessionDecorator) indexadas por id: el decorador
    // encola y serializa los envíos por sesión, de modo que un cliente lento no bloquea el hilo
    // del simulador ni a los demás clientes (clave con 2 vCPUs).
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, String> lastCycleUpdateBySimId = new ConcurrentHashMap<>();
    private final Map<String, String> lastStorageUpdateBySimId = new ConcurrentHashMap<>();
    // Progreso de warm-up (ciclo 1 en curso). Se cachea porque el cliente suele conectar
    // el WS DESPUÉS de que el hilo de simulación ya emitió el aviso; se borra al llegar
    // el primer CYCLE_UPDATE para que clientes nuevos no vean texto obsoleto.
    private final Map<String, String> lastPreparationBySimId = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    private volatile String activeSimId = "N/A";

    public SimulationWebSocketHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) throws Exception {
        // OVERFLOW_DROP: si el buffer del cliente se llena, se descartan mensajes viejos en vez
        // de cerrar la sesión — para un stream de estado (el siguiente update reemplaza al
        // anterior) es la política correcta.
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(
            rawSession, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES,
            ConcurrentWebSocketSessionDecorator.OverflowStrategy.DROP);
        sessions.put(session.getId(), session);
        String requestedSimId = extractSimulationId(session.getUri());
        String subscription = requestedSimId != null && !requestedSimId.isBlank()
            ? requestedSimId
            : FOLLOW_ACTIVE;
        sessionSubscriptions.put(session.getId(), subscription);
        System.out.println("🔌 WebSocket conectado: " + session.getId()
            + " (sim: " + describeSubscription(subscription) + ", total: " + sessions.size() + ")");

        session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of(
            "type", "CONNECTED",
            "message", "Conectado al stream de simulación",
            "simulationId", resolveSubscription(subscription)
        ))));
        sendSnapshot(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        sessionSubscriptions.remove(session.getId());
        System.out.println("🔌 WebSocket desconectado: " + session.getId()
            + " (total: " + sessions.size() + ")");
    }

    /**
     * Llamado por SimulationService con el DTO ya construido.
     * Esta sobrecarga es la que emite datos tipados al frontend.
     */
    public void onCycleCompleted(SimulationStatus status, Solution solution, CycleUpdateDTO update) {
        try {
            String json = mapper.writeValueAsString(update);
            lastCycleUpdateBySimId.put(update.simulationId(), json);
            lastPreparationBySimId.remove(update.simulationId());
            broadcast(update.simulationId(), json);
        } catch (Exception e) {
            System.err.println("⚠️ Error serializando CYCLE_UPDATE: " + e.getMessage());
        }
    }

    public void onStorageUpdated(StorageUpdateDTO update) {
        try {
            String json = mapper.writeValueAsString(update);
            lastStorageUpdateBySimId.put(update.simulationId(), json);
            broadcast(update.simulationId(), json);
        } catch (Exception e) {
            System.err.println("⚠️ Error serializando STORAGE_UPDATE: " + e.getMessage());
        }
    }

    /**
     * Progreso de warm-up: emitido por el hilo de simulación antes de completar el
     * ciclo 1 (p. ej. "Datos de envíos cargados — calculando el primer plan…").
     */
    public void onPreparationProgress(String simId, String message) {
        if (simId == null || simId.isBlank() || message == null) return;
        try {
            String json = mapper.writeValueAsString(Map.of(
                "type", "PREPARATION_PROGRESS",
                "simulationId", simId,
                "message", message
            ));
            lastPreparationBySimId.put(simId, json);
            broadcast(simId, json);
        } catch (Exception e) {
            System.err.println("⚠️ Error serializando PREPARATION_PROGRESS: " + e.getMessage());
        }
    }

    public void onSimulationStarted(ActiveSimulationDTO activeSimulation) {
        if (activeSimulation == null || activeSimulation.simulationId() == null) return;
        try {
            setActiveSimId(activeSimulation.simulationId());
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "SIMULATION_STARTED");
            msg.put("simulationId", activeSimulation.simulationId());
            msg.put("activeSimulation", activeSimulation);
            broadcast(activeSimulation.simulationId(), mapper.writeValueAsString(msg));
        } catch (Exception e) {
            System.err.println("⚠️ Error serializando SIMULATION_STARTED: " + e.getMessage());
        }
    }

    /**
     * Implementación de la interface SimulationListener (sin DTO pre-construido).
     * Esta versión es el fallback — SimulationService usa la sobrecarga con DTO.
     */
    @Override
    public void onCycleCompleted(SimulationStatus status, Solution solution) {
        try {
            lastPreparationBySimId.remove(activeSimId);
            Map<String, Object> msg = Map.of(
                "type", "CYCLE_UPDATE",
                "simulationId", activeSimId,
                "cycle", status.currentCycle(),
                "simulatedTime", status.simulatedTime() != null
                    ? status.simulatedTime().format(ISO_OFFSET) : null,
                "fitness", status.currentFitness(),
                "batchesProcessed", status.batchesProcessed(),
                "totalRoutes", solution.getRoutes().size(),
                "totalBags", solution.getTotalBags()
            );
            String json = mapper.writeValueAsString(msg);
            lastCycleUpdateBySimId.put(activeSimId, json);
            broadcast(activeSimId, json);
        } catch (Exception e) {
            System.err.println("⚠️ Error serializando ciclo WebSocket: " + e.getMessage());
        }
    }

    @Override
    public void onSimulationFinished(SimulationStatus status) {
        try {
            Map<String, Object> msg = Map.of(
                "type", "SIMULATION_FINISHED",
                "simulationId", activeSimId,
                "simulationComplete", true,
                "finalFitness", status.currentFitness(),
                "totalCycles", status.currentCycle(),
                "batchesProcessed", status.batchesProcessed(),
                "collapseLevel", status.collapseLevel().name()
            );
            broadcast(activeSimId, mapper.writeValueAsString(msg));
        } catch (Exception e) {
            System.err.println("⚠️ Error serializando finish WebSocket: " + e.getMessage());
        }
    }

    @Override
    public void onSimulationError(SimulationStatus status, String errorMessage) {
        try {
            String safeMessage = errorMessage != null && !errorMessage.isBlank()
                ? errorMessage
                : "La simulación terminó por un error interno";
            Map<String, Object> msg = Map.of(
                "type", "SIMULATION_ERROR",
                "simulationId", activeSimId,
                "simulationComplete", false,
                "message", safeMessage,
                "currentCycle", status.currentCycle(),
                "batchesProcessed", status.batchesProcessed(),
                "collapseLevel", status.collapseLevel().name()
            );
            broadcast(activeSimId, mapper.writeValueAsString(msg));
        } catch (Exception e) {
            System.err.println("⚠️ Error serializando error WebSocket: " + e.getMessage());
        }
    }

    @Override
    public void onStorageUpdated(SimulationStatus status, Solution solution) {
        // SimulationService construye el DTO completo; este fallback se mantiene vacio.
    }

    public void setActiveSimId(String simId) {
        this.activeSimId = simId;
        // Poda de caches "último update": sin esto, cada simulación histórica deja un JSON
        // (potencialmente >1 MB con ~15k vuelos) retenido para siempre → fuga lenta en Old Gen.
        if (simId != null && !simId.isBlank()) {
            lastCycleUpdateBySimId.keySet().removeIf(k -> !k.equals(simId));
            lastStorageUpdateBySimId.keySet().removeIf(k -> !k.equals(simId));
            lastPreparationBySimId.keySet().removeIf(k -> !k.equals(simId));
        }
    }

    private void sendSnapshot(WebSocketSession session) {
        String simId = resolveSubscription(sessionSubscriptions.get(session.getId()));
        // Solo existe durante el warm-up (se borra al primer CYCLE_UPDATE): cubre la
        // carrera en que el hilo de simulación lo emitió antes de que el WS conectara.
        sendIfOpen(session, lastPreparationBySimId.get(simId));
        sendIfOpen(session, lastCycleUpdateBySimId.get(simId));
        sendIfOpen(session, lastStorageUpdateBySimId.get(simId));
    }

    private void sendIfOpen(WebSocketSession session, String json) {
        if (json == null || !session.isOpen()) return;
        try {
            // El decorador concurrente serializa/encola internamente: no requiere synchronized.
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            System.err.println("⚠️ Error enviando snapshot WebSocket a " + session.getId()
                + ": " + e.getMessage());
        }
    }

    private void broadcast(String simId, String json) {
        TextMessage msg = new TextMessage(json);
        sessions.values().removeIf(session -> !session.isOpen());
        sessions.values().forEach(session -> {
            String subscription = sessionSubscriptions.getOrDefault(session.getId(), FOLLOW_ACTIVE);
            if (!shouldReceive(subscription, simId)) return;
            try {
                // sendMessage sobre el decorador NO bloquea al hilo del simulador por un cliente
                // lento: encola y, si excede el presupuesto (2s / 1MB), descarta o desconecta.
                session.sendMessage(msg);
            } catch (Exception e) {
                System.err.println("⚠️ Error enviando WebSocket a " + session.getId()
                    + ": " + e.getMessage());
            }
        });
    }

    public int getConnectedClients() {
        return sessions.size();
    }

    public int getConnectedClients(String simId) {
        if (simId == null || simId.isBlank()) {
            return getConnectedClients();
        }
        int count = 0;
        for (WebSocketSession session : sessions.values()) {
            if (!session.isOpen()) continue;
            String subscription = sessionSubscriptions.getOrDefault(session.getId(), FOLLOW_ACTIVE);
            if (shouldReceive(subscription, simId)) {
                count++;
            }
        }
        return count;
    }

    private boolean shouldReceive(String subscription, String simId) {
        if (simId == null) return false;
        return FOLLOW_ACTIVE.equals(subscription) || Objects.equals(subscription, simId);
    }

    private String resolveSubscription(String subscription) {
        return FOLLOW_ACTIVE.equals(subscription) || subscription == null ? activeSimId : subscription;
    }

    private String describeSubscription(String subscription) {
        return FOLLOW_ACTIVE.equals(subscription) ? "active" : subscription;
    }

    private String extractSimulationId(URI uri) {
        if (uri == null || uri.getRawQuery() == null) return null;
        String[] params = uri.getRawQuery().split("&");
        for (String param : params) {
            int idx = param.indexOf('=');
            String key = idx >= 0 ? param.substring(0, idx) : param;
            if (!"simulationId".equals(key)) continue;
            String value = idx >= 0 ? param.substring(idx + 1) : "";
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        return null;
    }
}
