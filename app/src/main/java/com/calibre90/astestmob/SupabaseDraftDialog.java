package com.calibre90.astestmob;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Explicit test-only UI: submit a pending draft; NEVER claim it was published. */
final class SupabaseDraftDialog {
  private static final String URL_BASE = "https://zyksvoauffhexketcyrq.supabase.co";
  private SupabaseDraftDialog() {}

  static void show(Activity activity) {
    LinearLayout fields = new LinearLayout(activity);
    fields.setOrientation(LinearLayout.VERTICAL);
    int pad = (int)(16 * activity.getResources().getDisplayMetrics().density);
    fields.setPadding(pad, pad, pad, 0);
    TextView note = new TextView(activity);
    note.setText("Тестовая отправка черновика. Настройки НЕ публикуются для Config. Используйте только публичный ключ sb_publishable_, НЕ secret/service_role.");
    fields.addView(note);
    EditText key = new EditText(activity);
    key.setHint("Supabase publishable key (sb_publishable_...)");
    key.setSingleLine(true);
    fields.addView(key);
    EditText email = new EditText(activity);
    email.setHint("Email администратора");
    email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
    fields.addView(email);
    EditText password = new EditText(activity);
    password.setHint("Пароль администратора");
    password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    fields.addView(password);
    new AlertDialog.Builder(activity)
      .setTitle("Supabase — тест черновика")
      .setView(fields)
      .setNegativeButton("Отмена", null)
      .setPositiveButton("Отправить черновик", (dialog, which) -> {
        final String publicKey = key.getText().toString().trim();
        final String mail = email.getText().toString().trim();
        final String pass = password.getText().toString();
        password.setText("");
        key.setText("");
        new Thread(() -> {
          try {
            JSONObject snapshot = AdminSnapshot.exportLocal(activity);
            AdminSnapshot.validate(snapshot);
            SupabaseAdminAuth.Session session =
              SupabaseAdminAuth.signIn(URL_BASE, publicKey, mail, pass);
            // Get current public version. The database RLS policy enforces it again
            // at insert time, preventing stale drafts from silently overwriting.
            long baseVersion = readVersion(publicKey);
            String id = SupabaseAdminDraftTransport.submit(
              URL_BASE, publicKey, session.accessToken, snapshot, baseVersion);
            activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
              .setTitle("Черновик принят")
              .setMessage("Черновик ID: " + id +
                "\nНастройки ещё НЕ опубликованы. Config не получит их до серверной проверки и RSA-подписи.")
              .setPositiveButton("Закрыть", null).show());
          } catch (Exception ex) {
            activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
              .setTitle("Ошибка отправки Supabase")
              .setMessage(ex.getMessage())
              .setPositiveButton("Закрыть", null).show());
          }
        }, "SupabaseDraftTest").start();
      }).show();
  }

  private static long readVersion(String publishableKey) throws Exception {
    if (publishableKey == null || !publishableKey.startsWith("sb_publishable_"))
      throw new SecurityException("Нужен только sb_publishable_ ключ");
    HttpURLConnection c = (HttpURLConnection)new URL(
      URL_BASE + "/rest/v1/app_config?id=eq.main&select=version&limit=1").openConnection();
    try {
      c.setConnectTimeout(10000);
      c.setReadTimeout(10000);
      c.setRequestProperty("apikey", publishableKey);
      if (c.getResponseCode() != 200) throw new IllegalStateException("Ошибка чтения версии: HTTP " + c.getResponseCode());
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (InputStream in = c.getInputStream()) {
        byte[] buf = new byte[1024]; int n;
        while ((n=in.read(buf))!=-1) {
          if (out.size()+n > 4096) throw new IllegalStateException("Ответ слишком большой");
          out.write(buf,0,n);
        }
      }
      JSONArray rows = new JSONArray(new String(out.toByteArray(), StandardCharsets.UTF_8));
      if (rows.length()!=1) throw new IllegalStateException("Нет версии опубликованной конфигурации");
      return rows.getJSONObject(0).getLong("version");
    } finally { c.disconnect(); }
  }
}
