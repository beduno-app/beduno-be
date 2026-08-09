package com.beduno.common.exception;

import lombok.Getter;

import java.util.List;

@Getter
public class ConstraintViolationException extends BusinessException {

    private final List<ViolationDetail> details;

    public ConstraintViolationException(String messageCode, List<ViolationDetail> details) {
        super(messageCode);
        this.details = details;
    }

    public record ViolationDetail(
            String type,
            String field,
            String message,
            java.util.Map<String, Object> params
    ) {}
}
