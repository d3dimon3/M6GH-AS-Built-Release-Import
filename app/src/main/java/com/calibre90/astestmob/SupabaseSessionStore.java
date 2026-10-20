package com.calibre90.astestmob;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Persist only the rotating refresh token, encrypted with Android Keystore AES-GCM. */
final class SupabaseSessionStore {
  private static final String ALIAS="m6gh_supabase_admin_refresh_v1";
  private static final String PREFS="m6gh_supabase_admin_secure_session";
  private SupabaseSessionStore(){}
  private static SharedPreferences prefs(Context c){
    return c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
  }
  private static SecretKey key(boolean create) throws Exception {
    KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
    if(ks.containsAlias(ALIAS))return ((KeyStore.SecretKeyEntry)ks.getEntry(ALIAS,null)).getSecretKey();
    if(!create)return null;
    KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
    gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setRandomizedEncryptionRequired(true).build());
    return gen.generateKey();
  }
  static boolean hasSession(Context c){return prefs(c).contains("iv")&&prefs(c).contains("data");}
  static void save(Context c,String refreshToken) throws Exception {
    if(refreshToken==null||refreshToken.trim().isEmpty())throw new SecurityException("Missing refresh token");
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE,key(true));
    byte[] data=cipher.doFinal(refreshToken.getBytes(StandardCharsets.UTF_8));
    if(!prefs(c).edit().putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
      .putString("data",Base64.encodeToString(data,Base64.NO_WRAP)).commit())
      throw new IllegalStateException("Cannot persist Supabase session");
  }
  static String load(Context c) throws Exception {
    if(!hasSession(c))return null;
    SecretKey secret=key(false);
    if(secret==null)throw new SecurityException("Android Keystore session key unavailable");
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE,secret,new GCMParameterSpec(128,
      Base64.decode(prefs(c).getString("iv",""),Base64.NO_WRAP)));
    return new String(cipher.doFinal(Base64.decode(prefs(c).getString("data",""),Base64.NO_WRAP)),StandardCharsets.UTF_8);
  }
  static void clear(Context c){prefs(c).edit().clear().commit();}
}
