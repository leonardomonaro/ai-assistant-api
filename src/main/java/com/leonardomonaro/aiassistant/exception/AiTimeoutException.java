package com.leonardomonaro.aiassistant.exception;

public class AiTimeoutException extends AiServiceException {
    public AiTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
