package com.lifesync.poc.consent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/** Short-lived signed, non-PII consent link. No opt-in is recorded until explicit POST. */
@Service
public class ConsentLinkService {
    private final byte[] key;
    private final String baseUrl;
    public ConsentLinkService(@Value("${lifesync.consent.secret:}") String configured,
                              @Value("${lifesync.public-base-url:http://localhost:8080}") String baseUrl) {
        if(configured==null || configured.isBlank()) {
            byte[] generated=new byte[32];
            new SecureRandom().nextBytes(generated);
            this.key=generated;
        } else {
            this.key=sha256(configured);
        }
        this.baseUrl=baseUrl.replaceAll("/+$","");
    }
    private static byte[] sha256(String text) {
        try {return java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));}
        catch(Exception e) {throw new IllegalStateException(e);}
    }
    public String generate(String goalId) {return generate(goalId,Instant.now().plusSeconds(24*3600));}
    String generate(String goalId,Instant expiry) {
        if(!goalId.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("Bad goal ID");
        String body=Base64.getUrlEncoder().withoutPadding()
                .encodeToString((goalId+":"+expiry.getEpochSecond()).getBytes(StandardCharsets.UTF_8));
        String sig=Base64.getUrlEncoder().withoutPadding().encodeToString(mac(body));
        return baseUrl+"/consent?token="+body+"."+sig;
    }
    public String validatedGoalId(String token) {
        try {
            if(token==null || token.length()>512) return null;
            String[] parts=token.split("\\.");
            if(parts.length!=2) return null;
            byte[] provided=Base64.getUrlDecoder().decode(parts[1]);
            if(!MessageDigest.isEqual(mac(parts[0]),provided)) return null;
            String payload=new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8);
            String[] fields=payload.split(":");
            if(fields.length!=2 || !fields[0].matches("[A-Za-z0-9_-]{1,80}")) return null;
            if(Instant.now().isAfter(Instant.ofEpochSecond(Long.parseLong(fields[1])))) return null;
            return fields[0];
        } catch(Exception ignored) {return null;}
    }
    private byte[] mac(String text) {
        try {
            Mac m=Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key,"HmacSHA256"));
            return m.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch(Exception e) {throw new IllegalStateException(e);}
    }
}
