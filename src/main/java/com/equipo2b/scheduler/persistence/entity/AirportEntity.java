package com.equipo2b.scheduler.persistence.entity;

import jakarta.persistence.*;

/**
 * Entidad JPA para persistir aeropuertos en MySQL.
 */
@Entity
@Table(name = "airports")
public class AirportEntity {

    @Id
    @Column(length = 10)
    private String id;

    @Column(nullable = false)
    private String city;

    @Column
    private String country;

    @Column(name = "timezone", length = 50)
    private String timezone;

    @Column(name = "storage_capacity")
    private int storageCapacity;

    @Column
    private double latitude;

    @Column
    private double longitude;

    @Column(length = 20)
    private String continent;

    // JPA requires no-arg constructor
    protected AirportEntity() {}

    public AirportEntity(String id, String city, String country, String timezone,
                         int storageCapacity, double latitude, double longitude, String continent) {
        this.id = id;
        this.city = city;
        this.country = country;
        this.timezone = timezone;
        this.storageCapacity = storageCapacity;
        this.latitude = latitude;
        this.longitude = longitude;
        this.continent = continent;
    }

    /** Crea entity desde el objeto de dominio Airport */
    public static AirportEntity from(com.equipo2b.scheduler.model.Airport airport) {
        return new AirportEntity(
            airport.id(),
            airport.city(),
            airport.country(),
            airport.zoneId().getId(),
            airport.storageCapacity(),
            airport.latitude(),
            airport.longitude(),
            airport.continent().name()
        );
    }

    // Getters
    public String getId() { return id; }
    public String getCity() { return city; }
    public String getCountry() { return country; }
    public String getTimezone() { return timezone; }
    public int getStorageCapacity() { return storageCapacity; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public String getContinent() { return continent; }
}
