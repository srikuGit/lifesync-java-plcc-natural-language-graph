package com.lifesync.poc.product;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class DistanceDisplayTest {
    String label(double distance) {return new CustomerSubgraph.Provider("M","S","Provider","Seattle","WA",distance,List.of()).distanceLabel();}
    @Test void distinguishesSameMappedLocationFromShortRoundedDistances() {
        assertEquals("Same mapped location",label(0));
        assertEquals("<0.1 mi",label(0.02));
        assertEquals("0.1 mi",label(0.1));
        assertEquals("12.3 mi",label(12.34));
    }
}
