package com.calibre90.astestmob;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Downloads and caches a signed full configuration without applying it to
 * administrator preferences or to a customer's vehicle ABT data.
 */
final class FullCatalogSync {
  private static final String STORE = "studio_published_full_catalog";
  private static final int MAX_BYTES = 512 * 1024;
  private FullCatalogSync() {}

  /** Must be an owner-controlled HTTPS URL. Empty means disabled. */
  static boolean refresh(Context context, String publishedUrl) {
    if (publishedUrl == null || publishedUrl.isEmpty()) return false;
    HttpURLConnection connection = null;
    try {
      URL url = new URL(publishedUrl);
      if (!"https".equalsIgnoreCase(url.getProtocol())) return false;
      connection = (HttpURLConnection) url.openConnection();
      connection.setConnectTimeout(7000);
      connection.setReadTimeout(7000);
      connection.setInstanceFollowRedirects(false);
      if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (InputStream input = connection.getInputStream()) {
        byte[] chunk = new byte[4096];
        int count;
        while ((count = input.read(chunk)) != -1) {
          if (output.size() + count > MAX_BYTES) return false;
          output.write(chunk, 0, count);
        }
      }
      JSONObject document = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
      if (!CatalogSignature.verifyFull(document)) return false;
      FullCatalogEnvelope envelope = FullCatalogEnvelope.parse(document);
      SharedPreferences prefs = context.getApplicationContext()
          .getSharedPreferences(STORE, Context.MODE_PRIVATE);
      int current = prefs.getInt("version", 0);
      if (envelope.version <= current) return false;
      // One atomic preferences transaction; keep previous valid data on failure.
      return prefs.edit().putInt("version", envelope.version)
          .putString("signed_document", document.toString()).commit();
    } catch (Exception ignored) {
      return false;
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  /** Cache only an envelope already verified by SupabaseSignedCatalogReader. */
  static boolean cacheVerified(Context context, FullCatalogEnvelope envelope) {
    if(envelope==null)return false;
    try{
      SharedPreferences prefs=context.getApplicationContext().getSharedPreferences(STORE,Context.MODE_PRIVATE);
      int current=prefs.getInt("version",0);
      // A stored version number alone is not proof of a valid cached document.
      // Repair a broken/missing same-version cache instead of permanently skipping it.
      JSONObject validCached=cached(context);
      if(envelope.version<current && validCached!=null)return false;
      if(envelope.version==current && validCached!=null)return false;
      // Keep the original exact signed payload: never reserialize before verification.
      JSONObject document=envelope.document;
      if(!CatalogSignature.verifyFull(document))
        throw new SecurityException("Published settings signature rejected by Config");
      if(!prefs.edit().putInt("version",envelope.version)
          .putString("signed_document",document.toString()).commit())
        throw new IllegalStateException("SharedPreferences commit failed");
      if(cached(context)==null)
        throw new IllegalStateException("Signed publication was stored but cannot be verified on readback");
      return true;
    }catch(Exception ex){
      android.util.Log.e("SupabaseConfigSync","Cannot cache signed publication",ex);
      throw new IllegalStateException("Cannot cache signed publication: "+ex.getMessage(),ex);
    }
  }

  /** Verify the cached signature again before returning any published settings. */
  static JSONObject cached(Context context) {
    try {
      SharedPreferences prefs = context.getApplicationContext()
          .getSharedPreferences(STORE, Context.MODE_PRIVATE);
      String signed = prefs.getString("signed_document", "");
      if (signed.isEmpty()) return null;
      JSONObject document = new JSONObject(signed);
      if (!CatalogSignature.verifyFull(document)) return null;
      FullCatalogEnvelope envelope = FullCatalogEnvelope.parse(document);
      if (envelope.version != prefs.getInt("version", 0)) return null;
      return envelope.snapshot;
    } catch (Exception ignored) {
      return null;
    }
  }
}
