package dev.audiobook.platform.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class PlatformTimeConfiguration {

    @Bean
    Clock identityClock() {
        return Clock.systemUTC();
    }

    @Bean
    SecureRandom platformSecureRandom() {
        return new SecureRandom();
    }
}
