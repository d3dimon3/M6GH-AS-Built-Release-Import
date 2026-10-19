package com.calibre90.astestmob;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Supabase Auth password sign-in for the administrator only.
 * No password or token is persisted by this class.
 * Caller must keep the returned access token in memory and handle expiration.
 * Not wired to UI yet; production needs a secure session/refresh lifecycle.
 */
final class SupabaseAdminAuth {
  private static final int MAX_RESPONSE_BYTES = 32 * 1024;
  private SupabaseAdminAuth() {}

  static final class Session {
    final String accessToken;
    final String refreshToken;
    final long expiresInSeconds;
    final String userId;
    Session(String accessToken, String refreshToken, long expiresInSeconds, String userId) {
      this.accessToken = accessToken;
      this.refreshToken = refreshToken;
      this.expiresInSeconds = expiresInSeconds;
      this.userId = userId;
    }
  }

  static Session signIn(String projectUrl, String publishableKey, String email,
                        String password) throws Exception {
    if (email == null || email.trim().isEmpty() || password == null || password.isEmpty())
      throw new IllegalArgumentException("Введите email и пароль администратора");
    JSONObject body = new JSONObject();
    body.put("email", email.trim());
    body.put("password", password);
    return request(projectUrl, publishableKey, "/auth/v1/token?grant_type=password", body);
  }

  static Session refresh(String projectUrl, String publishableKey, String refreshToken)
      throws Exception {
    if (refreshToken == null || refreshToken.trim().isEmpty())
      throw new SecurityException("Нет токена обновления сессии");
    JSONObject body = new JSONObject();
    body.put("refresh_token", refreshToken);
    return request(projectUrl, publishableKey, "/auth/v1/token?grant_type=refresh_token", body);
  }

  private static Session request(String projectUrl, String key, String path, JSONObject payload)
      throws Exception {
    if (projectUrl == null || !projectUrl.matches("https://[a-z0-9-]+\\.supabase\\.co/?"))
      throw new IllegalArgumentException("Invalid Supabase URL");
    if (key == null || !key.startsWith("sb_publishable_"))
      throw new IllegalArgumentException("Expected Supabase publishable key");
    String base = projectUrl.endsWith("/") ? projectUrl.substring(0, projectUrl.length()-1) : projectUrl;
    HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
    try {
      conn.setRequestMethod("POST");
      conn.setConnectTimeout(10000);
      conn.setReadTimeout(15000);
      conn.setInstanceFollowRedirects(false);
      conn.setUseCaches(false);
      conn.setDoOutput(true);
      conn.setRequestProperty("apikey", key);
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setRequestProperty("Accept", "application/json");
      try (OutputStream out = conn.getOutputStream()) {
        out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
      }
      int status = conn.getResponseCode();
      if (status != 200) throw new SecurityException("Supabase Auth HTTP " + status);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (InputStream in = conn.getInputStream()) {
        byte[] buf = new byte[2048]; int n;
        while ((n = in.read(buf)) != -1) {
          if (out.size() + n > MAX_RESPONSE_BYTES) throw new IOException("Auth response too large");
          out.write(buf, 0, n);
        }
      }
      JSONObject data = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
      String access = data.getString("access_token");
      String refresh = data.getString("refresh_token");
      String userId = data.getJSONObject("user").getString("id");
      long expires = data.getLong("expires_in");
      if (access.isEmpty() || refresh.isEmpty() || expires <= 0)
        throw new SecurityException("Incomplete authentication response");
      return new Session(access, refresh, expires, userId);
    } finally {
      conn.disconnect();
    }
  }
}
