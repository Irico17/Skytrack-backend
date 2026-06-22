package com.equipo2b.scheduler.api.dto;

public record StaticDataBatchStartDTO(
    String sessionId,
    String message,
    int shipmentFilesStaged
) {}
