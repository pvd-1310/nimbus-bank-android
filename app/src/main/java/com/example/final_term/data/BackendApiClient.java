package com.example.final_term.data;

import androidx.annotation.NonNull;
import com.example.final_term.BuildConfig;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Authenticated production REST transport. Money is never mutated locally. */
public final class BackendApiClient {
    public static final class Response {
        public final int statusCode;
        public final String body;
        Response(int statusCode, String body) { this.statusCode = statusCode; this.body = body; }
    }
    private final FirebaseAuth auth;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public BackendApiClient(FirebaseAuth auth) { this.auth = auth; }

    public Task<Response> get(@NonNull String path) {
        if (auth == null || auth.getCurrentUser() == null)
            return Tasks.forException(new IllegalStateException("BACKEND_AUTH_REQUIRED"));
        return auth.getCurrentUser().getIdToken(false).continueWithTask(tokenTask -> {
            if (!tokenTask.isSuccessful() || tokenTask.getResult() == null)
                throw tokenTask.getException() == null
                        ? new IllegalStateException("BACKEND_TOKEN_UNAVAILABLE") : tokenTask.getException();
            return Tasks.call(executor, () -> executeGet(path, tokenTask.getResult().getToken()));
        });
    }

    public Task<Response> post(@NonNull String path, @NonNull String jsonBody) {
        if (auth == null || auth.getCurrentUser() == null)
            return Tasks.forException(new IllegalStateException("BACKEND_AUTH_REQUIRED"));
        return auth.getCurrentUser().getIdToken(false).continueWithTask(tokenTask -> {
            if (!tokenTask.isSuccessful() || tokenTask.getResult() == null)
                throw tokenTask.getException() == null
                        ? new IllegalStateException("BACKEND_TOKEN_UNAVAILABLE") : tokenTask.getException();
            return Tasks.call(executor, () -> executePost(path, tokenTask.getResult().getToken(), jsonBody));
        });
    }

    public Task<Response> patch(@NonNull String path, @NonNull String jsonBody) {
        if (auth == null || auth.getCurrentUser() == null)
            return Tasks.forException(new IllegalStateException("BACKEND_AUTH_REQUIRED"));
        return auth.getCurrentUser().getIdToken(false).continueWithTask(tokenTask -> {
            if (!tokenTask.isSuccessful() || tokenTask.getResult() == null)
                throw tokenTask.getException() == null
                        ? new IllegalStateException("BACKEND_TOKEN_UNAVAILABLE") : tokenTask.getException();
            return Tasks.call(executor, () -> executePatch(path, tokenTask.getResult().getToken(), jsonBody));
        });
    }

    private Response executeGet(String path, String token) throws Exception {
        return execute(path, token, "GET", null);
    }

    private Response executePost(String path, String token, String jsonBody) throws Exception {
        return execute(path, token, "POST", jsonBody);
    }

    private Response executePatch(String path, String token, String jsonBody) throws Exception {
        try {
            return execute(path, token, "PATCH", jsonBody);
        } catch (java.net.ProtocolException e) {
            HttpURLConnection connection = (HttpURLConnection) new URL(BuildConfig.BACKEND_BASE_URL + path).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("X-HTTP-Method-Override", "PATCH");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(jsonBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            StringBuilder body = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
            }
            connection.disconnect();
            return new Response(status, body.toString());
        }
    }

    private Response execute(String path, String token, String method, String jsonBody) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BuildConfig.BACKEND_BASE_URL + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        if (jsonBody != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(jsonBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        StringBuilder body = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
        }
        connection.disconnect();
        return new Response(status, body.toString());
    }

    public Task<Response> uploadToPresignedPost(@NonNull String uploadUrl, @NonNull JSONObject fields,
                                                @NonNull String mime, @NonNull byte[] fileBytes) {
        return Tasks.call(executor, () -> executeMultipartPost(uploadUrl, fields, mime, fileBytes));
    }

    private Response executeMultipartPost(String uploadUrl, JSONObject fields, String mime, byte[] fileBytes) throws Exception {
        String boundary = "----NimbusBoundary" + System.currentTimeMillis();
        String lineEnd = "\r\n";
        String twoHyphens = "--";

        HttpURLConnection connection = (HttpURLConnection) new URL(uploadUrl).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (java.io.OutputStream output = connection.getOutputStream()) {
            Iterator<String> keys = fields.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = fields.optString(key);
                output.write((twoHyphens + boundary + lineEnd).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.write(("Content-Disposition: form-data; name=\"" + key + "\"" + lineEnd + lineEnd).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.write((value + lineEnd).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            output.write((twoHyphens + boundary + lineEnd).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.write(("Content-Disposition: form-data; name=\"file\"; filename=\"evidence.jpg\"" + lineEnd).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.write(("Content-Type: " + mime + lineEnd + lineEnd).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.write(fileBytes);
            output.write(lineEnd.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.write((twoHyphens + boundary + twoHyphens + lineEnd).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.flush();
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        StringBuilder body = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
        }
        connection.disconnect();
        return new Response(status, body.toString());
    }

    public Task<byte[]> downloadBytes(@NonNull String downloadUrl) {
        return Tasks.call(executor, () -> {
            HttpURLConnection connection = (HttpURLConnection) new URL(downloadUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            int status = connection.getResponseCode();
            if (status >= 400) {
                throw new java.io.IOException("HTTP " + status);
            }
            try (InputStream is = connection.getInputStream();
                 java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, n);
                }
                return baos.toByteArray();
            } finally {
                connection.disconnect();
            }
        });
    }
}
