package com.equipo2b.scheduler.api.websocket;

import com.equipo2b.scheduler.service.SimulationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Conecta automáticamente el WebSocketHandler con el SimulationService.
 * Se registra como listener al iniciar el contexto Spring, así cualquier
 * simulación que se inicie automáticamente emitirá eventos al WebSocket.
 */
@Component
public class WebSocketListenerRegistrar {

    @Autowired
    private SimulationService simulationService;

    @Autowired
    private SimulationWebSocketHandler webSocketHandler;

    /**
     * Registra el WebSocket handler como listener global de simulaciones.
     * Desde SimulationService se llama a este setter al iniciar cada simulación.
     */
    public void registerListener() {
        simulationService.setSimulationListener(webSocketHandler);
    }
}
