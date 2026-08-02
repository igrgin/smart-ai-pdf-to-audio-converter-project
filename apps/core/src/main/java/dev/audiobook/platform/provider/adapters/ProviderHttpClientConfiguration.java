package dev.audiobook.platform.provider.adapters;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

@Configuration
public class ProviderHttpClientConfiguration {

    @Bean
    HttpClient providerHttpClient(ProviderRuntimeProperties properties) {
        return HttpClient.newBuilder().connectTimeout(properties.commandTimeout()).build();
    }
}
