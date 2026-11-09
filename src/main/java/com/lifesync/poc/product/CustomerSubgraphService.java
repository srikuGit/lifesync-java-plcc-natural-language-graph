package com.lifesync.poc.product;

import com.lifesync.poc.service.DistanceService;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class CustomerSubgraphService {
    public static final Set<String> PRODUCTS = Set.of("PLCC","HOME_PURCHASE","HOME_REFINANCE","AUTO_LOAN");
    private final ProductGraphRepository graph;
    private final ProductOfferCatalog offers;
    private final DistanceService distances;
    public CustomerSubgraphService(ProductGraphRepository graph, ProductOfferCatalog offers, DistanceService distances) {
        this.graph=graph; this.offers=offers; this.distances=distances;
    }
    public CustomerSubgraph load(String customerId, String product) {
        if (customerId == null || !customerId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Enter a valid customer ID.");
        String filter = product == null ? "" : product;
        if (!filter.isEmpty() && !PRODUCTS.contains(filter))
            throw new IllegalArgumentException("Unknown product.");
        var customer = graph.customer(customerId).orElseThrow(
                () -> new NoSuchElementException("No demo customer found."));
        var goals = graph.goals(customerId,filter);
        if (goals.size() > 200) throw new IllegalArgumentException("Customer graph exceeds the demo limit of 200 goals.");
        var offerSnapshot=offers.snapshot();
        List<CustomerSubgraph.Goal> result = new ArrayList<>();
        for (var g : goals) {
            String status = "MATCHED";
            List<CustomerSubgraph.Provider> providers = new ArrayList<>();
            if ("PLCC".equals(g.product()) && !customer.plccHolder()) {
                status = "PLCC_ACCOUNT_RESTRICTED";
            } else if (customer.latitude() == null || customer.longitude() == null) {
                status = "NO_CUSTOMER_LOCATION";
            } else {
                Map<String,CustomerSubgraph.Provider> unique = new LinkedHashMap<>();
                for (var m : graph.candidates(customerId,g.id(),g.product())) {
                    double miles = distances.miles(customer.latitude(),customer.longitude(),m.latitude(),m.longitude());
                    if (!Double.isFinite(miles)) continue;
                    unique.putIfAbsent(m.storeId(),new CustomerSubgraph.Provider(m.merchantId(),m.storeId(),
                        m.name(),m.city(),m.state(),miles,ProductOfferCatalog.eligible(offerSnapshot,g.product(),m.merchantId(),customerId,java.time.LocalDate.now())));
                }
                providers = unique.values().stream().sorted(Comparator
                    .comparingDouble(CustomerSubgraph.Provider::distanceMiles)
                    .thenComparing(CustomerSubgraph.Provider::storeId)).limit(5).toList();
                if (providers.isEmpty()) status = "NO_MATCHING_PROVIDERS";
            }
            result.add(new CustomerSubgraph.Goal(g.id(),g.description(),g.product(),g.productName(),
                g.categories(),status,List.copyOf(providers)));
        }
        return new CustomerSubgraph(customerId,customer.name(),List.copyOf(result));
    }
}
