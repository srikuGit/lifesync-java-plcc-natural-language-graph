package com.lifesync.poc.consent;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class ConsentLinkServiceTest {
    private final ConsentLinkService service=new ConsentLinkService("unit-test-secret-key","http://localhost:8080");
    @Test void verifiesSignedTokenAndRejectsTampering() {
        String token=service.generate("G001").split("token=")[1];
        assertEquals("G001",service.validatedGoalId(token));
        String[] parts=token.split("\\.");
        byte[] signature=java.util.Base64.getUrlDecoder().decode(parts[1]);
        signature[0]^=1; // Change a MAC byte; replacing Base64 suffix characters can preserve decoded bytes.
        String changed=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        assertNull(service.validatedGoalId(parts[0]+"."+changed));
    }
    @Test void expires() {
        String token=service.generate("G001",Instant.now().minusSeconds(5)).split("token=")[1];
        assertNull(service.validatedGoalId(token));
    }
}
