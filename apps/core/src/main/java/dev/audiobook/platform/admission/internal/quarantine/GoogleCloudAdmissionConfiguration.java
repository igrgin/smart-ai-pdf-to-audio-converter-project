package dev.audiobook.platform.admission.internal.quarantine;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("prod")
public class GoogleCloudAdmissionConfiguration {

    @Bean
    Storage quarantineStorage() {
        return StorageOptions.getDefaultInstance().getService();
    }
}
