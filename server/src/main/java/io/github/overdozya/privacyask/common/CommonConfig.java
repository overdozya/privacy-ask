package io.github.overdozya.privacyask.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CommonConfig {

    @Bean
    AppClock appClock() {
        return AppClock.system();
    }

    @Bean
    CodeVersion codeVersion(@Value("${app.code-version:unknown}") String value) {
        return new CodeVersion(value);
    }
}
