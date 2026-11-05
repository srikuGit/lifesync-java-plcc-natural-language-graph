package com.lifesync.poc.ingestion;

import com.lifesync.poc.graph.GraphRepository;
import org.springframework.stereotype.Service;

/** Integration seam for future account-opened/account-closed events from the account system. */
@Service
public class PlccAccountIntakeService {
    private final GraphRepository graph;
    public PlccAccountIntakeService(GraphRepository graph) {this.graph=graph;}
    /** The caller must authenticate and validate authoritative source events before invoking. */
    public void updateVerifiedAccountStatus(String customerId,String newStatus) {
        graph.updatePlccAccountStatus(customerId,newStatus);
    }
}
