package dev.audiobook.platform;

import dev.audiobook.platform.entitlement.EntitlementPolicyProperties;
import dev.audiobook.platform.admission.AdmissionProperties;
import dev.audiobook.platform.admission.InspectionProperties;
import dev.audiobook.platform.status.PlatformBuildProperties;
import dev.audiobook.platform.worker.WorkerProperties;
import dev.audiobook.platform.identity.IdentitySecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({
        AdmissionProperties.class,
        InspectionProperties.class,
        EntitlementPolicyProperties.class,
        IdentitySecurityProperties.class,
        PlatformBuildProperties.class,
        WorkerProperties.class
})
@EnableScheduling
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
