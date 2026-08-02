package dev.audiobook.platform.status;

import dev.audiobook.platform.status.service.*;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
@RequiredArgsConstructor
public class PlatformStatusController {

    private final PlatformStatusService platformStatusService;

    @GetMapping("/status")
    public ResponseEntity<PlatformStatus> status() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(platformStatusService.currentStatus());
    }
}
