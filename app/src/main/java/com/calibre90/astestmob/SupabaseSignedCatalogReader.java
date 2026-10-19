package com.calibre90.astestmob;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Read-only Supabase transport for owner-signed publications.
 *
 * This class intentionally does not change the current GitHub production path.
 * The caller supplies a public publishable key, never a service-role/secret key.
 * A missing/unsigned publication is rejected rather than silently accepted.
 */
final class SupabaseSignedCatalogReader {
  private static final int MAX_RESPONSE_BYTES = 512 * 1024;
  private SupabaseSignedCatalogReader() {}

  static FullCatalogEnvelope download(String projectUrl, String publishableKey) throws Exception {
    if (projectUrl == null || !projectUrl.matches("https://[a-z0-9-]+\\.supabase\\.co/?"))
      throw new IllegalArgumentException("Invalid Supabase project URL");
    if (publishableKey == null || !publishableKey.startsWith("sb_publishable_"))
      throw new IllegalArgumentException("Expected a Supabase publishable key");
    String base = projectUrl.endsWith("/") ? projectUrl.substring(0, projectUrl.length() - 1) : projectUrl;
    URL url = new URL(base + "/rest/v1/app_config?id=eq.main&select=config,version&limit=1");
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    try {
      connection.setRequestMethod("GET");
      connection.setConnectTimeout(10000);
      connection.setReadTimeout(10000);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestProperty("apikey", publishableKey);
      connection.setRequestProperty("Accept", "application/json");
      connection.setUseCaches(false);
      int status = connection.getResponseCode();
      if (status != 200) throw new IOException("Supabase HTTP " + status);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (InputStream in = connection.getInputStream()) {
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) != -1) {
          if (out.size() + n > MAX_RESPONSE_BYTES)
            throw new IOException("Supabase publication response too large");
          out.write(buffer, 0, n);
        }
      }
      JSONArray rows = new JSONArray(new String(out.toByteArray(), StandardCharsets.UTF_8));
      if (rows.length() != 1) throw new IOException("No published configuration");
      JSONObject row = rows.getJSONObject(0);
      JSONObject document = row.getJSONObject("config");
      if (!CatalogSignature.verifyFull(document))
        throw new SecurityException("Supabase publication signature verification failed");
      FullCatalogEnvelope envelope = FullCatalogEnvelope.parse(document);
      if (row.getLong("version") != envelope.version)
        throw new SecurityException("Supabase version mismatch");
      return envelope;
    } finally {
      connection.disconnect();
    }
  }
}
