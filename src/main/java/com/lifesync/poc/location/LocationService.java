package com.lifesync.poc.location;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** US Census address lookup. Only provider results are cached; no fabricated coordinate fallback. */
@Service
public class LocationService {
    public record Location(double latitude, double longitude, String matchedAddress, String provider,
                           String geocodedAt, String addressHash) {}
    private final RestClient rest;
    private final ObjectMapper mapper;
    private final Path cache;
    public LocationService(ObjectMapper mapper,
            @Value("${lifesync.geocoding.url:https://geocoding.geo.census.gov/geocoder/locations/onelineaddress}") String url,
            @Value("${lifesync.geocoding.cache-dir:./data/cache/geocoding}") String cacheDir) {
        this.mapper=mapper; cache=Path.of(cacheDir);
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000); factory.setReadTimeout(10000);
        rest=RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    public synchronized Optional<Location> geocode(String address) {
        if (address==null || address.isBlank()) return Optional.empty();
        try {
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(address.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
            Path file=cache.resolve(hash+".json");
            if (Files.isRegularFile(file)) {
                Location saved=mapper.readValue(file.toFile(),Location.class);
                if (valid(saved) && hash.equals(saved.addressHash())) return Optional.of(saved);
            }
            var json=rest.get().uri(b -> b.queryParam("address",address).queryParam("benchmark","Public_AR_Current")
                .queryParam("format","json").build()).retrieve().body(com.fasterxml.jackson.databind.JsonNode.class);
            var matches=json==null?null:json.path("result").path("addressMatches");
            if (matches==null || !matches.isArray() || matches.isEmpty()) return Optional.empty();
            var match=matches.get(0); var point=match.path("coordinates");
            if (!point.path("y").isNumber() || !point.path("x").isNumber()) return Optional.empty();
            Location result=new Location(point.get("y").asDouble(),point.get("x").asDouble(),
                match.path("matchedAddress").asText(),"US_CENSUS",Instant.now().toString(),hash);
            if (!valid(result)) return Optional.empty();
            Files.createDirectories(cache);
            Path temp=Files.createTempFile(cache,"geocode-",".tmp");
            mapper.writeValue(temp.toFile(),result);
            Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(result);
        } catch (Exception ex) {
            // Warn without logging addresses or credentials.
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Address geocoding unavailable: {}",ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }
    private static boolean valid(Location p) {
        return Double.isFinite(p.latitude()) && Math.abs(p.latitude())<=90
            && Double.isFinite(p.longitude()) && Math.abs(p.longitude())<=180
            && "US_CENSUS".equals(p.provider()) && p.matchedAddress()!=null && !p.matchedAddress().isBlank();
    }
}
