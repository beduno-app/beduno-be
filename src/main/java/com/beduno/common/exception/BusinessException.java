package com.beduno.common.exception;

import lombok.Getter;

@Getter
public abstract class BusinessException extends RuntimeException {

    private final String messageCode;

    protected BusinessException(String messageCode) {
        super(messageCode);
        this.messageCode = messageCode;
    }

    protected BusinessException(String messageCode, Throwable cause) {
        super(messageCode, cause);
        this.messageCode = messageCode;
    }
}
