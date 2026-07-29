package com.swingscope;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SwingScopeApplication {

    public static void main(String[] args) {
        SpringApplication.run(SwingScopeApplication.class, args);
    }
}
