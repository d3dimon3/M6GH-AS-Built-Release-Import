package com.calibre90.astestmob;

/**
 * Restore only the same owner-signed Supabase publication that Config uses.
 * Never fetch the retired GitHub publication: it may be stale or signed with
 * an unrelated key. Both manual restore and startup sync use this method.
 */
final class AdminOnlineRestore {
  private static final String PROJECT_URL = "https://zyksvoauffhexketcyrq.supabase.co";

  private AdminOnlineRestore() {}

  static FullCatalogEnvelope download() throws Exception {
    return SupabaseSignedCatalogReader.download(PROJECT_URL, SupabasePublicKey.VALUE);
  }

  /** Compatibility with callers that still pass a legacy GitHub token. */
  static FullCatalogEnvelope download(String ignoredLegacyToken) throws Exception {
    return download();
  }
}
