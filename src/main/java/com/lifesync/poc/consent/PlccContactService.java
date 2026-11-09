package com.lifesync.poc.consent;

import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;
import java.util.Map;

/** Records an explicit opt-in for follow-up; does not send messages or change account ownership. */
@Service
public class PlccContactService {
    public static final String CONSENT_TEXT="I agree that a representative may contact me to explain PLCC account details. This is a request for information, not an account application.";
    private final Driver driver;
    public PlccContactService(Driver driver){this.driver=driver;}
    public boolean hasConsent(String customerId) {
        try(var session=driver.session()) {
            return session.executeRead(tx->tx.run("""
                MATCH (:Customer {customerId:$customer})-[:REQUESTED_CONTACT]->(r:PlccContactRequest)
                WHERE r.productId='PLCC' AND r.consentGranted=true
                    AND coalesce(r.status,'') <> 'WITHDRAWN'
                RETURN r.requestId LIMIT 1
                """,Map.of("customer",customerId)).hasNext());
        }
    }
    public boolean request(String customerId) {
        try(var session=driver.session()) {
            return session.executeWrite(tx->tx.run("""
                MATCH (c:Customer {customerId:$customer})
                WHERE coalesce(c.plccAccountStatus,'UNKNOWN') <> 'ACTIVE'
                MERGE (r:PlccContactRequest {requestId:$request})
                ON CREATE SET r.createdAt=datetime()
                SET r.status='REQUESTED',r.productId='PLCC',r.consentGranted=true,
                    r.consentText=$text,r.consentVersion='1',r.consentedAt=datetime(),r.source='CUSTOMER_UI'
                MERGE (c)-[:REQUESTED_CONTACT]->(r)
                RETURN r.requestId AS id
                """,Map.of("customer",customerId,"request","PLCC-CONTACT-"+customerId,"text",CONSENT_TEXT)).hasNext());
        }
    }
}
