package dev.audiobook.platform;

import dev.audiobook.platform.status.PlatformBuildProperties;
import dev.audiobook.platform.worker.WorkerProperties;
import dev.audiobook.platform.identity.IdentitySecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({IdentitySecurityProperties.class, PlatformBuildProperties.class, WorkerProperties.class})
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
