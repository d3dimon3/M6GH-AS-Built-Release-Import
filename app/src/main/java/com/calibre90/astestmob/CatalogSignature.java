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
  // Set to a dedicated catalog-signing RSA public key after generating the publisher key pair.
  // Never reuse or embed the private license-generation key.
  private static final String CATALOG_PUBLIC_KEY_BASE64 = "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAmdmD0x5h5YVrtzkkTW8crB4GgupJ/0DXnD3BjTeM4m9wd4ugCbrcf+0+5PlV9j6uNTY71NBudbH3xQR86GPi2+GlFOzBzeDOiRr9ZHaspdVpCHAGA37eGnR2sDWEGmmN3i7GeglW75hcpUKdMJ94Z6ZYTH6Bl+fUjZXujb9ABN3SWizQK121CbgU9RghFpXN71IULj9xQwpXgtCNrRib54z2qAsP4V2YUoRoLOHjFclj8GLnu5DCvk6ucy19aKGN80SJi/yQxIVR5Ac5E0GhOxcJ/Q6q1gTaycwkbyiTPm4yX853qUSHbjBdAKoBLEu2CpW+cZINE2gqGrgEAYNw9sMHi3ggQ8VxkX4RGthiFHXQCtdu+U8WcRFGNmXpRrLPIAYhPR+9OGGmxpmojbriXQkkttDqbTgUpRukezYDa83DemVq5dCrWFRS0k71nIfizvreVvZkLtq/vKbPvipHXtXCZBZBA1OdvXT30GtJ2fiET8C7SWmavoru1of3/ULFAgMBAAE=";
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
