package com.lifesync.poc.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DistanceServiceTest {
    private final DistanceService distance = new DistanceService();

    @Test void samePointIsZeroMiles() {
        assertEquals(0, distance.miles(47.6062, -122.3321, 47.6062, -122.3321), 0.00001);
    }

    @Test void seattleToBellevueIsAboutSixMilesStraightLine() {
        double actual = distance.miles(47.6062, -122.3321, 47.6101, -122.2015);
        assertTrue(actual > 5 && actual < 8, "Expected straight-line distance around 6 miles");
    }
}
