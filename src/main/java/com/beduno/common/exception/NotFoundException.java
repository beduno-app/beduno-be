package com.beduno.common.exception;

public class NotFoundException extends BusinessException {

    public NotFoundException(String messageCode) {
        super(messageCode);
    }
}
