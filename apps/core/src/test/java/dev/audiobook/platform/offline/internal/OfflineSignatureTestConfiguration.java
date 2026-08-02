package dev.audiobook.platform.offline.internal;

import dev.audiobook.platform.offline.internal.OfflineAccessProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile({"test", "itest"})
class OfflineSignatureTestConfiguration {

    @Bean
    OfflineSignatureBoundary offlineSignatureBoundary(OfflineAccessProperties properties) {
        return new LocalOfflineSignatureBoundary(
                TestOfflineSigningKeys.properties(properties.authorizationValidity(), properties.chunkBytes()));
    }
}
