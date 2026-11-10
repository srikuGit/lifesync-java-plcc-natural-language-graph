package com.lifesync.poc.product;

import com.lifesync.poc.domain.Models.Customer;
import com.lifesync.poc.service.DistanceService;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.*;

/** Read-only customer projections. Merchant disclosure is restricted to ACTIVE PLCC holders. */
@Service
public class CustomerSubgraphService {
    public static final Set<String> PRODUCTS = Set.of("PLCC","HOME_PURCHASE","HOME_REFINANCE","AUTO_LOAN");
    private final ProductGraphRepository graph;
    private final ProductOfferCatalog offers;
    private final DistanceService distances;
    public CustomerSubgraphService(ProductGraphRepository graph,ProductOfferCatalog offers,DistanceService distances) {
        this.graph=graph;this.offers=offers;this.distances=distances;
    }
    public CustomerProfile profile(String customerId) {
        validateCustomerId(customerId);
        return graph.profile(customerId).orElseThrow(()->new NoSuchElementException("No customer found."));
    }
    /** Goal listing deliberately performs no merchant queries or offer-feed reads. */
    public CustomerSubgraph listGoals(String customerId) {
        Customer customer=customer(customerId);
        List<ProductGraphRepository.GoalFact> goals=goals(customerId,"");
        return assemble(customer,goals,false,null,null,5);
    }
    /** Compatibility projection: non-PLCC goals always remain basic-only. */
    public CustomerSubgraph load(String customerId,String product) {
        String filter=product==null?"":product;
        if(!filter.isEmpty()&&!PRODUCTS.contains(filter)) throw new IllegalArgumentException("Unknown product.");
        Customer customer=customer(customerId);
        return assemble(customer,goals(customerId,filter),true,null,null,5);
    }
    public CustomerSubgraph findPlccMerchants(String customerId,List<String> goalIds,
            Double maxDistanceMiles,Double minDiscountPercent,int limit) {
        validateFilter(maxDistanceMiles,10000,"distance");
        validateFilter(minDiscountPercent,100,"discount");
        if(limit<1||limit>5)throw new IllegalArgumentException("Provider limit must be between 1 and 5.");
        if(goalIds==null || goalIds.size()>200) throw new IllegalArgumentException("Invalid goal selection.");
        Set<String> selected=new LinkedHashSet<>();
        for(String id:goalIds) if(id==null||!id.matches("[A-Za-z0-9_-]{1,80}")||!selected.add(id))
            throw new IllegalArgumentException("Invalid or duplicate goal ID.");
        Customer customer=customer(customerId);
        List<ProductGraphRepository.GoalFact> all=goals(customerId,"");
        Set<String> allowed=new HashSet<>();
        all.stream().filter(g->"PLCC".equals(g.product())).forEach(g->allowed.add(g.id()));
        if(!allowed.containsAll(selected))throw new IllegalArgumentException("Requested goals must be this customer's PLCC goals.");
        List<ProductGraphRepository.GoalFact> requested=all.stream().filter(g->"PLCC".equals(g.product()))
            .filter(g->selected.isEmpty()||selected.contains(g.id())).toList();
        return assemble(customer,requested,true,maxDistanceMiles,minDiscountPercent,limit);
    }
    private Customer customer(String customerId) {
        validateCustomerId(customerId);
        return graph.customer(customerId).orElseThrow(()->new NoSuchElementException("No customer found."));
    }
    private List<ProductGraphRepository.GoalFact> goals(String customer,String product) {
        var goals=graph.goals(customer,product);
        if(goals.size()>200)throw new IllegalArgumentException("Customer graph exceeds the limit of 200 goals.");
        return goals;
    }
    private CustomerSubgraph assemble(Customer customer,List<ProductGraphRepository.GoalFact> goals,
            boolean includeDetails,Double maxDistanceMiles,Double minDiscountPercent,int limit) {
        List<ProductOfferCatalog.Offer> offerSnapshot=includeDetails&&customer.plccHolder()
                &&goals.stream().anyMatch(g->"PLCC".equals(g.product()))?offers.snapshot():List.of();
        List<CustomerSubgraph.Goal> result=new ArrayList<>();
        for(var g:goals) {
            String status="GOAL_ONLY";
            List<CustomerSubgraph.Provider> providers=List.of();
            if(includeDetails&&"PLCC".equals(g.product())) {
                if(!customer.plccHolder()) status="PLCC_ACCOUNT_RESTRICTED";
                else if(customer.latitude()==null||customer.longitude()==null) status="NO_CUSTOMER_LOCATION";
                else {
                    Map<String,CustomerSubgraph.Provider> unique=new LinkedHashMap<>();
                    for(var m:graph.candidates(customer.customerId(),g.id(),"PLCC")) {
                        double miles=distances.miles(customer.latitude(),customer.longitude(),m.latitude(),m.longitude());
                        if(!Double.isFinite(miles)||maxDistanceMiles!=null&&miles>maxDistanceMiles)continue;
                        var eligible=ProductOfferCatalog.eligible(offerSnapshot,"PLCC",m.merchantId(),customer.customerId(),LocalDate.now())
                            .stream().filter(o->minDiscountPercent==null||o.discountPercent()!=null&&o.discountPercent()>=minDiscountPercent).toList();
                        if(minDiscountPercent!=null&&eligible.isEmpty())continue;
                        unique.putIfAbsent(m.storeId(),new CustomerSubgraph.Provider(m.merchantId(),m.storeId(),m.name(),m.city(),m.state(),miles,eligible));
                    }
                    providers=unique.values().stream().sorted(Comparator.comparingDouble(CustomerSubgraph.Provider::distanceMiles)
                        .thenComparing(CustomerSubgraph.Provider::storeId)).limit(limit).toList();
                    status=providers.isEmpty()?"NO_MATCHING_PROVIDERS":"MATCHED";
                }
            }
            result.add(new CustomerSubgraph.Goal(g.id(),g.description(),g.product(),g.productName(),g.categories(),status,providers));
        }
        return new CustomerSubgraph(customer.customerId(),customer.name(),List.copyOf(result));
    }
    private static void validateCustomerId(String customer) {
        if(customer==null||!customer.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Enter a valid customer ID.");
    }
    private static void validateFilter(Double value,double max,String field) {
        if(value!=null&&(!Double.isFinite(value)||value<0||value>max))throw new IllegalArgumentException("Invalid "+field+" filter.");
    }
}
