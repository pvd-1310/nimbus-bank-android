package com.example.final_term.data;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.List;

public final class ReadModelResult<T> {
    public enum Status { LOADING, SUCCESS, EMPTY, CACHED, ERROR }

    private final Status status;
    private final T data;
    private final String message;
    private final boolean readOnly;

    private ReadModelResult(Status status, T data, String message, boolean readOnly) {
        this.status = status;
        this.data = data;
        this.message = message == null ? "" : message;
        this.readOnly = readOnly;
    }

    public static <T> ReadModelResult<T> loading() {
        return new ReadModelResult<>(Status.LOADING, null, "", true);
    }

    @SuppressWarnings("unchecked")
    public static <T> ReadModelResult<T> success(@NonNull T data) {
        if (data instanceof List && ((List<?>) data).isEmpty()) {
            return (ReadModelResult<T>) ReadModelResult.<Object>empty();
        }
        return new ReadModelResult<>(Status.SUCCESS, data, "", false);
    }

    public static <T> ReadModelResult<List<T>> empty() {
        return new ReadModelResult<>(Status.EMPTY, Collections.emptyList(), "", true);
    }

    public static <T> ReadModelResult<T> cached(@NonNull T data, String message) {
        return new ReadModelResult<>(Status.CACHED, data, message, true);
    }

    public static <T> ReadModelResult<T> error(String message) {
        return new ReadModelResult<>(Status.ERROR, null, message, true);
    }

    public Status getStatus() { return status; }
    public T getData() { return data; }
    public String getMessage() { return message; }
    public boolean isReadOnly() { return readOnly; }
}
