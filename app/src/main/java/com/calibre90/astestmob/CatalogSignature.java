package com.calibre90.astestmob;

import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/** Verifies the publisher's RSA signature, independently of license activation. */
final class CatalogSignature {
  // Dedicated RSA 3072-bit catalog verification key (public only).
  // Never reuse or embed the private license-generation key.
  private static final String CATALOG_PUBLIC_KEY_BASE64 = "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAoqrQX71QDDkyQ9Jru5IwEjPw6GUclNHYD1Ve3I9Le/7+p7LIOR6h914ZuVBPrPFIptTrRkshmieU/YgYYd/eURvJHn0ovKPOrt2wytN5rv/teah3pERQDFpsZaaL/ZJZ73yH4sijciaGjFCzhjLNXDB3A+kkIvpqIQtVXARobF15JTJ8I7V4TLBFc/s7gSlUHCDsvzhY1yLe6zdH8DoIAZlzZVhVI/wcB1Qm8pSAERkE6f8QPPPYyuxfydFMIBrBwFNjtCajjHA7wpURuZKotwisKVAA3w6iFcnyVhgmQH6D7fD+gUPTj9vaPHzzSIAghQvAA3qL3+06C4uLEtSZ3vodcYxKf18+VL/bUi6558XPrsQ/1NQ+mEbGTFDx2npV6iXwWe21YR6t1DiD2Gl09fJo5pyqaAzSTF5dQ3jFNjvqL0xOXHbIpTWnViy54GM39KHfg2R6rzAC9SxTUr8z9bky6K/ZXMYmoBLPwyf90+RTaqR9PzmFWkGxewXSjHp7AgMBAAE=";
  private CatalogSignature(){}

  /** Verify a full published configuration; never trust an unsigned admin export. */
  static boolean verifyFull(JSONObject document) {
    if (CATALOG_PUBLIC_KEY_BASE64.isEmpty()) return false;
    try {
      FullCatalogEnvelope envelope = FullCatalogEnvelope.parse(document);
      byte[] publicDer = Base64.decode(CATALOG_PUBLIC_KEY_BASE64, Base64.DEFAULT);
      PublicKey publicKey = KeyFactory.getInstance("RSA")
          .generatePublic(new X509EncodedKeySpec(publicDer));
      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(publicKey);
      verifier.update(envelope.signingMessage().getBytes(StandardCharsets.UTF_8));
      return verifier.verify(Base64.decode(document.getString("signature"), Base64.DEFAULT));
    } catch (Exception ex) {
      return false;
    }
  }

  static boolean verify(JSONObject document){
    if(CATALOG_PUBLIC_KEY_BASE64.isEmpty())return false; // Fail closed until configured.
    try{
      if(document.getInt("schema")!=1)return false;
      int version=document.getInt("version");
      if(version<1)return false;
      String payload="MAZDA6GH-CATALOG-V1\n"+version+"\n"+document.getString("features_payload");
      // Verify the exact published UTF-8 payload, not a reserialized JSON array.
      new org.json.JSONArray(document.getString("features_payload")); // Parse before verifying.
      byte[] publicDer=Base64.decode(CATALOG_PUBLIC_KEY_BASE64,Base64.DEFAULT);
      PublicKey publicKey=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicDer));
      Signature verifier=Signature.getInstance("SHA256withRSA");
      verifier.initVerify(publicKey);
      verifier.update(payload.getBytes(StandardCharsets.UTF_8));
      return verifier.verify(Base64.decode(document.getString("signature"),Base64.DEFAULT));
    }catch(Exception ex){return false;}
  }
}
