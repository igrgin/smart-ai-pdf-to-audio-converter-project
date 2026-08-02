package dev.audiobook.platform.provider.adapters.google.service;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;

import dev.audiobook.platform.provider.adapters.google.*;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class GoogleProviderAccessTokenServiceImpl implements GoogleProviderAccessTokenService {

    @Override
    public String accessToken() {
        try {
            GoogleCredentials credentials =
                    GoogleCredentials.getApplicationDefault()
                            .createScoped(
                                    List.of("https://www.googleapis.com/auth/cloud-platform"));
            credentials.refreshIfExpired();
            AccessToken accessToken = credentials.getAccessToken();
            if (accessToken == null
                    || accessToken.getTokenValue() == null
                    || accessToken.getTokenValue().isBlank()) {
                throw new IllegalStateException("Google provider access is unavailable");
            }
            return accessToken.getTokenValue();
        } catch (IOException exception) {
            throw new IllegalStateException("Google provider access is unavailable", exception);
        }
    }
}
