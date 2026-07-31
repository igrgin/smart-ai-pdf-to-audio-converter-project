package dev.audiobook.platform.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
final class WebShellController {

    @GetMapping(value = {"/", "/index.html"}, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    ResponseEntity<String> shell(HttpServletRequest request) {
        Object nonceAttribute = request.getAttribute(SecurityHeadersFilter.NONCE_ATTRIBUTE);
        if (!(nonceAttribute instanceof String nonce)) {
            throw new IllegalStateException("CSP nonce was not established");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body("""
                        <!doctype html>
                        <html lang="en">
                          <head>
                            <meta charset="UTF-8" />
                            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                            <meta name="theme-color" content="#f5f4f1" media="(prefers-color-scheme: light)" />
                            <meta name="theme-color" content="#101821" media="(prefers-color-scheme: dark)" />
                            <meta name="description" content="A private studio for turning authorized publications into beautifully narrated audiobooks." />
                            <link rel="icon" href="/brand-mark.svg" type="image/svg+xml" />
                            <link rel="manifest" href="/manifest.webmanifest" />
                            <link rel="stylesheet" href="/assets/app.css" />
                            <title>Folio — private AI audiobooks</title>
                          </head>
                          <body>
                            <div id="root"></div>
                            <script nonce="%s" type="module" src="/assets/app.js"></script>
                          </body>
                        </html>
                        """.formatted(nonce));
    }
}
