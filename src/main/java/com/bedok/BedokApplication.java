package com.bedok;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BedokApplication {

    public static void main(String[] args) {
        SpringApplication.run(BedokApplication.class, args);
    }
}
