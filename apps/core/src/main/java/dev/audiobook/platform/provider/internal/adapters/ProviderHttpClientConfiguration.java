package dev.audiobook.platform.provider.internal.adapters;

import dev.audiobook.platform.provider.internal.adapters.ProviderRuntimeProperties;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProviderHttpClientConfiguration {

    @Bean
    HttpClient providerHttpClient(ProviderRuntimeProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.commandTimeout())
                .build();
    }
}
