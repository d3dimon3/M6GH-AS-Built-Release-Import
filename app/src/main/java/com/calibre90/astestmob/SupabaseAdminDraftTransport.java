package com.calibre90.astestmob;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Authenticated draft submission to Supabase; does not publish or sign.
 * Access token must come from Supabase Auth, never from a GitHub PAT.
 * This transport is intentionally not wired to the existing Admin UI yet.
 */
final class SupabaseAdminDraftTransport {
  private static final int MAX_RESPONSE = 32 * 1024;
  private SupabaseAdminDraftTransport() {}

  static String submit(String projectUrl, String publishableKey, String accessToken,
                       JSONObject snapshot, long baseVersion) throws Exception {
    AdminSnapshot.validate(snapshot);
    if (baseVersion < 0) throw new IllegalArgumentException("Invalid base version");
    if (projectUrl == null || !projectUrl.matches("https://[a-z0-9-]+\\.supabase\\.co/?"))
      throw new IllegalArgumentException("Invalid Supabase URL");
    if (publishableKey == null || !publishableKey.startsWith("sb_publishable_"))
      throw new IllegalArgumentException("Expected Supabase publishable key");
    if (accessToken == null || accessToken.trim().isEmpty())
      throw new SecurityException("Supabase Auth sign-in required");
    String base = projectUrl.endsWith("/") ? projectUrl.substring(0, projectUrl.length()-1) : projectUrl;
    JSONObject draft = new JSONObject();
    draft.put("base_version", baseVersion);
    draft.put("snapshot", snapshot);
    // owner_uid and status are deliberately omitted; DB defaults + RLS enforce both.
    byte[] body = draft.toString().getBytes(StandardCharsets.UTF_8);
    if (body.length > 400 * 1024) throw new IOException("Draft too large");
    HttpURLConnection conn = (HttpURLConnection) new URL(base + "/rest/v1/admin_config_drafts?select=id").openConnection();
    try {
      conn.setRequestMethod("POST");
      conn.setConnectTimeout(10000);
      conn.setReadTimeout(15000);
      conn.setInstanceFollowRedirects(false);
      conn.setUseCaches(false);
      conn.setDoOutput(true);
      conn.setRequestProperty("apikey", publishableKey);
      conn.setRequestProperty("Authorization", "Bearer " + accessToken.trim());
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setRequestProperty("Accept", "application/json");
      conn.setRequestProperty("Prefer", "return=representation");
      try (OutputStream out = conn.getOutputStream()) { out.write(body); }
      int status = conn.getResponseCode();
      if (status != 201) throw new IOException("Supabase draft upload HTTP " + status);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (InputStream in = conn.getInputStream()) {
        byte[] buf = new byte[2048]; int n;
        while ((n = in.read(buf)) != -1) {
          if (out.size()+n > MAX_RESPONSE) throw new IOException("Response too large");
          out.write(buf, 0, n);
        }
      }
      JSONArray rows = new JSONArray(new String(out.toByteArray(), StandardCharsets.UTF_8));
      if (rows.length() != 1) throw new IOException("Draft acknowledgement missing");
      return rows.getJSONObject(0).getString("id");
    } finally { conn.disconnect(); }
  }
}
