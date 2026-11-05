package com.lifesync.poc.offers;

import com.lifesync.poc.domain.Models.Offer;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OffersClientTest {
    private Offer offer(String id,String audience,String customer,String merchant,String state,String until) {
        return new Offer(id,audience,customer,merchant,"Demo offer","Only demo terms",state,
                "2026-01-01",until);
    }
    @Test void onlyActiveCurrentCustomerOrMerchantOffersAreAllowed() {
        LocalDate today=LocalDate.parse("2026-10-29");
        List<Offer> all=List.of(
                offer("A","MERCHANT",null,"M001","ACTIVE","2027-01-01"),
                offer("B","CUSTOMER","C001","M001","ACTIVE","2027-01-01"),
                offer("C","CUSTOMER","C002","M001","ACTIVE","2027-01-01"),
                offer("D","MERCHANT",null,"M002","ACTIVE","2027-01-01"),
                offer("E","MERCHANT",null,"M001","INACTIVE","2027-01-01"),
                offer("F","MERCHANT",null,"M001","ACTIVE","2025-12-01"));
        var eligible=OffersClient.eligible(all,"C001",List.of("M001"),today);
        assertEquals(List.of("A","B"),eligible.stream().map(Offer::offerId).toList());
    }
}
