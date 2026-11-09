package com.lifesync.poc.location;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LocationServiceTest {
    @TempDir Path cache;
    HttpServer server; AtomicInteger calls=new AtomicInteger(); String response;
    LocationService service;
    @BeforeEach void setup() throws Exception {
        response="{\"result\":{\"addressMatches\":[{\"matchedAddress\":\"1000 4TH AVE, SEATTLE, WA\",\"coordinates\":{\"x\":-122.33,\"y\":47.60}}]}}";
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/search",e->{calls.incrementAndGet();byte[] b=response.getBytes();e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});server.start();
        service=new LocationService(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort()+"/search",cache.toString());
    }
    @AfterEach void close(){server.stop(0);}
    @Test void convertsProviderCoordinatesAndCachesByAddressAcrossInstances() {
        var result=service.geocode("1000 4th Ave, Seattle, WA").orElseThrow();
        assertEquals(47.60,result.latitude());assertEquals(-122.33,result.longitude());
        assertEquals("US_CENSUS",result.provider());assertFalse(result.geocodedAt().isBlank());
        assertTrue(service.geocode("1000 4TH AVE, SEATTLE, WA").isPresent());
        var restored=new LocationService(new ObjectMapper(),"http://127.0.0.1:1/search",cache.toString());
        assertTrue(restored.geocode("1000 4th Ave, Seattle, WA").isPresent());assertEquals(1,calls.get());
        service.geocode("Another address");assertEquals(2,calls.get());
    }
    @Test void unmatchedInvalidAndUnavailableAddressesNeverInventCoordinates() {
        response="{\"result\":{\"addressMatches\":[]}}";
        assertTrue(service.geocode("unmatched").isEmpty());
        response="{\"result\":{\"addressMatches\":[{\"matchedAddress\":\"invalid\",\"coordinates\":{\"x\":400,\"y\":200}}]}}";
        assertTrue(service.geocode("invalid").isEmpty());
        response="invalid json";assertTrue(service.geocode("unavailable").isEmpty());
        assertTrue(service.geocode("").isEmpty());
    }
}
