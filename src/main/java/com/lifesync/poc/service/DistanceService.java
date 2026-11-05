package com.lifesync.poc.service;

import org.springframework.stereotype.Service;

@Service
public class DistanceService {
    private static final double EARTH_RADIUS_MILES = 3958.7613;

    public double miles(double lat1, double lon1, double lat2, double lon2) {
        double latitude = Math.toRadians(lat2 - lat1);
        double longitude = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(latitude / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(longitude / 2), 2);
        return 2 * EARTH_RADIUS_MILES * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
    }
}
