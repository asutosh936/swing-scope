package com.swingscope;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling   // weekly scan-history purge
public class SwingScopeApplication {

    public static void main(String[] args) {
        SpringApplication.run(SwingScopeApplication.class, args);
    }
}
