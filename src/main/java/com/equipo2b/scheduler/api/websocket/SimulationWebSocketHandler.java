package com.equipo2b.scheduler.api.websocket;

import com.equipo2b.scheduler.api.dto.DTOMapper;
import com.equipo2b.scheduler.api.dto.SimulationStatusDTO;
import com.equipo2b.scheduler.api.dto.SolutionDTO;
import com.equipo2b.scheduler.execution.SimulationController;
import com.equipo2b.scheduler.execution.SimulationStatus;
import com.equipo2b.scheduler.model.Solution;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handler WebSocket que emite actualizaciones en tiempo real al frontend.
 *
 * Implementa SimulationController.SimulationListener para recibir callbacks
 * del loop de simulación y hacer broadcast a todos los clientes conectados.
 *
 * Conexión: ws://host:8080/ws/simulation
 *
 * Mensajes emitidos:
 *   - Tipo "CYCLE_UPDATE" al finalizar cada ciclo de planificación
 *   - Tipo "SIMULATION_FINISHED" al terminar la simulación
 */
public class SimulationWebSocketHandler extends TextWebSocketHandler
        implements SimulationController.SimulationListener {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper;

    // Referencia al simulationId activo (para incluir en mensajes)
    private volatile String activeSimId = "N/A";

    public SimulationWebSocketHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Frontend conecta al WebSocket */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        System.out.println("🔌 WebSocket conectado: " + session.getId()
            + " (total: " + sessions.size() + ")");

        // Enviar mensaje de bienvenida
        session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of(
            "type", "CONNECTED",
            "message", "Conectado al stream de simulación",
            "simulationId", activeSimId
        ))));
    }

    /** Frontend desconecta */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        System.out.println("🔌 WebSocket desconectado: " + session.getId()
            + " (total: " + sessions.size() + ")");
    }

    /** Llamado por SimulationController al finalizar cada ciclo */
    @Override
    public void onCycleCompleted(SimulationStatus status, Solution solution) {
        try {
            // Serializar estado + resumen de solución (sin las rutas completas — son muy grandes)
            Map<String, Object> msg = Map.of(
                "type", "CYCLE_UPDATE",
                "simulationId", activeSimId,
                "cycle", status.currentCycle(),
                "simulatedTime", status.simulatedTime() != null
                    ? status.simulatedTime().toString() : null,
                "fitness", status.currentFitness(),
                "batchesProcessed", status.batchesProcessed(),
                "batchesFailed", status.batchesFailed(),
                "collapseLevel", status.collapseLevel().name(),
                "totalRoutes", solution.getRoutes().size(),
                "totalBags", solution.getTotalBags()
            );
            broadcast(mapper.writeValueAsString(msg));
        } catch (Exception e) {
            System.err.println("⚠️ Error serializando ciclo WebSocket: " + e.getMessage());
        }
    }

    /** Llamado por SimulationController cuando termina la simulación */
    @Override
    public void onSimulationFinished(SimulationStatus status) {
        try {
            Map<String, Object> msg = Map.of(
                "type", "SIMULATION_FINISHED",
                "simulationId", activeSimId,
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

    /** Actualiza el simulationId activo (llamado desde SimulationService al iniciar) */
    public void setActiveSimId(String simId) {
        this.activeSimId = simId;
    }

    /** Envía el mensaje JSON a todas las sesiones conectadas */
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

    /** Retorna número de clientes conectados */
    public int getConnectedClients() {
        return sessions.size();
    }
}
