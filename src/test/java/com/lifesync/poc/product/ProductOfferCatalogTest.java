package com.lifesync.poc.product;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProductOfferCatalogTest {
    private static final LocalDate TODAY=LocalDate.of(2026,11,5);
    private ProductOfferCatalog.Offer offer(String id, String product, String customer, String until,
                                            Double discount, Double apr, Double fees) {
        return new ProductOfferCatalog.Offer(id,product,"M1",customer,id,"Synthetic terms","ACTIVE",
            LocalDate.of(2026,1,1),LocalDate.parse(until),discount,apr,fees,60);
    }
    @Test void filtersProductCustomerMerchantExpiryAndOrdersDiscounts() {
        var offers=List.of(offer("a","PLCC",null,"2030-01-01",10.,null,null),
            offer("b","PLCC","C1","2030-01-01",20.,null,null),
            offer("foreign","PLCC","C2","2030-01-01",99.,null,null),
            offer("expired","PLCC",null,"2025-01-01",99.,null,null),
            offer("loan","AUTO_LOAN",null,"2030-01-01",null,1.,0.));
        assertEquals(List.of("b"),ProductOfferCatalog.eligible(offers,"PLCC","M1","C1",TODAY)
            .stream().map(ProductOfferCatalog.Offer::offerId).toList());
        assertTrue(ProductOfferCatalog.eligible(offers,"PLCC","OTHER","C1",TODAY).isEmpty());
    }
    @Test void ordersLoanAprThenFeesAndRejectsInvalidNumbers() {
        var offers=List.of(offer("high","AUTO_LOAN",null,"2030-01-01",null,7.,100.),
            offer("costly","AUTO_LOAN",null,"2030-01-01",null,6.,500.),
            offer("best","AUTO_LOAN",null,"2030-01-01",null,6.,100.),
            offer("nan","AUTO_LOAN",null,"2030-01-01",null,Double.NaN,100.));
        assertEquals(List.of("best","costly","high"),ProductOfferCatalog.eligible(offers,"AUTO_LOAN","M1","C1",TODAY)
            .stream().map(ProductOfferCatalog.Offer::offerId).toList());
    }
}
