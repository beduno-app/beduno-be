package com.beduno.common.exception;

/**
 * Thrown when a caller cannot be authenticated: bad credentials, or a refresh
 * token that is invalid, expired, or no longer resolves to a user.
 *
 * <p>Distinct from {@link ForbiddenException}, which means the caller is known
 * but lacks the required role.
 */
public class UnauthorizedException extends BusinessException {

    public UnauthorizedException(String messageCode) {
        super(messageCode);
    }
}
