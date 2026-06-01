package com.equipo2b.scheduler.model;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Cola de pedidos pendientes de planificación.
 * Soporta consumo por ventana temporal para simulación acelerada.
 * 
 * <p>Utiliza TreeMap para consultas temporales eficientes O(log n).
 * Los pedidos se organizan por ingressTime para consumo ordenado.
 * 
 * <p><b>Validación de Requisitos:</b>
 * <ul>
 *   <li>Requisito 22.2: Recopila pedidos pendientes de planificación</li>
 *   <li>Requisito 23.2: Consume pedidos en ventana temporal Sc</li>
 *   <li>Requisito 30.4: Acumula pedidos transaccionales hasta siguiente ciclo</li>
 * </ul>
 * 
 * @see ShipmentBatch
 */
public class ShipmentQueue {
    private final TreeMap<ZonedDateTime, List<ShipmentBatch>> timeIndex;
    
    /**
     * Construye una cola vacía de pedidos.
     */
    public ShipmentQueue() {
        this.timeIndex = new TreeMap<>();
    }
    
    /**
     * Agrega un pedido transaccional a la cola.
     * El pedido se indexa por su ingressTime para consumo temporal eficiente.
     * 
     * @param batch Lote de maletas a agregar
     * @throws NullPointerException si batch es null
     */
    public synchronized void addShipment(ShipmentBatch batch) {
        if (batch == null) {
            throw new NullPointerException("ShipmentBatch cannot be null");
        }
        
        timeIndex.computeIfAbsent(batch.ingressTime(), k -> new ArrayList<>()).add(batch);
    }
    
    /**
    * Consume pedidos acumulados hasta el fin de la ventana temporal.
    * Retorna todos los pedidos con ingressTime anterior a end.
     * Los pedidos consumidos se eliminan de la cola.
     * 
     * <p>Este método es idempotente para la misma ventana temporal:
     * llamadas repetidas con la misma ventana no retornarán pedidos ya consumidos.
     * 
     * <p><b>Complejidad:</b> O(k log n) donde k es el número de timestamps en la ventana
     * y n es el número total de timestamps en la cola.
     * 
     * @param start Inicio de la ventana temporal (inclusivo)
     * @param end Fin de la ventana temporal (exclusivo)
     * @return Lista de pedidos consumidos, ordenados por ingressTime
     * @throws NullPointerException si start o end son null
     * @throws IllegalArgumentException si start >= end
     */
    public synchronized List<ShipmentBatch> consumeShipments(ZonedDateTime start, ZonedDateTime end) {
        if (start == null || end == null) {
            throw new NullPointerException("Start and end times cannot be null");
        }
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("Start time must be before end time");
        }
        
        List<ShipmentBatch> consumed = new ArrayList<>();
        
        // Consumir también pedidos rezagados por pequeña deriva de reloj/red.
        var windowMap = timeIndex.headMap(end, false);
        
        // Recolectar todos los batches en la ventana
        for (List<ShipmentBatch> batches : windowMap.values()) {
            consumed.addAll(batches);
        }
        
        // Eliminar timestamps consumidos de la cola
        windowMap.clear();
        
        return consumed;
    }
    
    /**
     * Retorna el número de pedidos pendientes en la cola.
     * 
     * @return Cantidad total de pedidos no consumidos
     */
    public synchronized int getPendingCount() {
        return timeIndex.values().stream()
                .mapToInt(List::size)
                .sum();
    }
}
