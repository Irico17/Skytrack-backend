package com.equipo2b.scheduler.api.dto;

public record StaticDataUploadDTO(
    String message,
    String airportsFile,
    String flightsFile,
    int shipmentFiles,
    int airportsLoaded,
    int flightsLoaded,
    int shipmentsLoaded,
    int dbAirportsImported,
    int dbFlightsImported
) {}