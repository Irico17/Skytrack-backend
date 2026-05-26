CREATE TABLE IF NOT EXISTS simulations (
    id VARCHAR(36) NOT NULL,
    scenario VARCHAR(30),
    status VARCHAR(20),
    started_at DATETIME(6),
    finished_at DATETIME(6),
    current_cycle INT,
    final_fitness DOUBLE,
    sla_compliance DOUBLE,
    collapse_level VARCHAR(20),
    algorithm_type VARCHAR(20),
    total_batches INT,
    routed_batches INT,
    unroutable_batches INT,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS airports (
    id VARCHAR(10) NOT NULL,
    city VARCHAR(255) NOT NULL,
    country VARCHAR(255),
    timezone VARCHAR(50),
    storage_capacity INT,
    latitude DOUBLE,
    longitude DOUBLE,
    continent VARCHAR(20),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS flights (
    flight_id VARCHAR(50) NOT NULL,
    origin_id VARCHAR(10),
    destination_id VARCHAR(10),
    departure_time DATETIME(6),
    arrival_time DATETIME(6),
    capacity INT,
    flight_type VARCHAR(20),
    PRIMARY KEY (flight_id),
    INDEX idx_flights_origin (origin_id),
    INDEX idx_flights_destination (destination_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS shipment_batches (
    id BIGINT NOT NULL AUTO_INCREMENT,
    simulation_id VARCHAR(36) NOT NULL,
    batch_id VARCHAR(50) NOT NULL,
    client_id VARCHAR(20),
    origin_id VARCHAR(10) NOT NULL,
    destination_id VARCHAR(10) NOT NULL,
    bag_count INT NOT NULL,
    ingress_time DATETIME(6) NOT NULL,
    deadline DATETIME(6) NOT NULL,
    routed BIT(1) NOT NULL,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_shipment_batches_sim_batch UNIQUE (simulation_id, batch_id),
    INDEX idx_shipment_batches_simulation (simulation_id),
    INDEX idx_shipment_batches_origin (simulation_id, origin_id),
    CONSTRAINT fk_shipment_batches_simulation
        FOREIGN KEY (simulation_id) REFERENCES simulations(id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS assigned_routes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    simulation_id VARCHAR(36) NOT NULL,
    batch_id BIGINT NOT NULL,
    arrival_time DATETIME(6) NOT NULL,
    total_duration_minutes INT,
    stop_count INT,
    meets_sla BIT(1) NOT NULL,
    slack_hours DOUBLE,
    total_cost DOUBLE,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    INDEX idx_assigned_routes_simulation (simulation_id),
    INDEX idx_assigned_routes_batch (batch_id),
    INDEX idx_assigned_routes_sla (simulation_id, meets_sla),
    CONSTRAINT fk_assigned_routes_simulation
        FOREIGN KEY (simulation_id) REFERENCES simulations(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_assigned_routes_batch
        FOREIGN KEY (batch_id) REFERENCES shipment_batches(id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS route_flight_segments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    route_id BIGINT NOT NULL,
    flight_id VARCHAR(50) NOT NULL,
    segment_order INT NOT NULL,
    departure_time DATETIME(6) NOT NULL,
    arrival_time DATETIME(6) NOT NULL,
    layover_minutes INT,
    PRIMARY KEY (id),
    INDEX idx_route_flight_segments_route (route_id),
    INDEX idx_route_flight_segments_flight (flight_id),
    CONSTRAINT fk_route_flight_segments_route
        FOREIGN KEY (route_id) REFERENCES assigned_routes(id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS flight_cancellations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    simulation_id VARCHAR(36),
    flight_id VARCHAR(50),
    cancelled_day DATE,
    cancelled_at DATETIME(6),
    affected_batches INT,
    replanned_batches INT,
    PRIMARY KEY (id),
    INDEX idx_flight_cancellations_simulation (simulation_id),
    INDEX idx_flight_cancellations_flight (flight_id),
    CONSTRAINT fk_flight_cancellations_simulation
        FOREIGN KEY (simulation_id) REFERENCES simulations(id)
        ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
