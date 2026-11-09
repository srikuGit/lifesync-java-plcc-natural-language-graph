package com.lifesync.poc.location;

import com.fasterxml.jackson.databind.*;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.util.*;

/** Runs after both ingestion paths, replacing fixture coordinates with address-derived results. */
@Component
@Order(2)
public class LocationIngestion implements ApplicationRunner {
    private final Driver driver;
    private final LocationService locations;
    private final ObjectMapper mapper;
    private final Path input;
    private final Map<String,Optional<LocationService.Location>> results=new HashMap<>();
    public LocationIngestion(Driver driver, LocationService locations, ObjectMapper mapper,
            @Value("${lifesync.input-dir:./data/input}") String inputDir) {
        this.driver=driver; this.locations=locations; this.mapper=mapper; input=Path.of(inputDir);
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        var customers=new LinkedHashMap<String,String>();
        var stores=new LinkedHashMap<String,String>();
        var merchantEcns=new LinkedHashMap<String,String>();
        for (var entry:mapper.readTree(input.resolve("customers.json").toFile())) {
            var c=entry.path("data").path("attributes");
            for (var usage:c.path("addressUsageList")) if (usage.path("currentIndicator").asBoolean()
                    && "CUSTOMER_RESIDENCE".equals(usage.path("addressUsageCode").asText())) {
                var a=usage.path("address");
                customers.put(c.path("customerId").asText(),String.join(", ",a.path("address_line_1").asText(),
                    a.path("city").asText(),a.path("stateCode").asText()+" "+a.path("postalCode").asText())); break;
            }
        }
        for (var m:mapper.readTree(input.resolve("merchants.json").toFile())) {
            stores.put(m.path("merchantId").asText()+"-S1",String.join(", ",m.path("businessAddressLine1").asText(),
                m.path("businessCity").asText(),m.path("businessState").asText()+" "+m.path("businessZipCode").asText()));
            merchantEcns.put(m.path("merchantId").asText(),m.path("ecn").asText());
        }
        var fixtures=mapper.readTree(input.resolve("products-demo.json").toFile());
        for (var c:fixtures.path("customers")) customers.put(c.path("customerId").asText(),c.path("address").asText());
        for (var m:fixtures.path("merchants")) {
            stores.put(m.path("storeId").asText(),m.path("address").asText());
            merchantEcns.put(m.path("merchantId").asText(),m.path("ecn").asText());
        }
        update("Customer","customerId",customers);
        update("Store","storeId",stores);
        try (var s=driver.session()) {
            merchantEcns.forEach((id,ecn)->s.run("MATCH (m:Merchant {merchantId:$id}) SET m.ecn=$ecn",Map.of("id",id,"ecn",ecn)).consume());
            s.run("MATCH (c:Customer)-[:HAS_GOAL]->(g:Goal) SET g.ecn=c.ecn").consume();
        }
    }
    private void update(String label,String key,Map<String,String> addresses) {
        // Labels and property keys are internal constants, never user input.
        try (var session=driver.session()) {
            for (var entry:addresses.entrySet()) {
                if (!session.run("MATCH (n:"+label+" {"+key+":$id}) RETURN n",Map.of("id",entry.getKey())).hasNext()) continue;
                var found=results.computeIfAbsent(entry.getValue(),locations::geocode);
                Map<String,Object> p=new HashMap<>(); p.put("id",entry.getKey()); p.put("address",entry.getValue());
                p.put("status",found.isPresent()?"RESOLVED":"UNRESOLVED");
                found.ifPresent(l->{p.put("lat",l.latitude());p.put("lon",l.longitude());p.put("provider",l.provider());
                    p.put("matched",l.matchedAddress());p.put("time",l.geocodedAt());p.put("hash",l.addressHash());});
                for (String k:List.of("lat","lon","provider","matched","time","hash")) p.putIfAbsent(k,null);
                session.run("MATCH (n:"+label+" {"+key+":$id}) SET n.address=$address,n.latitude=$lat,n.longitude=$lon,"
                    +"n.geocodeStatus=$status,n.geocodeProvider=$provider,n.matchedAddress=$matched,n.geocodedAt=$time,n.addressHash=$hash",p).consume();
            }
        }
    }
}
