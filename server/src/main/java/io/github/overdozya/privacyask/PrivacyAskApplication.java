package io.github.overdozya.privacyask;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PrivacyAskApplication {
    public static void main(String[] args) {
        SpringApplication.run(PrivacyAskApplication.class, args);
    }
}
