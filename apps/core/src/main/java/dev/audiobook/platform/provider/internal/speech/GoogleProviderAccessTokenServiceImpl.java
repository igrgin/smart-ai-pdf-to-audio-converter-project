package dev.audiobook.platform.provider.internal.speech;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class GoogleProviderAccessTokenServiceImpl implements GoogleProviderAccessTokenService {

    @Override
    public String accessToken() {
        try {
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
            credentials.refreshIfExpired();
            AccessToken accessToken = credentials.getAccessToken();
            if (accessToken == null || accessToken.getTokenValue() == null || accessToken.getTokenValue().isBlank()) {
                throw new IllegalStateException("Google provider access is unavailable");
            }
            return accessToken.getTokenValue();
        } catch (IOException exception) {
            throw new IllegalStateException("Google provider access is unavailable", exception);
        }
    }
}
