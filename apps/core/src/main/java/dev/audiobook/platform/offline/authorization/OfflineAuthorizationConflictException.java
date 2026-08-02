package dev.audiobook.platform.offline.authorization;

import dev.audiobook.platform.offline.authorization.service.*;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public final class OfflineAuthorizationConflictException extends RuntimeException {}
