package com.lifesync.poc.product;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifesync.poc.classification.SemanticCategoryClassifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import java.nio.file.Path;
import static org.mockito.Mockito.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ProductDemoIngestionTest {
    @Test void rejectsSharedGoalIdsBeforeDatabaseWrites() {
        var customers=List.<Map<String,Object>>of(Map.of("customerId","C1"),Map.of("customerId","C2"));
        var goals=List.<Map<String,Object>>of(Map.of("goalId","G1","customerId","C1"),Map.of("goalId","G1","customerId","C2"));
        assertThrows(IllegalArgumentException.class,()->ProductDemoIngestion.validateOwnership(new ProductDemoIngestion.Fixture(List.of(),customers,goals,List.of())));
    }
    @Test void productGoalUsesEmbeddingsAndLeavesWeakMatchesUnmapped(@TempDir Path directory) {
        var models=org.mockito.Mockito.<ObjectProvider<EmbeddingModel>>mock();
        var model=mock(EmbeddingModel.class);
        when(models.getIfAvailable()).thenReturn(model);
        when(model.embed(anyString())).thenAnswer(call -> {
            String input=call.getArgument(0);
            float[] vector=new float[768];
            if(input.contains("ELECTRONICS") || input.contains("new laptop")) vector[0]=1;
            else if(input.contains("HOME FURNISHINGS")) vector[1]=1;
            else vector[2]=1;
            return vector;
        });
        var mapper=new ObjectMapper();
        var classifier=new SemanticCategoryClassifier(models,mapper,directory.toString(),"test-model",0.57,0.09);
        var ingestion=new ProductDemoIngestion(null,mapper,null,classifier,directory.toString());
        var products=List.<Map<String,Object>>of(Map.of("productId","PLCC",
            "categories",List.of("HOME_FURNISHINGS","ELECTRONICS")));
        var goals=List.<Map<String,Object>>of(
            Map.of("goalId","G1","productId","PLCC","customerId","C1","description","Get a new laptop",
                "category","HOME_FURNISHINGS"),
            Map.of("goalId","G2","productId","PLCC","customerId","C1","description","Take a trip"));
        var mapped=ingestion.classifyGoals(new ProductDemoIngestion.Fixture(products,List.of(),goals,List.of()));
        assertEquals(List.of("ELECTRONICS"),mapped.get(0).get("categories"));
        assertEquals("SEMANTIC_EMBEDDING",mapped.get(0).get("source"));
        assertEquals(List.of(),mapped.get(1).get("categories"));
        assertEquals("SEMANTIC_NO_PRODUCT_MATCH",mapped.get(1).get("source"));
        verify(model).embed("search_query: Customer goal: Get a new laptop");
    }
}
