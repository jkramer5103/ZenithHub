package com.zenith.manager.web;

public final class ApiException extends RuntimeException {
    public final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    public static ApiException notFound() {
        return new ApiException(404, "Not found");
    }

    public static ApiException forbidden() {
        return new ApiException(403, "You don't have permission to do that");
    }
}
