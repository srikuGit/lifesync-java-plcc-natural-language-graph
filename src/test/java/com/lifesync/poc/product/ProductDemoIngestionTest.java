package com.lifesync.poc.product;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ProductDemoIngestionTest {
    @Test void rejectsSharedGoalIdsBeforeDatabaseWrites() {
        var customers=List.<Map<String,Object>>of(Map.of("customerId","C1"),Map.of("customerId","C2"));
        var goals=List.<Map<String,Object>>of(Map.of("goalId","G1","customerId","C1"),Map.of("goalId","G1","customerId","C2"));
        assertThrows(IllegalArgumentException.class,()->ProductDemoIngestion.validateOwnership(new ProductDemoIngestion.Fixture(List.of(),customers,goals,List.of())));
    }
}
