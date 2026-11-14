package com.lifesync.poc.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** Synthetic offer feed; offers remain outside Neo4j and are revalidated on every lookup. */
@Component
public class ProductOfferCatalog {
    public record Offer(String offerId, String productId, String merchantId, String customerId,
                        String title, String terms, String status, LocalDate validFrom, LocalDate validUntil,
                        Double discountPercent, Double apr, Double fees, Integer termMonths) {}
    private final Path path;
    private final ObjectMapper mapper;
    public ProductOfferCatalog(ObjectMapper mapper,
            @Value("${lifesync.input-dir:./data/input}") String inputDir) {
        this.mapper = mapper;
        this.path = Path.of(inputDir, "product-offers.json");
    }
    public List<Offer> current(String product, String merchant, String customer) {
        return eligible(snapshot(),product,merchant,customer,LocalDate.now());
    }
    /** One immutable feed snapshot per graph retrieval, rather than one disk read per provider. */
    public List<Offer> snapshot() {
        try {
            List<Offer> all = Arrays.asList(mapper.readValue(path.toFile(), Offer[].class));
            return List.copyOf(all);
        } catch (Exception ex) {
            // An unavailable offer feed must never produce invented terms.
            return List.of();
        }
    }
    public static List<Offer> eligible(List<Offer> all, String product, String merchant,
                                       String customer, LocalDate today) {
        Comparator<Offer> order = "PLCC".equals(product)
                ? Comparator.comparingDouble(o -> -o.discountPercent())
                : Comparator.comparingDouble((Offer o) -> o.apr()).thenComparingDouble(o -> o.fees());
        return all.stream().filter(Objects::nonNull)
                .filter(o -> product.equals(o.productId()) && merchant.equals(o.merchantId()))
                .filter(o -> o.customerId() == null || customer.equals(o.customerId()))
                .filter(o -> "ACTIVE".equals(o.status()) && o.validFrom() != null && o.validUntil() != null)
                .filter(o -> !today.isBefore(o.validFrom()) && !today.isAfter(o.validUntil()))
                .filter(o -> o.offerId() != null && o.title() != null && o.terms() != null)
                .filter(o -> "PLCC".equals(product)
                        ? valid(o.discountPercent(), 100)
                        : valid(o.apr(), 100) && valid(o.fees(), Double.MAX_VALUE)
                            && o.termMonths() != null && o.termMonths() > 0)
                .sorted(order.thenComparing(Offer::offerId)).limit("PLCC".equals(product) ? 1 : 3).toList();
    }
    private static boolean valid(Double n, double max) {
        return n != null && Double.isFinite(n) && n >= 0 && n <= max;
    }
}
