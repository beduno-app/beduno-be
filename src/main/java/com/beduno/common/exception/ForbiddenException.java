package com.beduno.common.exception;

public class ForbiddenException extends BusinessException {

    public ForbiddenException(String messageCode) {
        super(messageCode);
    }
}
