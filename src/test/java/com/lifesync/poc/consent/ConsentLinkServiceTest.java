package com.lifesync.poc.consent;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class ConsentLinkServiceTest {
    private final ConsentLinkService service=new ConsentLinkService("unit-test-secret-key","http://localhost:8080");
    @Test void verifiesSignedTokenAndRejectsTampering() {
        String token=service.generate("G001").split("token=")[1];
        assertEquals("G001",service.validatedGoalId(token));
        assertNull(service.validatedGoalId(token.substring(0,token.length()-2)+"XX"));
    }
    @Test void expires() {
        String token=service.generate("G001",Instant.now().minusSeconds(5)).split("token=")[1];
        assertNull(service.validatedGoalId(token));
    }
}
