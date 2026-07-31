package dev.audiobook.platform.identity;

import java.util.List;
import java.util.Locale;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/library")
public class LibraryController {

    @GetMapping
    public ResponseEntity<LibraryView> library(@AuthenticationPrincipal ListenerPrincipal principal) {
        List<String> methods = principal.providers().stream()
                .map(provider -> provider.name().toLowerCase(Locale.ROOT))
                .sorted()
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new LibraryView(principal.displayName(), principal.contactEmail(), methods, List.of()));
    }

    public record LibraryView(
            String displayName,
            String contactEmail,
            List<String> signInMethods,
            List<Object> audiobooks) {
    }
}
