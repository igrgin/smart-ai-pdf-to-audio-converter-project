package dev.audiobook.platform.provider;

import dev.audiobook.platform.generation.AudioGenerationProperties;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProviderHttpClientConfiguration {

    @Bean
    HttpClient providerHttpClient(AudioGenerationProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.commandTimeout())
                .build();
    }
}
