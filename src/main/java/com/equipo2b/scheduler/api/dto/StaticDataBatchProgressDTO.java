package com.equipo2b.scheduler.api.dto;

public record StaticDataBatchProgressDTO(
    String sessionId,
    int filesInBatch,
    int shipmentFilesStaged,
    String message
) {}
