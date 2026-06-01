package com.equipo2b.scheduler.api.websocket;

import com.equipo2b.scheduler.api.dto.CycleUpdateDTO;
import com.equipo2b.scheduler.api.dto.SimulationStatusDTO;
import com.equipo2b.scheduler.api.dto.SolutionDTO;
import com.equipo2b.scheduler.api.dto.StorageUpdateDTO;
import com.equipo2b.scheduler.execution.SimulationController;
import com.equipo2b.scheduler.execution.SimulationStatus;
import com.equipo2b.scheduler.model.Solution;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper;

    private volatile String activeSimId = "N/A";
    private volatile String lastCycleUpdateJson;
    private volatile String lastStorageUpdateJson;

    public SimulationWebSocketHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        System.out.println("🔌 WebSocket conectado: " + session.getId()
            + " (total: " + sessions.size() + ")");

        session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of(
            "type", "CONNECTED",
            "message", "Conectado al stream de simulación",
            "simulationId", activeSimId
        ))));
        sendSnapshot(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
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
            lastCycleUpdateJson = json;
            broadcast(json);
        } catch (Exception e) {
            System.err.println("⚠️ Error serializando CYCLE_UPDATE: " + e.getMessage());
        }
    }

    public void onStorageUpdated(StorageUpdateDTO update) {
        try {
            String json = mapper.writeValueAsString(update);
            lastStorageUpdateJson = json;
            broadcast(json);
        } catch (Exception e) {
            System.err.println("⚠️ Error serializando STORAGE_UPDATE: " + e.getMessage());
        }
    }

    /**
     * Implementación de la interface SimulationListener (sin DTO pre-construido).
     * Esta versión es el fallback — SimulationService usa la sobrecarga con DTO.
     */
    @Override
    public void onCycleCompleted(SimulationStatus status, Solution solution) {
        try {
            Map<String, Object> msg = Map.of(
                "type", "CYCLE_UPDATE",
                "simulationId", activeSimId,
                "cycle", status.currentCycle(),
                "simulatedTime", status.simulatedTime() != null ? status.simulatedTime().toString() : null,
                "fitness", status.currentFitness(),
                "batchesProcessed", status.batchesProcessed(),
                "totalRoutes", solution.getRoutes().size(),
                "totalBags", solution.getTotalBags()
            );
            broadcast(mapper.writeValueAsString(msg));
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
            broadcast(mapper.writeValueAsString(msg));
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
            broadcast(mapper.writeValueAsString(msg));
        } catch (Exception e) {
            System.err.println("⚠️ Error serializando error WebSocket: " + e.getMessage());
        }
    }

    @Override
    public void onStorageUpdated(SimulationStatus status, Solution solution) {
        // SimulationService construye el DTO completo; este fallback se mantiene vacio.
    }

    public void setActiveSimId(String simId) {
        if (!Objects.equals(this.activeSimId, simId)) {
            lastCycleUpdateJson = null;
            lastStorageUpdateJson = null;
        }
        this.activeSimId = simId;
    }

    private void sendSnapshot(WebSocketSession session) {
        sendIfOpen(session, lastCycleUpdateJson);
        sendIfOpen(session, lastStorageUpdateJson);
    }

    private void sendIfOpen(WebSocketSession session, String json) {
        if (json == null || !session.isOpen()) return;
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            System.err.println("⚠️ Error enviando snapshot WebSocket a " + session.getId()
                + ": " + e.getMessage());
        }
    }

    private void broadcast(String json) {
        TextMessage msg = new TextMessage(json);
        sessions.removeIf(session -> !session.isOpen());
        sessions.forEach(session -> {
            try {
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(msg);
                    }
                }
            } catch (Exception e) {
                System.err.println("⚠️ Error enviando WebSocket a " + session.getId()
                    + ": " + e.getMessage());
            }
        });
    }

    public int getConnectedClients() {
        return sessions.size();
    }
}
