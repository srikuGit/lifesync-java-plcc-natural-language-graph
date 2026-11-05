package com.lifesync.poc.graph;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionCallback;
import org.neo4j.driver.TransactionContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Regression: db.create.setNodeVectorProperty is VOID and must never use YIELD. */
class GraphRepositoryVectorWriteTest {
    private final Driver driver = mock(Driver.class);
    private final Session session = mock(Session.class);
    private final TransactionContext tx = mock(TransactionContext.class);
    private final Result result = mock(Result.class);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private GraphRepository repository() {
        when(driver.session()).thenReturn(session);
        when(tx.run(anyString(), anyMap())).thenReturn(result);
        when(session.executeWrite(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.execute(tx);
        });
        return new GraphRepository(driver);
    }

    @Test void merchantVectorWriteUsesVoidProcedureWithoutYieldAndKeepsFingerprint() {
        repository().saveMerchantEmbedding("M001", "fingerprint", "nomic-embed-text", List.of(0.1, 0.2));
        var query = captureWriteQuery();
        assertTrue(query.contains("CALL db.create.setNodeVectorProperty(m, 'embedding', $vector)"));
        assertFalse(query.contains("YIELD node"));
        assertTrue(query.contains("SET m.vectorFingerprint=$fp, m.vectorModel=$model"));
        assertTrue(query.contains("RETURN m.merchantId AS id"));
    }

    @Test void goalVectorWriteUsesVoidProcedureWithoutYieldAndKeepsFingerprint() {
        repository().saveGoalEmbedding("G001", "fingerprint", "nomic-embed-text", List.of(0.1, 0.2));
        var query = captureWriteQuery();
        assertTrue(query.contains("CALL db.create.setNodeVectorProperty(g, 'embedding', $vector)"));
        assertFalse(query.contains("YIELD node"));
        assertTrue(query.contains("SET g.vectorFingerprint=$fp, g.vectorModel=$model"));
        assertTrue(query.contains("RETURN g.goalId AS id"));
    }

    private String captureWriteQuery() {
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(tx).run(captor.capture(), anyMap());
        return captor.getValue();
    }
}
