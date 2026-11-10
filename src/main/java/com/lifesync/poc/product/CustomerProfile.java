package com.lifesync.poc.product;

/** Minimal customer header; no ECN, address, coordinates or goal data. */
public record CustomerProfile(String customerId, String firstName, String lastName, String city,
                              String state, String plccAccountStatus) {
    public String displayName() {
        return (firstName + " " + lastName).strip();
    }
    public boolean plccHolder() { return "ACTIVE".equals(plccAccountStatus); }
}
