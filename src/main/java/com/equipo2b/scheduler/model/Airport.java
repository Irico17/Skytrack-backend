package com.equipo2b.scheduler.model;

import java.util.Objects;

public class Airport {
    private final String id;
    private final String city;
    private final String country;
    private final int gmtOffset;
    private final int capacity;
    private final double latitude;
    private final double longitude;

    public Airport(String id, String city, String country, int gmtOffset, int capacity, double latitude, double longitude) {
        this.id = id;
        this.city = city;
        this.country = country;
        this.gmtOffset = gmtOffset;
        this.capacity = capacity;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public String getId() { return id; }
    public String getCity() { return city; }
    public int getGmtOffset() { return gmtOffset; }
    public int getCapacity() { return capacity; }
    public String getCountry() { return country; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true; // Compara referencias de memoria
        if (o == null || this.getClass() != o.getClass()) return false;
        Airport airport = (Airport) o;
        return Objects.equals(this.id, airport.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return String.format("%s (%s, %s)", id, city, country);
    }
}
