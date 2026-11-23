package com.example.final_term.data;

import androidx.annotation.NonNull;
import com.example.final_term.BuildConfig;
import com.example.final_term.domain.DomainDtos.OtpVerification;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import org.json.JSONObject;

/** Authenticated production REST transport. Money is never mutated locally. */
public final class BackendApiClient {
    public static final class Response {
        public final int statusCode;
        public final String body;
        Response(int statusCode, String body) { this.statusCode = statusCode; this.body = body; }
    }
    private static final MediaType JSON = MediaType.get("application/json");
    private static final String BASE_URL = BuildConfig.BACKEND_BASE_URL.replaceFirst("/+$", "");
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build();
    private final OkHttpClient cameraHttp = http.newBuilder().callTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).build();
    private final FirebaseAuth auth;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public BackendApiClient(FirebaseAuth auth) { this.auth = auth; }

    public Task<Response> get(@NonNull String path) {
        return request("GET", path, null);
    }

    public Task<Response> post(@NonNull String path, @NonNull String jsonBody) {
        return request("POST", path, jsonBody);
    }

    public Task<Response> patch(@NonNull String path, @NonNull String jsonBody) {
        return request("PATCH", path, jsonBody);
    }

    public Task<Response> verifyIntentOtp(OtpVerification verification) {
        try {
            JSONObject body = new JSONObject().put("challengeId", verification.challengeId)
                    .put("code", verification.code);
            return post("/intents/" + verification.intentId + "/verify-otp", body.toString());
        } catch (org.json.JSONException e) {
            return Tasks.forException(e);
        }
    }

    public Task<Void> signInWithFace(byte[] jpeg) {
        if (auth == null) return Tasks.forException(new IllegalStateException("FACE_SETUP_REQUIRED"));
        return postJpeg("/auth/face-login", jpeg, null).continueWithTask(task -> {
            if (!task.isSuccessful()) throw task.getException();
            Response result = task.getResult();
            JSONObject body = new JSONObject(result.body);
            if (result.statusCode != 200)
                throw new IllegalStateException(body.optString("error", "FACE_LOGIN_FAILED"));
            return auth.signInWithCustomToken(body.getString("customToken")).continueWith(signedIn -> {
                if (!signedIn.isSuccessful()) throw signedIn.getException();
                return null;
            });
        });
    }

    public Task<Response> verifyIntentFace(String intentId, byte[] jpeg) {
        FirebaseUser user = auth == null ? null : auth.getCurrentUser();
        if (user == null) return Tasks.forException(new IllegalStateException("BACKEND_AUTH_REQUIRED"));
        return user.getIdToken(false).continueWithTask(task -> {
            if (!task.isSuccessful()) throw task.getException();
            String token = task.getResult().getToken();
            if (token == null) throw new IllegalStateException("BACKEND_AUTH_REQUIRED");
            return postJpeg("/intents/" + intentId + "/face", jpeg, token);
        });
    }

    private Task<Response> postJpeg(String path, byte[] jpeg, String token) {
        return Tasks.call(executor, () -> {
            Request.Builder request = new Request.Builder().url(BASE_URL + path)
                    .header("Accept", "application/json")
                    .post(RequestBody.create(jpeg, MediaType.get("image/jpeg")));
            if (token != null) request.header("Authorization", "Bearer " + token);
            try (okhttp3.Response response = cameraHttp.newCall(request.build()).execute()) {
                ResponseBody body = response.body();
                return new Response(response.code(), body == null ? "" : body.string());
            }
        });
    }

    private Task<Response> request(String method, String path, String jsonBody) {
        FirebaseUser user = auth == null ? null : auth.getCurrentUser();
        if (user == null) return Tasks.forException(new IllegalStateException("BACKEND_AUTH_REQUIRED"));
        return user.getIdToken(false).continueWithTask(tokenTask -> {
            if (!tokenTask.isSuccessful()) {
                throw tokenTask.getException() == null
                        ? new IllegalStateException("BACKEND_TOKEN_UNAVAILABLE") : tokenTask.getException();
            }
            String token = tokenTask.getResult().getToken();
            if (token == null) throw new IllegalStateException("BACKEND_TOKEN_UNAVAILABLE");
            return Tasks.call(executor, () -> {
                Request request = new Request.Builder()
                        .url(BASE_URL + path)
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .method(method, jsonBody == null ? null : RequestBody.create(jsonBody, JSON))
                        .build();
                OkHttpClient client = path.startsWith("/ekyc/") && path.endsWith("/submit") ? cameraHttp : http;
                try (okhttp3.Response response = client.newCall(request).execute()) {
                    ResponseBody body = response.body();
                    return new Response(response.code(), body == null ? "" : body.string());
                }
            });
        });
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
