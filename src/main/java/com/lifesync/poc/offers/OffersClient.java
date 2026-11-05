package com.lifesync.poc.offers;

import com.lifesync.poc.domain.Models.Offer;
import com.lifesync.poc.domain.Models.OfferResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.*;

/** Always retrieves live offers; no offer fields are persisted to Neo4j. */
@Service
public class OffersClient {
    private static final Logger log=LoggerFactory.getLogger(OffersClient.class);
    private final RestClient rest;
    private final boolean enabled;
    private final String apiKey;
    public OffersClient(@Value("${lifesync.offers.base-url:http://localhost:8080/mock-offers}") String baseUrl,
                        @Value("${lifesync.offers.enabled:true}") boolean enabled,
                        @Value("${lifesync.offers.api-key:}") String apiKey) {
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1200);
        factory.setReadTimeout(1800);
        this.rest=RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.enabled=enabled;
        this.apiKey=apiKey;
    }

    public List<Offer> currentOffers(String customerId, List<String> merchantIds) {
        if(!enabled || customerId==null || customerId.isBlank() || merchantIds.isEmpty()) return List.of();
        try {
            String ids=String.join(",", merchantIds);
            RestClient.RequestHeadersSpec<?> request=rest.get().uri(builder -> builder.path("/api/offers")
                    .queryParam("customerId",customerId).queryParam("merchantIds",ids).build());
            if(!apiKey.isBlank()) request.header("X-Demo-Offers-Key",apiKey);
            OfferResponse response=request.retrieve().body(OfferResponse.class);
            return eligible(response==null ? List.of():response.offers(),customerId,merchantIds,LocalDate.now());
        } catch(Exception ex) {
            log.warn("Offers REST unavailable (not a reason to invent offers): {}",ex.getClass().getSimpleName());
            return List.of();
        }
    }

    /** Independently enforce eligibility even if upstream service returns unexpected offers. */
    public static List<Offer> eligible(List<Offer> all,String customerId,List<String> merchants,LocalDate today) {
        if(all==null) return List.of();
        Set<String> allowed=Set.copyOf(merchants);
        LinkedHashMap<String,Offer> filtered=new LinkedHashMap<>();
        for(Offer o:all) {
            if(o==null || o.offerId()==null || o.offerId().isBlank()
                    || !"ACTIVE".equals(o.status()) || !allowed.contains(o.merchantId())) continue;
            boolean permitted=switch(o.audience()==null?"":o.audience()) {
                case "MERCHANT" -> true;
                case "CUSTOMER" -> customerId.equals(o.customerId());
                default -> false;
            };
            if(!permitted || !isValidDate(o.validFrom(),o.validUntil(),today)) continue;
            if(o.title()==null || o.title().isBlank() || o.description()==null) continue;
            filtered.putIfAbsent(o.offerId(),o);
            if(filtered.size()>=30) break;
        }
        return List.copyOf(filtered.values());
    }

    private static boolean isValidDate(String from,String until,LocalDate today) {
        try {
            return from!=null && until!=null && !today.isBefore(LocalDate.parse(from))
                    && !today.isAfter(LocalDate.parse(until));
        } catch(Exception e) {return false;}
    }
}
