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

  /** Send pending draft; the trusted server signs and publishes separately. */
  static void show(Activity activity) {
    if(SupabaseSessionStore.hasSession(activity)){
      submit(activity,null,null);
      return;
    }
    showLogin(activity);
  }

  private static void showLogin(Activity activity) {
    LinearLayout fields=new LinearLayout(activity);
    fields.setOrientation(LinearLayout.VERTICAL);
    int pad=(int)(16*activity.getResources().getDisplayMetrics().density);
    fields.setPadding(pad,pad,pad,0);
    TextView note=new TextView(activity);
    note.setText("Войдите один раз. Сессия сохранится в защищённом хранилище Android. При выходе из аккаунта или истечении сессии потребуется повторный вход.");
    fields.addView(note);
    EditText email=new EditText(activity);
    email.setHint("Email администратора");
    email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
    fields.addView(email);
    EditText password=new EditText(activity);
    password.setHint("Пароль администратора");
    password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    fields.addView(password);
    new AlertDialog.Builder(activity)
      .setTitle("Вход администратора Supabase")
      .setView(fields)
      .setNegativeButton("Отмена",null)
      .setPositiveButton("Войти и отправить",(d,w)->{
        String mail=email.getText().toString().trim();
        String pass=password.getText().toString();
        password.setText("");
        submit(activity,mail,pass);
      }).show();
  }

  private static void submit(Activity activity,String mail,String pass) {
    final String publicKey=SupabasePublicKey.VALUE;
    new Thread(()->{
      try{
        JSONObject snapshot=AdminSnapshot.exportLocal(activity);
        AdminSnapshot.validate(snapshot);
        SupabaseAdminAuth.Session session;
        if(mail!=null){
          session=SupabaseAdminAuth.signIn(URL_BASE,publicKey,mail,pass);
        }else{
          String saved=SupabaseSessionStore.load(activity);
          session=SupabaseAdminAuth.refresh(URL_BASE,publicKey,saved);
        }
        // Supabase rotates refresh tokens on renewal: persist the new token
        // before making the submission, so future sends remain authenticated.
        SupabaseSessionStore.save(activity,session.refreshToken);
        long baseVersion=readVersion(publicKey);
        String id=SupabaseAdminDraftTransport.submit(
          URL_BASE,publicKey,session.accessToken,snapshot,baseVersion);
        activity.runOnUiThread(()->new AlertDialog.Builder(activity)
          .setTitle("Настройки отправлены на сервер")
          .setMessage("Черновик ID: "+id+
            "\\nНастройки переданы на проверку. Config получит их после серверной публикации и RSA-подписи.")
          .setPositiveButton("Закрыть",null).show());
      }catch(Exception ex){
        final String message=ex.getMessage()==null?ex.toString():ex.getMessage();
        boolean authExpired=mail==null&&(message.contains("Auth HTTP 4")||
          message.contains("Keystore")||message.contains("refresh token")||
          message.contains("AEADBadTag")||message.contains("Signature/MAC"));
        if(authExpired)SupabaseSessionStore.clear(activity);
        activity.runOnUiThread(()->new AlertDialog.Builder(activity)
          .setTitle(authExpired?"Требуется повторный вход":"Ошибка отправки Supabase")
          .setMessage(authExpired?"Сохранённая сессия истекла или недоступна. Войдите повторно.":message)
          .setPositiveButton(authExpired?"Войти":"Закрыть",(d,w)->{
            if(authExpired)showLogin(activity);
          }).show());
      }
    },"SupabaseAdminSubmit").start();
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
