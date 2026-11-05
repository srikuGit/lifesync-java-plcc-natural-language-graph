package com.lifesync.poc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class LifeSyncApplication {
    public static void main(String[] args) {
        SpringApplication.run(LifeSyncApplication.class, args);
    }
}
