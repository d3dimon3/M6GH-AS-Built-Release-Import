package com.calibre90.astestmob;

import org.json.JSONObject;

/**
 * Strict parser for the full Studio publication format.
 * This class does not trust, persist or apply a downloaded configuration.
 * A caller MUST verify the publisher RSA signature before using its result.
 */
final class FullCatalogEnvelope {
  static final int MAX_PAYLOAD_CHARS = 262144;
  final int version;
  final String rawSnapshot;
  final JSONObject snapshot;
  final JSONObject document;

  private FullCatalogEnvelope(int version, String rawSnapshot, JSONObject snapshot, JSONObject document) {
    this.version = version;
    this.rawSnapshot = rawSnapshot;
    this.snapshot = snapshot;
    this.document = document;
  }

  static FullCatalogEnvelope parse(JSONObject document) throws Exception {
    if (document.getInt("schema") != 2 ||
        !"mazda6gh-published-settings".equals(document.getString("kind"))) {
      throw new IllegalArgumentException("Unsupported Studio publication");
    }
    int version = document.getInt("version");
    if (version < 1) throw new IllegalArgumentException("Invalid version");
    String raw = document.getString("snapshot_payload");
    if (raw.isEmpty() || raw.length() > MAX_PAYLOAD_CHARS) {
      throw new IllegalArgumentException("Invalid publication size");
    }
    String signature = document.getString("signature");
    if (signature.length() < 100 || signature.length() > 2048 ||
        !signature.matches("[A-Za-z0-9+/=]+")) {
      throw new IllegalArgumentException("Invalid signature encoding");
    }
    JSONObject snapshot = new JSONObject(raw);
    AdminSnapshot.validate(snapshot);
    return new FullCatalogEnvelope(version, raw, snapshot, document);
  }

  String signingMessage() {
    return "MAZDA6GH-SETTINGS-V2\n" + version + "\n" + rawSnapshot;
  }
}
