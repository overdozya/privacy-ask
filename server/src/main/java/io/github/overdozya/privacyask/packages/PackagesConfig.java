package io.github.overdozya.privacyask.packages;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.overdozya.privacyask.knowledge.Knowledge;

@Configuration(proxyBeanMethods = false)
public class PackagesConfig {

    @Bean
    PackageService packageService(Knowledge knowledge) {
        return new PackageService(knowledge);
    }
}
