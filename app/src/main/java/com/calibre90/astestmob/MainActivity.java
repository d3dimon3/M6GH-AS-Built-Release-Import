package com.calibre90.astestmob;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.io.*;

public class MainActivity extends Activity {
  StudioView view;
  private static final int SPLASH_PICK_REQUEST=9091;
  private File splashFile(){return new File(getFilesDir(),"custom_launch_splash.png");}
  private static final String SPLASH_KEY="splash_image_b64";
  private String publishedSplash(){
    return PublishedFullSettings.moduleSetting(this,"appearance_"+SPLASH_KEY);
  }
  private void refreshPublishedSplash(){
    if("com.calibre90.catalogadmin".equals(getPackageName()))return;
    String encoded=publishedSplash();
    if(encoded==null||encoded.isEmpty())return;
    android.content.SharedPreferences prefs=getSharedPreferences("studio_splash_sync",MODE_PRIVATE);
    String hash=String.valueOf(encoded.hashCode());
    if(hash.equals(prefs.getString("hash",""))&&splashFile().isFile())return;
    try{
      byte[] bytes=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT);
      if(bytes.length>240000)throw new IOException("Изображение слишком большое");
      Bitmap bmp=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
      if(bmp==null)throw new IOException("Некорректная заставка");
      bmp.recycle();
      File temp=new File(getFilesDir(),"published_splash.tmp");
      try(FileOutputStream out=new FileOutputStream(temp)){out.write(bytes);}
      if(splashFile().exists()&&!splashFile().delete())throw new IOException("Не удалось заменить заставку");
      if(!temp.renameTo(splashFile()))throw new IOException("Не удалось сохранить заставку");
      prefs.edit().putString("hash",hash).apply();
    }catch(Exception e){android.util.Log.e("SplashSync","Failed to apply published splash",e);}
  }
  private void saveChosenSplash(Uri uri) throws IOException {
    BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;
    try(InputStream input=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(input,null,opts);}
    if(opts.outWidth<1||opts.outHeight<1||opts.outWidth>12000||opts.outHeight>12000)throw new IOException("Неподдерживаемое изображение");
    opts.inJustDecodeBounds=false;opts.inSampleSize=Math.max(1,Math.max(opts.outWidth/1600,opts.outHeight/2400));
    Bitmap bitmap;
    try(InputStream input=getContentResolver().openInputStream(uri)){bitmap=BitmapFactory.decodeStream(input,null,opts);}
    if(bitmap==null)throw new IOException("Не удалось прочитать изображение");
    File temporary=new File(getFilesDir(),"custom_launch_splash.tmp");
    // The signed publication accepts at most 256 KiB for ALL settings, not just the image.
    // Downscale the splash to leave room for features, rows, FAQ and other appearance data.
    if(bitmap.getWidth()>900||bitmap.getHeight()>1600){
      float scale=Math.min(900f/bitmap.getWidth(),1600f/bitmap.getHeight());
      Bitmap scaled=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*scale)),Math.max(1,Math.round(bitmap.getHeight()*scale)),true);
      if(scaled!=bitmap){bitmap.recycle();bitmap=scaled;}
    }
    byte[] compressed=null;
    for(int quality:new int[]{75,55,40,25,15}){
      ByteArrayOutputStream stream=new ByteArrayOutputStream();
      if(!bitmap.compress(Bitmap.CompressFormat.JPEG,quality,stream))throw new IOException("Ошибка кодирования JPG");
      if(stream.size()<=60000){compressed=stream.toByteArray();break;}
    }
    bitmap.recycle();
    if(compressed==null)throw new IOException("Изображение слишком большое для онлайн-публикации (предел 60 КБ)");
    try(FileOutputStream output=new FileOutputStream(temporary)){output.write(compressed);}
    if("com.calibre90.catalogadmin".equals(getPackageName())){
      String base64=android.util.Base64.encodeToString(compressed,android.util.Base64.NO_WRAP);
      if(!getSharedPreferences("run35_settings",MODE_PRIVATE).edit()
          .putString("appearance_"+SPLASH_KEY,base64).commit())
        throw new IOException("Не удалось сохранить заставку для экспорта");
    }
    if(splashFile().exists()&&!splashFile().delete())throw new IOException("Не удалось заменить заставку");
    if(!temporary.renameTo(splashFile()))throw new IOException("Не удалось сохранить заставку");
  }
  private void showLaunchSplash(ImageView splash){
    refreshPublishedSplash();
    if(splashFile().isFile()){
      Bitmap bitmap=BitmapFactory.decodeFile(splashFile().getAbsolutePath());
      if(bitmap!=null){splash.setImageBitmap(bitmap);return;}
    }
    splash.setImageResource(R.drawable.splash_studio);
  }
  String pendingAdminSnapshot;
  boolean adminSession=false;
  private static final String ADMIN_MAGIC="MAZDA6GH-ADMIN-V1";
  private static final String ADMIN_PUBLIC_KEY="MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA5ZvEA9vU4BHQ35UQKaBqsdZElVfATRC79SjFLRwMN/9g9ezgQk91ziDLaDzc4SuoRtuqiKDBqhRR/HJLfvfOB6+/DNAyc896dfW85seTaN51shqae5Rw/boIO0C9w9rgoJg7N7SHfqDvLAaKBeNYtleBdfhfU5AShumRUZnYRrT1irp0VLGLlTiYF+CMCF+oN2NV2QzA/cENGCCmxjsWpQKionXNPGKx7D36DBzLwmAYOF3M2J/JRuCtEpKO8O9/cMoTNZoB8/R40A4vY4pZBbuPZnhpmHiCXx5uzXXrJatpTTj04dqFw2Mrbo/CQP80FRYnNGipeCbIbGtk/tda8wIDAQAB";
  boolean tryAdminKey(Uri uri){
    try{
      ByteArrayOutputStream buffer=new ByteArrayOutputStream();
      try(InputStream in=getContentResolver().openInputStream(uri)){
        if(in==null)return false;
        byte[] bytes=new byte[1024];int n;
        while((n=in.read(bytes))!=-1){if(buffer.size()+n>4096)return false;buffer.write(bytes,0,n);}
      }
      String[] lines=new String(buffer.toByteArray(),"UTF-8").trim().split("\\r?\n");
      if(lines.length!=2||!ADMIN_MAGIC.equals(lines[0]))return false;
      byte[] encoded=android.util.Base64.decode(ADMIN_PUBLIC_KEY,android.util.Base64.DEFAULT);
      java.security.PublicKey key=java.security.KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(encoded));
      java.security.Signature verifier=java.security.Signature.getInstance("SHA256withRSA");
      verifier.initVerify(key);verifier.update(ADMIN_MAGIC.getBytes("UTF-8"));
      return verifier.verify(android.util.Base64.decode(lines[1],android.util.Base64.DEFAULT));
    }catch(Exception ignored){return false;}
  }
  boolean handleAdminKey(Uri uri){
    String name=uri.getLastPathSegment();
    if(name==null||!name.toLowerCase(java.util.Locale.US).endsWith("mazda6gh-admin.key"))return false;
    if(tryAdminKey(uri))admin();
    else Toast.makeText(this,"Неверный ключ администратора",Toast.LENGTH_LONG).show();
    return true;
  }

  private static boolean verifyStudioAdminPassword(String candidate) {
    try {
      byte[] salt=hexBytes("3bccda4bc5fe6fd75885c87d5a137dd5");
      byte[] value=candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      java.security.MessageDigest md=java.security.MessageDigest.getInstance("SHA-256");
      md.update(salt);
      byte[] digest=md.digest(value);
      byte[] expected=hexBytes("1af4681bfe9f3c023102fe69730cbc8360ac88c434662d7a4fddb95f131d7565");
      return java.security.MessageDigest.isEqual(digest,expected);
    }catch(Exception ignored){return false;}
  }
  private static byte[] hexBytes(String hex){
    byte[] out=new byte[hex.length()/2];
    for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
    return out;
  }

  private void styleAdminHomeButton(Button button,String symbol){
    // Real native buttons: no click targets are baked into the background artwork.
    String label=button.getText().toString().toUpperCase(java.util.Locale.forLanguageTag("ru"));
    button.setText(symbol+"   "+label+"   ›");
    button.setTextColor(Color.rgb(22,28,38));
    button.setTextSize(13);
    button.setAllCaps(false);
    button.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
    button.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);
    button.setPadding(dp(18),0,dp(12),0);
    button.setBackgroundResource(R.drawable.admin_metal_button);
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(64));
    lp.setMargins(0,0,0,dp(9));
    button.setLayoutParams(lp);
  }

  final String[] modules={"IC","BCM","RKE","ABS"};
  final String[] ids={"720","726","731","760"};
  final String[] names={"Instrument Cluster","Body Control Module","Remote Keyless Entry","Anti-lock Brake System"};
  final String[] defaults={"2B00 7126 806B","000E F255 E766","0F0E C236 CC0C","F0FD ECCC EEBF","A461 7C00 00AE","00A0 0102 10E1","0822 FF08 0868","5852 00DA","C834 385E"};
  ArrayList<String>[] rows=new ArrayList[4];
  boolean[][] checks=new boolean[4][3];
  int active=0;
  @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
    for(int i=0;i<4;i++)rows[i]=new ArrayList<>();
    features.addAll(StudioSettings.load(this));restoreCustomTabPositions();restoreSelectedModule();seedBuiltInRows();restoreAdminRows();
    if(getClass().getName().equals("com.calibre90.astestmob.StandaloneAdminActivity")){
      // The original admin callbacks depend on StudioView even when the editor is hidden.
      view=new StudioView();
      LinearLayout adminHome=new LinearLayout(this);
      adminHome.setOrientation(LinearLayout.VERTICAL);
      // Admin-only dark automotive theme. Studio screens and their behavior remain untouched.
      // The background is a separate bitmap layer, never a screenshot of buttons.
      // Resolve by name so the source still builds before the JPG asset is uploaded.
      int backgroundId=getResources().getIdentifier(
          "admin_gh_fullscreen_background","drawable",getPackageName());
      FrameLayout adminRoot=new FrameLayout(this);
      if(backgroundId!=0){
        ImageView adminBackdrop=new ImageView(this);
        adminBackdrop.setImageResource(backgroundId);
        // FIT_CENTER preserves the entire GH car and original artwork proportions.
        adminBackdrop.setScaleType(ImageView.ScaleType.FIT_CENTER);
        adminBackdrop.setBackgroundColor(Color.rgb(6,7,11));
        adminRoot.addView(adminBackdrop,new FrameLayout.LayoutParams(-1,-1));
        // Subtle veil behind text/buttons while retaining the photograph.
        adminHome.setBackgroundColor(Color.TRANSPARENT);
      }else{
        adminRoot.setBackgroundResource(R.drawable.admin_home_background);
        adminHome.setBackgroundColor(Color.TRANSPARENT);
      }
      // Position real menu controls over the six shaded button slots in the artwork.
      adminHome.setPadding(dp(34),0,dp(34),0);
      Button reopen=new Button(this);
      reopen.setText("Администрирование интерфейса");
      reopen.setOnClickListener(v->showAdminTabs());
      styleAdminHomeButton(reopen, "⚙");
      adminHome.addView(reopen);
      Button exportSnapshot=new Button(this);
      exportSnapshot.setText("Экспорт настроек M6GH CONFIG");
      exportSnapshot.setOnClickListener(v->{
        try{
          org.json.JSONObject snapshot=AdminSnapshot.exportLocal(this);
          AdminSnapshot.validate(snapshot);
          pendingAdminSnapshot=snapshot.toString(2);
          org.json.JSONObject stores=snapshot.getJSONObject("stores");
          org.json.JSONObject settings=stores.getJSONObject("run35_settings");
          org.json.JSONObject rowStore=stores.getJSONObject("studio_admin_rows");
          org.json.JSONObject customRowStore=stores.optJSONObject("studio_admin_custom_rows");
          org.json.JSONObject moduleStore=stores.getJSONObject("studio_custom_modules");
          int featureCount=new org.json.JSONArray(settings.optString("features","[]")).length();
          int moduleCount=new org.json.JSONArray(moduleStore.optString("catalog","[]")).length();
          String details="Функций: "+featureCount+"\nДополнительных блоков: "+moduleCount+
            "\nСохранённых наборов строк: "+(rowStore.length()+(customRowStore==null?0:customRowStore.length()))+
            "\nПараметров оформления и блоков: "+Math.max(0,settings.length()-(settings.has("features")?1:0));
          new AlertDialog.Builder(this).setTitle("Проверка полного экспорта")
            .setMessage(details+"\n\nЭкспортируются все сохранённые настройки, а не только текущий блок.")
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Сохранить JSON",(dialog,which)->{
              Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
              intent.addCategory(Intent.CATEGORY_OPENABLE);
              intent.setType("application/json");
              intent.putExtra(Intent.EXTRA_TITLE,"mazda6gh-admin-settings.json");
              startActivityForResult(intent,9087);
            }).show();
        }catch(Exception ex){
          Toast.makeText(this,"Ошибка экспорта: "+ex.getMessage(),Toast.LENGTH_LONG).show();
        }
      });
      styleAdminHomeButton(exportSnapshot, "↓");
      adminHome.addView(exportSnapshot);
      Button importSnapshot=new Button(this);
      importSnapshot.setText("Импорт настроек M6GH CONFIG");
      importSnapshot.setOnClickListener(v->{
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent,9088);
      });
      styleAdminHomeButton(importSnapshot, "↑");
      adminHome.addView(importSnapshot);
      Button restoreOnline=new Button(this);
      restoreOnline.setText("Восстановить настройки из сервера");
      restoreOnline.setOnClickListener(v->{
        restoreOnline.setEnabled(false);
        Toast.makeText(this,"Загружаем опубликованные настройки…",Toast.LENGTH_SHORT).show();
        new Thread(()->{
          try{
            final FullCatalogEnvelope published=AdminOnlineRestore.download();
            final org.json.JSONObject stores=published.snapshot.getJSONObject("stores");
            final int functions=new org.json.JSONArray(
              stores.getJSONObject("run35_settings").optString("features","[]")).length();
            final int modules=new org.json.JSONArray(
              stores.getJSONObject("studio_custom_modules").optString("catalog","[]")).length();
            final org.json.JSONObject faqStore=stores.optJSONObject("studio_faq");
            final int faqCount=faqStore==null?0:new org.json.JSONArray(faqStore.optString("entries","[]")).length();
            runOnUiThread(()->{
              restoreOnline.setEnabled(true);
              new AlertDialog.Builder(this)
                .setTitle("Восстановить из сервера — версия "+published.version)
                .setMessage("Найдено функций: "+functions+
                  "\nДополнительных блоков: "+modules+
                  "\nЗаписей FAQ: "+faqCount+
                  "\n\nБудут заменены локальные настройки администратора: функции, строки, блоки, оформление и FAQ. Данные ABT и лицензии не затрагиваются. Перед заменой текущие настройки будут сохранены в резервную копию внутри приложения. Продолжить?")
                .setNegativeButton("Отмена",null)
                .setPositiveButton("Восстановить",(dialog,which)->{
                  try{
                    // Preserve existing admin data before any destructive import.
                    try{
                      org.json.JSONObject backup=AdminSnapshot.exportLocal(this);
                      AdminSnapshot.validate(backup);
                      android.content.SharedPreferences backups=
                          getSharedPreferences("studio_admin_restore_backup",MODE_PRIVATE);
                      // Keep the previous backup as well: a second restore must not
                      // destroy the only copy of the earlier administrator draft.
                      String previous=backups.getString("previous_snapshot",null);
                      android.content.SharedPreferences.Editor backupEdit=backups.edit();
                      if(previous!=null)backupEdit.putString("older_snapshot",previous);
                      backupEdit.putString("previous_snapshot",backup.toString());
                      if(!backupEdit.commit())
                        throw new IllegalStateException("Не удалось сохранить резервную копию");
                    }catch(IllegalStateException empty){
                      if(!empty.getMessage().startsWith("Нет сохранённых настроек"))throw empty;
                    }
                    AdminSnapshot.importLocal(this,published.snapshot);
                    if(!getSharedPreferences("studio_admin_sync",MODE_PRIVATE).edit()
                        .putInt("restored_publication_version",published.version)
                        .putString("last_synced_snapshot",published.snapshot.toString()).commit())
                      throw new IllegalStateException("Настройки восстановлены, но не удалось сохранить номер серверной версии");
                    features.clear();
                    features.addAll(StudioSettings.load(this));
                    restoreAdminRows();
                    if(view!=null)view.invalidate();
                    new AlertDialog.Builder(this)
                      .setTitle("Настройки восстановлены")
                      .setMessage("Каталог сервера версии "+published.version+
                        " импортирован. Откройте «Администрирование интерфейса», чтобы проверить блоки и функции.")
                      .setPositiveButton("Понятно",null).show();
                  }catch(Exception ex){
                    new AlertDialog.Builder(this).setTitle("Ошибка восстановления")
                      .setMessage(ex.getMessage()).setPositiveButton("Закрыть",null).show();
                  }
                }).show();
            });
          }catch(Exception ex){
            runOnUiThread(()->{
              restoreOnline.setEnabled(true);
              new AlertDialog.Builder(this).setTitle("Не удалось загрузить с сервера")
                .setMessage(ex.getMessage()).setPositiveButton("Закрыть",null).show();
            });
          }
        }).start();
      });
      styleAdminHomeButton(restoreOnline, "↓");
      adminHome.addView(restoreOnline);
      // Check the common published configuration whenever Admin starts.
      // Never silently overwrite locally edited settings: the existing restore
      // flow previews the server version and creates a backup before import.
      new Thread(()->{
        try{
          FullCatalogEnvelope remote=AdminOnlineRestore.download();
          int applied=getSharedPreferences("studio_admin_sync",MODE_PRIVATE)
              .getInt("restored_publication_version",0);
          if(remote.version>applied){
            android.content.SharedPreferences sync=getSharedPreferences("studio_admin_sync",MODE_PRIVATE);
            String baseline=sync.getString("last_synced_snapshot",null);
            String local=null;
            try{local=AdminSnapshot.exportLocal(this).toString();}catch(Exception ignored){}
            if(baseline!=null && local!=null && AdminSyncSnapshot.same(baseline,local)){
              // A clean editor can accept the newer signed publication silently.
              // Keep a rollback snapshot before changing any settings.
              try{
                org.json.JSONObject backup=AdminSnapshot.exportLocal(this);
                android.content.SharedPreferences backups=
                    getSharedPreferences("studio_admin_restore_backup",MODE_PRIVATE);
                android.content.SharedPreferences.Editor backupEdit=backups.edit();
                String previous=backups.getString("previous_snapshot",null);
                if(previous!=null)backupEdit.putString("older_snapshot",previous);
                backupEdit.putString("previous_snapshot",backup.toString());
                if(!backupEdit.commit())throw new IllegalStateException("Ошибка резервной копии");
                AdminSnapshot.importLocal(this,remote.snapshot);
                if(!sync.edit().putInt("restored_publication_version",remote.version)
                    .putString("last_synced_snapshot",remote.snapshot.toString()).commit())
                  throw new IllegalStateException("Ошибка сохранения версии синхронизации");
                runOnUiThread(()->{
                  features.clear();
                  features.addAll(StudioSettings.load(this));
                  restoreAdminRows();
                  if(view!=null)view.invalidate();
                  Toast.makeText(this,"Общие настройки обновлены: версия "+remote.version,
                      Toast.LENGTH_LONG).show();
                });
              }catch(Exception syncError){
                android.util.Log.e("AdminSync","Automatic sync failed",syncError);
                runOnUiThread(()->Toast.makeText(this,
                    "Синхронизация: "+syncError.getMessage(),Toast.LENGTH_LONG).show());
              }
            }else{
              runOnUiThread(()->new AlertDialog.Builder(this)
                .setTitle("Обновления общей админки")
                .setMessage("На сервере версия "+remote.version+
                  ". Есть локальные настройки, которые могут отличаться от серверных. "+
                  "Перед заменой будет создана резервная копия.")
                .setNegativeButton("Позже",null)
                .setPositiveButton("Синхронизировать",(d,w)->restoreOnline.performClick())
                .show());
            }
          }
        }catch(Exception ex){
          android.util.Log.w("AdminSync","Не удалось проверить общую конфигурацию",ex);
        }
      },"AdminSyncCheck").start();
      Button restoreBackup=new Button(this);
      restoreBackup.setText("Восстановить локальную копию");
      restoreBackup.setOnClickListener(v->{
        android.content.SharedPreferences backups=
            getSharedPreferences("studio_admin_restore_backup",MODE_PRIVATE);
        String newest=backups.getString("previous_snapshot",null);
        String older=backups.getString("older_snapshot",null);
        if(newest==null && older==null){
          new AlertDialog.Builder(this).setTitle("Резервных копий нет")
            .setMessage("Копии создаются перед восстановлением настроек с сервера.")
            .setPositiveButton("Закрыть",null).show();
          return;
        }
        java.util.ArrayList<String> labels=new java.util.ArrayList<>();
        java.util.ArrayList<String> values=new java.util.ArrayList<>();
        if(newest!=null){labels.add("Последняя резервная копия");values.add(newest);}
        if(older!=null){labels.add("Предыдущая резервная копия");values.add(older);}
        new AlertDialog.Builder(this).setTitle("Выберите локальную копию")
          .setItems(labels.toArray(new String[0]),(pick,index)->{
            final org.json.JSONObject candidate;
            try{
              candidate=new org.json.JSONObject(values.get(index));
              AdminSnapshot.validate(candidate);
            }catch(Exception ex){
              new AlertDialog.Builder(this).setTitle("Копия повреждена")
                .setMessage(ex.getMessage()).setPositiveButton("Закрыть",null).show();
              return;
            }
            new AlertDialog.Builder(this).setTitle("Восстановить локальные настройки?")
              .setMessage("Текущий локальный черновик будет заменён выбранной копией. "+
                "Файлы ABT и лицензия не изменятся. После восстановления для публикации "+
                "потребуется заново сверить настройки с сервером.")
              .setNegativeButton("Отмена",null)
              .setPositiveButton("Восстановить",(confirm,which)->{
                try{
                  // Back up the current draft before replacing it with a historical one.
                  org.json.JSONObject currentDraft=AdminSnapshot.exportLocal(this);
                  AdminSnapshot.validate(currentDraft);
                  android.content.SharedPreferences backupPrefs=
                      getSharedPreferences("studio_admin_restore_backup",MODE_PRIVATE);
                  String last=backupPrefs.getString("previous_snapshot",null);
                  android.content.SharedPreferences.Editor backupWrite=backupPrefs.edit();
                  if(last!=null)backupWrite.putString("older_snapshot",last);
                  if(!backupWrite.putString("previous_snapshot",currentDraft.toString()).commit())
                    throw new IllegalStateException("Не удалось сохранить текущий черновик");
                  AdminSnapshot.importLocal(this,candidate);
                  // A historical draft must never retain the current server baseline.
                  if(!getSharedPreferences("studio_admin_sync",MODE_PRIVATE).edit()
                      .remove("restored_publication_version").commit())
                    throw new IllegalStateException("Не удалось сбросить версию синхронизации");
                  features.clear();
                  features.addAll(StudioSettings.load(this));
                  restoreAdminRows();
                  if(view!=null)view.invalidate();
                  Toast.makeText(this,"Локальные настройки восстановлены",Toast.LENGTH_LONG).show();
                }catch(Exception ex){
                  new AlertDialog.Builder(this).setTitle("Ошибка восстановления")
                    .setMessage(ex.getMessage()).setPositiveButton("Закрыть",null).show();
                }
              }).show();
          }).show();
      });
      styleAdminHomeButton(restoreBackup, "↓");
      adminHome.addView(restoreBackup);
      Button publishOnline=new Button(this);
      publishOnline.setText("Отправить настройки на сервер");
      publishOnline.setOnClickListener(v->{
        try{
          org.json.JSONObject snapshot=AdminSnapshot.exportLocal(this);
          AdminSnapshot.validate(snapshot);
          final boolean saved=GitHubTokenStore.hasToken(this);
          final EditText tokenInput=new EditText(this);
          tokenInput.setSingleLine(true);
          tokenInput.setHint("Токен доступа к серверу (GitHub)");
          tokenInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
          new AlertDialog.Builder(this)
            .setTitle("Отправить на сервер")
            .setMessage(saved
              ?"Использовать сохранённый зашифрованный токен? Для замены или удаления нажмите «Токен сервера» на главном экране. Настройки сначала отправятся на проверку."
              :"Введите токен сервера (GitHub) с правами Contents и Actions: Read and write. После успешной отправки он будет сохранён в зашифрованном виде на этом устройстве. Настройки сначала отправятся на проверку.")
            .setView(saved?null:tokenInput)
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Отправить",(d,w)->{
              final String token;
              try{
                token=saved?GitHubTokenStore.load(this):tokenInput.getText().toString().trim();
                if(token==null||token.length()<12)throw new IllegalArgumentException("Введите действительный токен");
              }catch(Exception ex){
                Toast.makeText(this,"Токен: "+ex.getMessage(),Toast.LENGTH_LONG).show();
                return;
              }
              tokenInput.setText("");
              new Thread(()->{
                try{
                  // Automatically obtain the signed server configuration, preserving
                  // server-only settings while applying this admin's edited values.
                  FullCatalogEnvelope current=AdminOnlineRestore.download(token);
                  android.content.SharedPreferences syncPrefs=
                      getSharedPreferences("studio_admin_sync",MODE_PRIVATE);
                  String baseline=syncPrefs.getString("last_synced_snapshot",null);
                  int knownVersion=syncPrefs.getInt("restored_publication_version",0);
                  if(baseline==null || knownVersion<=0)
                    throw new IllegalStateException("Нет исходной синхронизированной версии. "+
                      "Сохраните экспорт настроек и выполните первое восстановление с сервера.");
                  org.json.JSONObject base=new org.json.JSONObject(baseline);
                  org.json.JSONObject merged=AdminThreeWayMerge.merge(base,snapshot,current.snapshot);
                  String sha=GitHubSnapshotUploader.upload(merged,token,current.snapshot);
                  if(!saved){
                    try{GitHubTokenStore.save(this,token);}
                    catch(Exception storeError){
                      runOnUiThread(()->Toast.makeText(this,
                        "Отправлено, но токен не сохранён: "+storeError.getMessage(),Toast.LENGTH_LONG).show());
                    }
                  }
                  // Request signed publication automatically after successful upload.
                  GitHubSnapshotUploader.requestRelease(token,sha);
                  runOnUiThread(()->new AlertDialog.Builder(this)
                    .setTitle("Настройки отправлены")
                    .setMessage("Изменения загружены на сервер, выпуск подписанной конфигурации запрошен автоматически. "+
                      "M6GH AS-BUILT CONFIG получит их после успешного завершения публикации.")
                    .setPositiveButton("Закрыть",null).show());
                }catch(Exception ex){
                  runOnUiThread(()->Toast.makeText(this,"Сервер: "+ex.getMessage(),Toast.LENGTH_LONG).show());
                }
              },"AdminGitHubUpload").start();
            }).show();
        }catch(Exception ex){
          Toast.makeText(this,"Ошибка подготовки: "+ex.getMessage(),Toast.LENGTH_LONG).show();
        }
      });
      styleAdminHomeButton(publishOnline, "↑");
      adminHome.addView(publishOnline);
      // Migration test only: preserve the existing GitHub publishing flow.
      Button supabaseDraftTest=new Button(this);
      supabaseDraftTest.setText("Supabase: отправить черновик (тест)");
      supabaseDraftTest.setOnClickListener(v->SupabaseDraftDialog.show(this));
      styleAdminHomeButton(supabaseDraftTest, "↑");
      adminHome.addView(supabaseDraftTest);
      Button tokenSettings=new Button(this);
      tokenSettings.setText("Токен сервера: изменить / удалить");
      tokenSettings.setOnClickListener(v->{
        final EditText replacement=new EditText(this);
        replacement.setSingleLine(true);
        replacement.setHint("Новый токен сервера");
        replacement.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
          .setTitle("Токен сервера")
          .setMessage(GitHubTokenStore.hasToken(this)
            ?"Токен сохранён в зашифрованном виде. Можно заменить или удалить его."
            :"Токен пока не сохранён. Введите его для будущих отправок.")
          .setView(replacement)
          .setNegativeButton("Отмена",null)
          .setNeutralButton("Удалить",(dialog,which)->{
            GitHubTokenStore.clear(this);
            Toast.makeText(this,"Токен удалён с устройства",Toast.LENGTH_SHORT).show();
          })
          .setPositiveButton("Сохранить",(dialog,which)->{
            try{
              GitHubTokenStore.save(this,replacement.getText().toString());
              replacement.setText("");
              Toast.makeText(this,"Токен защищённо сохранён",Toast.LENGTH_SHORT).show();
            }catch(Exception ex){
              Toast.makeText(this,"Ошибка токена: "+ex.getMessage(),Toast.LENGTH_LONG).show();
            }
          }).show();
      });
      styleAdminHomeButton(tokenSettings, "◆");
      adminHome.addView(tokenSettings);
      adminRoot.addView(adminHome,new FrameLayout.LayoutParams(-1,-1));
      setContentView(adminRoot);
      // The old automatic showAdminTabs() hid the home screen immediately.
      // Scale the six live controls to the artwork rather than covering the GH car.
      adminRoot.post(()->{
        int h=adminRoot.getHeight();
        if(h<=0)return;
        adminHome.setPadding(dp(34),(int)(h*0.187f),dp(34),0);
        int buttonHeight=Math.max(dp(44),(int)(h*0.057f));
        int buttonGap=Math.max(dp(6),(int)(h*0.011f));
        for(int i=0;i<adminHome.getChildCount();i++){
          View child=adminHome.getChildAt(i);
          LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,buttonHeight);
          lp.setMargins(0,0,0,buttonGap);
          child.setLayoutParams(lp);
        }
      });
      return;
    }
    setTitle(StudioSettings.appearance(this,"title","M6GH AS-BUILT CONFIG"));
    view=new StudioView();
    view.moduleTabOffset=Math.max(0,active*90f-280f);
    // Display the default or admin-selected splash for 1.5 seconds.
    ImageView splash=new ImageView(this);
    splash.setBackgroundColor(Color.BLACK);
    splash.setScaleType(ImageView.ScaleType.FIT_CENTER);
    showLaunchSplash(splash);
    setContentView(splash);
    new Handler(Looper.getMainLooper()).postDelayed(()->{
      if(isFinishing()||isDestroyed())return;
      if(LicenseGate.isActivated(this)){
        setContentView(view);
        refreshPublishedCatalog();
      }else{
        LicenseGate.enforce(this,()->{
          if(!isFinishing()&&!isDestroyed()){
            setContentView(view);
            refreshPublishedCatalog();
          }
        });
      }
    },1500);
  }
  /** Supabase is the only online source in the migration build; no legacy GitHub fallback. */
  private static final String SUPABASE_URL = "https://zyksvoauffhexketcyrq.supabase.co";
  private static final long SYNC_INTERVAL_MS = 60000L;
  private final Handler catalogHandler = new Handler(Looper.getMainLooper());
  private boolean catalogSyncInFlight = false;
  private boolean catalogSyncActive = false;
  private final Runnable catalogPoll = new Runnable(){
    @Override public void run(){
      if(!catalogSyncActive || isFinishing() || isDestroyed())return;
      refreshPublishedCatalog();
      catalogHandler.postDelayed(this,SYNC_INTERVAL_MS);
    }
  };
  @Override protected void onResume(){
    super.onResume();
    if(!getClass().getName().equals("com.calibre90.astestmob.StandaloneAdminActivity")){
      catalogSyncActive=true;
      catalogHandler.removeCallbacks(catalogPoll);
      catalogHandler.post(catalogPoll);
    }
  }
  @Override protected void onPause(){
    catalogSyncActive=false;
    catalogHandler.removeCallbacks(catalogPoll);
    super.onPause();
  }
  /** Fetch signed, published Supabase settings; drafts are never accepted by Config. */
  void refreshPublishedCatalog(){
    if(catalogSyncInFlight)return;
    final String key=BuildConfig.SUPABASE_PUBLISHABLE_KEY;
    if(key==null || !key.startsWith("sb_publishable_")){
      android.util.Log.e("SupabaseConfigSync","Missing Supabase publishable key in APK build");
      return;
    }
    catalogSyncInFlight=true;
    android.content.Context app=getApplicationContext();
    new Thread(()->{
      boolean updated=false;
      try{
        FullCatalogEnvelope envelope=SupabaseSignedCatalogReader.download(SUPABASE_URL,key);
        updated=FullCatalogSync.cacheVerified(app,envelope);
      }catch(Exception ex){
        android.util.Log.e("SupabaseConfigSync","Signed publication fetch failed",ex);
      }
      final boolean changed=updated;
      runOnUiThread(()->{
        catalogSyncInFlight=false;
        if(!changed || isFinishing() || isDestroyed())return;
        refreshPublishedSplash();
        features.clear();
        features.addAll(StudioSettings.load(this));
        if(view!=null)view.invalidate();
      });
    },"MazdaSupabaseConfigSync").start();
  }
  /** Built-in sample configuration: available before importing any vehicle ABT. */
  void seedBuiltInRows(){
    for(int n=0;n<defaults.length;n++){
      String address="720-"+(n<8?"01-"+String.format(Locale.US,"%02d",n+1):"02-01");
      AbtCodec.Row row=new AbtCodec.Row("IC",address,defaults[n],n<8?1:2);
      abtRows[0].add(row);original.put(key(row),AbtCodec.norm(row.value));
    }
    refreshRows(0);syncFeatureChecks(0);
  }
  void saveAdminRows(int module){
    org.json.JSONArray array=new org.json.JSONArray();
    for(AbtCodec.Row row:abtRows[module]){
      org.json.JSONObject item=new org.json.JSONObject();
      try{item.put("address",row.address);item.put("value",row.value);array.put(item);}
      catch(org.json.JSONException ex){throw new IllegalStateException(ex);}
    }
    getSharedPreferences("studio_admin_rows",MODE_PRIVATE).edit().putString(modules[module],array.toString()).apply();
  }
  /** On an imported ABT, persist only the administrator's explicit change,
      never the rest of the vehicle file as the built-in catalog. */
  void saveAdminRowChange(int module,String address,boolean deleted){
    if(!loaded[module]){saveAdminRows(module);return;}
    android.content.SharedPreferences prefs=getSharedPreferences("studio_admin_rows",MODE_PRIVATE);
    String saved=prefs.getString(modules[module],null);
    try{
      org.json.JSONArray source=new org.json.JSONArray(saved==null?"[]":saved);
      org.json.JSONArray result=new org.json.JSONArray();
      if(saved==null&&module==0){
        for(int n=0;n<defaults.length;n++){
          String a="720-"+(n<8?"01-"+String.format(Locale.US,"%02d",n+1):"02-01");
          org.json.JSONObject item=new org.json.JSONObject();
          item.put("address",a);item.put("value",defaults[n]);result.put(item);
        }
        source=result;result=new org.json.JSONArray();
      }
      for(int i=0;i<source.length();i++){
        org.json.JSONObject item=source.getJSONObject(i);
        if(!address.equals(item.getString("address")))result.put(item);
      }
      if(!deleted){
        AbtCodec.Row row=findRow(module,address);
        if(row!=null){
          org.json.JSONObject item=new org.json.JSONObject();
          item.put("address",row.address);item.put("value",row.value);result.put(item);
        }
      }
      prefs.edit().putString(modules[module],result.toString()).apply();
    }catch(org.json.JSONException ex){throw new IllegalStateException("Cannot persist admin row",ex);}
  }
  void restoreAdminRows(){
    android.content.SharedPreferences prefs=getSharedPreferences("studio_admin_rows",MODE_PRIVATE);
    for(int m=0;m<modules.length;m++){
      String saved=prefs.getString(modules[m],null);
      if(saved==null)continue;
      try{
        org.json.JSONArray array=new org.json.JSONArray(saved);
        ArrayList<AbtCodec.Row> restored=new ArrayList<>();
        for(int i=0;i<array.length();i++){
          org.json.JSONObject item=array.getJSONObject(i);
          String address=item.getString("address"),value=item.getString("value");
          String hex=AbtCodec.norm(value);
          if(!address.matches(ids[m]+"-[0-9]{2}-[0-9]{2}")||!hex.matches("[0-9A-F]{4,}")||hex.length()%2!=0)throw new IllegalArgumentException("Invalid saved row");
          for(AbtCodec.Row previous:restored)if(previous.address.equals(address))throw new IllegalArgumentException("Duplicate saved row "+address);
          restored.add(new AbtCodec.Row(modules[m],address,value,Integer.parseInt(address.split("-")[1])));
        }
        for(AbtCodec.Row old:abtRows[m])original.remove(key(old));
        abtRows[m].clear();abtRows[m].addAll(restored);
        for(AbtCodec.Row row:restored)original.put(key(row),AbtCodec.norm(row.value));
        refreshRows(m);syncFeatureChecks(m);
      }catch(Exception ex){android.util.Log.e("ASBuilt","Cannot restore admin rows for "+modules[m],ex);}
    }
  }
  org.json.JSONArray customModuleCatalog(){
    try{
      String local=getSharedPreferences("studio_custom_modules",MODE_PRIVATE).getString("catalog",null);
      if(local!=null)return new org.json.JSONArray(local);
      org.json.JSONArray published=PublishedFullSettings.modules(this);
      return published==null?new org.json.JSONArray():published;
    }catch(Exception ex){return new org.json.JSONArray();}
  }
  void showCustomModulePicker(){
    org.json.JSONArray catalog=customModuleCatalog();
    if(catalog.length()==0)return;
    String[] removeChoices=new String[catalog.length()];
    for(int i=0;i<catalog.length();i++){
      org.json.JSONObject item=catalog.optJSONObject(i);
      removeChoices[i]=item==null?"":item.optString("id")+" · "+item.optString("name");
    }
    new AlertDialog.Builder(this).setTitle("Дополнительные блоки").setItems(removeChoices,(d,index)->{
      org.json.JSONObject item=catalog.optJSONObject(index);
      if(item==null)return;
      showCustomRows(item);
    }).setNegativeButton("Закрыть",null).show();
  }

  boolean knownFeatureModule(String id){
    for(String factory:modules)if(factory.equals(id))return true;
    org.json.JSONArray catalog=customModuleCatalog();
    for(int i=0;i<catalog.length();i++){
      org.json.JSONObject item=catalog.optJSONObject(i);
      if(item!=null&&id.equalsIgnoreCase(item.optString("id")))return true;
    }
    return false;
  }
  final HashMap<String,FeatureEngine> customEngines=new HashMap<>();
  final HashMap<String,String> customRowBaseline=new HashMap<>();
  String customBaseline(String id,String address){
    String key=id+"|"+address;
    if(customRowBaseline.containsKey(key))return customRowBaseline.get(key);
    String saved=getSharedPreferences("studio_custom_baselines",MODE_PRIVATE).getString(key,null);
    if(saved!=null)customRowBaseline.put(key,saved);
    return saved;
  }
  void rememberCustomBaseline(String id,String address,String value){
    String key=id+"|"+address;
    if(customBaseline(id,address)!=null)return;
    customRowBaseline.put(key,value);
    getSharedPreferences("studio_custom_baselines",MODE_PRIVATE).edit().putString(key,value).apply();
  }
  void clearCustomBaselines(String id){
    android.content.SharedPreferences prefs=getSharedPreferences("studio_custom_baselines",MODE_PRIVATE);
    android.content.SharedPreferences.Editor editor=prefs.edit();
    for(String key:prefs.getAll().keySet())if(key.startsWith(id+"|"))editor.remove(key);
    editor.apply();
  }
  final HashMap<String,Boolean> customChecks=new HashMap<>();
  boolean customChecked(FeatureEngine.Feature feature){
    Boolean cached=customChecks.get(featureKey(feature));
    if(cached!=null)return cached;
    try{
      org.json.JSONArray entries=new org.json.JSONArray(getSharedPreferences("studio_custom_rows",MODE_PRIVATE).getString(feature.module,"[]"));
      for(int i=0;i<entries.length();i++){
        org.json.JSONObject item=entries.optJSONObject(i);
        if(item!=null&&feature.address.equalsIgnoreCase(item.optString("address"))){
          String stored=item.optString("value");
          boolean enabled=new FeatureEngine().state(feature,stored);
          customChecks.put(featureKey(feature),enabled);
          return enabled;
        }
      }
    }catch(Exception ignored){}
    return false;
  }
  boolean hasActiveCustomFeature(String id,String address){
    for(FeatureEngine.Feature feature:features)
      if(id.equalsIgnoreCase(feature.module)&&address.equalsIgnoreCase(feature.address)&&customChecked(feature))
        return true;
    return false;
  }
  void clearInactiveCustomBaseline(String id,String address){
    if(hasActiveCustomFeature(id,address))return;
    String key=id+"|"+address;
    customRowBaseline.remove(key);
    getSharedPreferences("studio_custom_baselines",MODE_PRIVATE).edit().remove(key).apply();
  }
  boolean customFeatureOverlap(FeatureEngine.Feature a,FeatureEngine.Feature b){
    if(!a.address.equalsIgnoreCase(b.address))return false;
    try{
      java.util.HashSet<Integer> positions=new java.util.HashSet<>();
      if("BITS".equalsIgnoreCase(a.mode)){
        for(int bit:FeatureEngine.indices(a.indices))positions.add(a.byteIndex*8+bit);
      }else{
        for(int hex:FeatureEngine.indices(a.indices))for(int bit=0;bit<4;bit++)positions.add(hex*4+bit);
      }
      if("BITS".equalsIgnoreCase(b.mode)){
        for(int bit:FeatureEngine.indices(b.indices))if(positions.contains(b.byteIndex*8+bit))return true;
      }else{
        for(int hex:FeatureEngine.indices(b.indices))for(int bit=0;bit<4;bit++)if(positions.contains(hex*4+bit))return true;
      }
    }catch(Exception ex){return true;}
    return false;
  }
  void toggleCustomFeature(int index){
    org.json.JSONObject module=customModuleCatalog().optJSONObject(active-4);
    if(module==null)return;
    String id=module.optString("id");
    ArrayList<FeatureEngine.Feature> available=new ArrayList<>();
    for(FeatureEngine.Feature f:features)if(id.equalsIgnoreCase(f.module))available.add(f);
    if(index<0||index>=available.size())return;
    FeatureEngine.Feature feature=available.get(index);
    android.content.SharedPreferences prefs=getSharedPreferences("studio_custom_rows",MODE_PRIVATE);
    try{
      org.json.JSONArray entries=new org.json.JSONArray(prefs.getString(id,"[]"));
      for(int i=0;i<entries.length();i++){
        org.json.JSONObject item=entries.optJSONObject(i);
        if(item==null||!feature.address.equalsIgnoreCase(item.optString("address")))continue;
        AbtCodec.Row row=new AbtCodec.Row(id,feature.address,item.optString("value"),Integer.parseInt(feature.address.split("-")[1]));
        FeatureEngine engine=customEngines.get(id);
        if(engine==null){
          engine=new FeatureEngine();
          customEngines.put(id,engine);
        }
        // Seed every feature from the current HEX row before applying an edit.
        // In particular, a second feature on the same row must retain the
        // first feature's state when the engine was created earlier.
        for(FeatureEngine.Feature sibling:available){
          if(!sibling.address.equalsIgnoreCase(feature.address))continue;
          boolean enabled=customChecked(sibling);
          engine.seed(sibling,enabled);
        }
        boolean next=!customChecked(feature);
        if(next){
          for(FeatureEngine.Feature sibling:available){
            if(sibling==feature||!customChecked(sibling))continue;
            if(customFeatureOverlap(feature,sibling)){
              Toast.makeText(this,"Конфликт с активной функцией: "+sibling.label,Toast.LENGTH_LONG).show();
              return;
            }
          }
        }
        String before=row.value;
        // Compute first. Do not commit checkbox state or baseline if HEX/BITS fails.
        try{
          engine.apply(row,feature,next,features);
          item.put("value",row.value);
          boolean saved=prefs.edit().putString(id,entries.toString()).commit();
          if(!saved)throw new IllegalStateException("Не удалось сохранить HEX-строку");
        }catch(Exception editFailure){
          // A failed apply may have mutated the engine's internal state.
          // Rebuild it from the last persisted HEX on the next attempt.
          customEngines.remove(id);
          customChecks.clear();
          throw editFailure;
        }
        String after=row.value;
        StringBuilder changes=new StringBuilder();
        // Match the built-in blocks: report changed data HEX digits, excluding checksum.
        int dataEnd=Math.max(0,Math.min(before.length(),after.length())-2);
        for(int k=0;k<dataEnd;k++){
          char from=Character.toUpperCase(before.charAt(k));
          char to=Character.toUpperCase(after.charAt(k));
          if(from!=to){
            if(changes.length()>0)changes.append(", ");
            changes.append(from).append(" на ").append(to);
          }
        }
        boolean anyChecked=next;
        if(!anyChecked)for(FeatureEngine.Feature sibling:available){
          if(sibling!=feature&&customChecked(sibling)){anyChecked=true;break;}
        }
        // Keep the most recent toggle event visible even after the final checkbox is cleared.
        // The IC/BCM/RKE/ABS status line likewise reports the reverse HEX transition.
        customFeatureStatuses.put(id,feature.label+": "+(next?"активирована":"деактивирована"));
        rememberCustomBaseline(id,feature.address,before);
        customChecks.put(featureKey(feature),next);
        if(!next)clearInactiveCustomBaseline(id,feature.address);
        view.invalidate();return;
      }
      org.json.JSONArray templates=PublishedFullSettings.customRowTemplates(this,id);
      if(templates!=null)for(int i=0;i<templates.length();i++){
        org.json.JSONObject template=templates.optJSONObject(i);
        if(template==null||!feature.address.equalsIgnoreCase(template.optString("address")))continue;
        // Explicit checkbox action creates a local editable row automatically.
        // Signed online templates remain immutable; never touch customer ABT.
        String value=template.optString("value");
        String hex=AbtCodec.norm(value);
        if(!hex.matches("[0-9A-F]{4,}")||hex.length()%2!=0)
          throw new IllegalArgumentException("Некорректное HEX-значение шаблона");
        org.json.JSONArray current=new org.json.JSONArray(prefs.getString(id,"[]"));
        boolean exists=false;
        for(int j=0;j<current.length();j++){
          org.json.JSONObject existing=current.optJSONObject(j);
          if(existing!=null&&feature.address.equalsIgnoreCase(existing.optString("address"))){exists=true;break;}
        }
        if(!exists){
          org.json.JSONObject copy=new org.json.JSONObject();
          copy.put("address",feature.address);
          copy.put("value",value);
          current.put(copy);
          if(!prefs.edit().putString(id,current.toString()).commit())
            throw new IllegalStateException("Не удалось сохранить рабочую копию");
        }
        toggleCustomFeature(index);
        return;
      }
      Toast.makeText(this,"Строка "+feature.address+" не найдена в рабочих данных блока "+id,Toast.LENGTH_LONG).show();
    }catch(Exception ex){
      String reason=ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage();
      Toast.makeText(this,"Ошибка "+id+" · "+feature.label+" · "+feature.address+": "+reason,Toast.LENGTH_LONG).show();
    }
  }
  final HashMap<String,Integer> customScroll=new HashMap<>();
  final HashMap<String,Integer> customFeaturePage=new HashMap<>();
  String pendingCustomId="";
  void customAbtPicker(boolean save,String id){
    pendingCustomId=id;
    Intent intent=new Intent(save?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT);
    intent.setType("*/*");intent.addCategory(Intent.CATEGORY_OPENABLE);
    if(save)intent.putExtra(Intent.EXTRA_TITLE,id+".abt");
    if(save){
      try{
        org.json.JSONArray entries=new org.json.JSONArray(getSharedPreferences("studio_custom_rows",MODE_PRIVATE).getString(id,"[]"));
        int invalid=0;
        for(int j=0;j<entries.length();j++){
          org.json.JSONObject row=entries.optJSONObject(j);
          if(row!=null&&!AbtCodec.checksumValid(row.optString("address"),row.optString("value")))invalid++;
        }
        if(invalid>0){
          final int count=invalid;
          new AlertDialog.Builder(this).setTitle("Проверка ABT перед сохранением")
            .setMessage("Найдены несовпадающие контрольные суммы: "+count+". Исходные HEX-значения будут сохранены без исправления. Продолжить?")
            .setNegativeButton("Отмена",(d,w)->pendingCustomId="")
            .setPositiveButton("Продолжить",(d,w)->startActivityForResult(intent,13)).show();
          return;
        }
      }catch(Exception ex){Toast.makeText(this,"Ошибка проверки ABT: "+ex.getMessage(),Toast.LENGTH_LONG).show();pendingCustomId="";return;}
    }
    startActivityForResult(intent,save?13:12);
  }
  void handleCustomAbt(int request,Uri uri)throws Exception{
    String id=pendingCustomId;pendingCustomId="";
    if(request==12&&handleAdminKey(uri))return;
    org.json.JSONObject module=null;
    org.json.JSONArray catalog=customModuleCatalog();
    for(int i=0;i<catalog.length();i++){
      org.json.JSONObject item=catalog.optJSONObject(i);
      if(item!=null&&id.equals(item.optString("id")))module=item;
    }
    if(module==null)throw new IllegalArgumentException("Дополнительный блок не найден");
    String prefix=module.optString("address").toUpperCase(java.util.Locale.US);
    android.content.SharedPreferences prefs=getSharedPreferences("studio_custom_rows",MODE_PRIVATE);
    if(request==12){
      ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] chunk=new byte[4096];int n;
      try(InputStream in=getContentResolver().openInputStream(uri)){
        if(in==null)throw new IOException("Файл недоступен");
        while((n=in.read(chunk))!=-1){if(buffer.size()+n>4*1024*1024)throw new IOException("ABT слишком большой");buffer.write(chunk,0,n);}
      }
      java.util.HashMap<String,String> mapping=new java.util.HashMap<>();mapping.put(prefix,id);
      java.util.List<AbtCodec.Row> parsed=AbtCodec.parseChecked(new String(buffer.toByteArray(),"UTF-8"),mapping);
      java.util.ArrayList<AbtCodec.Row> rows=AbtCodec.requireModule(parsed,id);
      java.util.HashSet<String> importedAddresses=new java.util.HashSet<>();
      for(AbtCodec.Row row:rows){
        if(!row.address.toUpperCase(java.util.Locale.US).matches(java.util.regex.Pattern.quote(prefix)+"-[0-9]{2}-[0-9]{2}"))
          throw new IllegalArgumentException("Адрес "+row.address+" не принадлежит блоку "+id);
        String hex=AbtCodec.norm(row.value);
        if(hex.length()<4||hex.length()%2!=0||!hex.matches("[0-9A-F]+"))
          throw new IllegalArgumentException("Повреждённое HEX-значение строки "+row.address);
        if(!importedAddresses.add(row.address.toUpperCase(java.util.Locale.US)))
          throw new IllegalArgumentException("Повтор адреса "+row.address+" в ABT. Импорт отменён.");
      }
      org.json.JSONArray entries=new org.json.JSONArray();
      for(AbtCodec.Row row:rows){
        org.json.JSONObject item=new org.json.JSONObject();
        item.put("address",row.address);item.put("value",row.value);entries.put(item);
      }
      prefs.edit().putString(id,entries.toString()).apply();
      customFeatureStatuses.remove(id);
      // A custom block is marked as loaded only after a successful ABT import.
      getSharedPreferences("studio_custom_abt_loaded",MODE_PRIVATE).edit().putBoolean(id,true).apply();
      resetCustomFeatureState(id);view.invalidate();
      int invalid=0;
      for(AbtCodec.Row row:rows)if(!AbtCodec.checksumValid(row.address,row.value))invalid++;
      if(invalid>0)new AlertDialog.Builder(this).setTitle("Проверка контрольных сумм")
        .setMessage("Импортировано строк: "+rows.size()+". У "+invalid+" строк контрольная сумма не совпадает. Исходные значения сохранены без исправления.")
        .setPositiveButton("Понятно",null).show();
      else Toast.makeText(this,"Импортировано строк: "+rows.size()+". Контрольные суммы верны.",Toast.LENGTH_LONG).show();
    }else{
      org.json.JSONArray entries=new org.json.JSONArray(prefs.getString(id,"[]"));
      java.util.ArrayList<AbtCodec.Row> rows=new java.util.ArrayList<>();
      java.util.HashSet<String> exportAddresses=new java.util.HashSet<>();
      for(int i=0;i<entries.length();i++){
        org.json.JSONObject item=entries.optJSONObject(i);
        if(item==null)throw new IllegalArgumentException("Повреждена строка №"+(i+1));
        String address=item.optString("address").toUpperCase(java.util.Locale.US);
        String hex=AbtCodec.norm(item.optString("value"));
        if(!address.matches(java.util.regex.Pattern.quote(prefix)+"-[0-9]{2}-[0-9]{2}"))
          throw new IllegalArgumentException("Адрес "+address+" не соответствует блоку");
        if(!exportAddresses.add(address))
          throw new IllegalArgumentException("Повтор адреса "+address+" в сохраняемом блоке");
        if(hex.length()<4||hex.length()%2!=0||!hex.matches("[0-9A-F]+"))
          throw new IllegalArgumentException("Повреждённое HEX-значение "+address);
        rows.add(new AbtCodec.Row(id,address,hex,Integer.parseInt(address.split("-")[1])));
      }
      if(rows.isEmpty())throw new IllegalArgumentException("Нет строк для сохранения");
      String content=AbtCodec.write(rows,id);
      byte[] payload=content.getBytes("UTF-8");
      if(payload.length==0)throw new IOException("Сформирован пустой ABT-файл");
      // Validate the complete payload before opening the destination for writing.
      java.util.HashMap<String,String> verifyMapping=new java.util.HashMap<>();
      verifyMapping.put(prefix,id);
      java.util.List<AbtCodec.Row> verified=AbtCodec.requireModule(
        AbtCodec.parseChecked(content,verifyMapping),id);
      if(verified.size()!=rows.size())throw new IOException("Проверка сформированного ABT не пройдена");
      try(java.io.OutputStream out=getContentResolver().openOutputStream(uri)){
        if(out==null)throw new IOException("Файл недоступен");
        out.write(payload);
        out.flush();
      }
      // Reopen the document to detect truncated or corrupted writes where supported.
      try(InputStream verifyIn=getContentResolver().openInputStream(uri)){
        if(verifyIn==null)throw new IOException("Не удалось проверить записанный ABT");
        byte[] checkBuffer=new byte[4096];
        int read,offset=0;
        while((read=verifyIn.read(checkBuffer))!=-1){
          if(offset+read>payload.length)throw new IOException("Размер записанного ABT отличается от ожидаемого");
          for(int j=0;j<read;j++)if(checkBuffer[j]!=payload[offset+j])
            throw new IOException("Записанный ABT отличается от подготовленного файла");
          offset+=read;
        }
        if(offset!=payload.length)throw new IOException("ABT записан не полностью: "+offset+" из "+payload.length+" байт");
      }
      Toast.makeText(this,"Сохранено и проверено строк: "+rows.size(),Toast.LENGTH_LONG).show();
    }
  }
  void showCustomRows(org.json.JSONObject module){
    final String id=module.optString("id"), address=module.optString("address");
    android.content.SharedPreferences prefs=getSharedPreferences("studio_custom_rows",MODE_PRIVATE);
    org.json.JSONArray stored;
    try{stored=new org.json.JSONArray(prefs.getString(id,"[]"));}catch(Exception ex){stored=new org.json.JSONArray();}
    final org.json.JSONArray entries=stored;
    LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(dp(12),dp(8),dp(12),dp(8));
    TextView info=new TextView(this);info.setText("Адрес: "+address+"\nРедактирование и импорт/экспорт ABT");
    layout.addView(info);
    // Published row templates are read-only. Never copy them into a customer's
    // editable ABT rows: doing so could silently replace vehicle-specific HEX.
    org.json.JSONArray publishedTemplates=PublishedFullSettings.customRowTemplates(this,id);
    if(publishedTemplates!=null && publishedTemplates.length()>0){
      for(int p=0;p<publishedTemplates.length();p++){
        org.json.JSONObject template=publishedTemplates.optJSONObject(p);
        if(template==null)continue;
        TextView templateRow=new TextView(this);
        templateRow.setText(template.optString("address")+"    "+template.optString("value"));
        templateRow.setPadding(dp(8),dp(3),dp(8),dp(3));
        layout.addView(templateRow);
      }
    }
    ScrollView scroll=new ScrollView(this);LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(list);layout.addView(scroll,new LinearLayout.LayoutParams(-1,dp(150)));
    Runnable[] refresh={null};
    refresh[0]=()->{
      list.removeAllViews();
      for(int i=0;i<entries.length();i++){
        final int index=i;org.json.JSONObject row=entries.optJSONObject(i);if(row==null)continue;
        Button entry=new Button(this);entry.setAllCaps(false);entry.setText(row.optString("address")+"    "+row.optString("value"));
        list.addView(entry);entry.setOnClickListener(v->editCustomRow(id,address,entries,index,prefs,refresh[0]));
      }
    };
    refresh[0].run();
    Button add=new Button(this);add.setText("Добавить строку");
    layout.addView(add);add.setOnClickListener(v->editCustomRow(id,address,entries,-1,prefs,refresh[0]));
    Button importAbt=new Button(this);importAbt.setText("Открыть ABT");
    layout.addView(importAbt);importAbt.setOnClickListener(v->customAbtPicker(false,id));
    Button exportAbt=new Button(this);exportAbt.setText("Сохранить ABT");
    layout.addView(exportAbt);exportAbt.setOnClickListener(v->customAbtPicker(true,id));
    new AlertDialog.Builder(this).setTitle(id+" · "+module.optString("name")).setView(layout).setPositiveButton("Закрыть",null).show();
  }
  void restoreSelectedModule(){
    android.content.SharedPreferences prefs=getSharedPreferences("studio_custom_positions",MODE_PRIVATE);
    String saved=prefs.getString("selected_module","IC");
    for(int i=0;i<modules.length;i++)if(modules[i].equals(saved)){active=i;return;}
    org.json.JSONArray catalog=customModuleCatalog();
    for(int i=0;i<catalog.length();i++){
      org.json.JSONObject module=catalog.optJSONObject(i);
      if(module!=null&&saved.equals(module.optString("id"))){active=i+4;return;}
    }
    active=0;
  }
  void saveSelectedModule(){
    String id=active<4?modules[active]:"";
    if(active>=4){
      org.json.JSONObject module=customModuleCatalog().optJSONObject(active-4);
      if(module!=null)id=module.optString("id");
    }
    if(!id.isEmpty())getSharedPreferences("studio_custom_positions",MODE_PRIVATE).edit().putString("selected_module",id).apply();
  }
  void restoreCustomTabPositions(){
    android.content.SharedPreferences prefs=getSharedPreferences("studio_custom_positions",MODE_PRIVATE);
    org.json.JSONArray catalog=customModuleCatalog();
    for(int i=0;i<catalog.length();i++){
      org.json.JSONObject module=catalog.optJSONObject(i);
      if(module==null)continue;
      String id=module.optString("id");
      customScroll.put(id,Math.max(0,prefs.getInt(id+"_scroll",0)));
      customFeaturePage.put(id,Math.max(0,prefs.getInt(id+"_features",0)));
    }
  }
  void saveCustomTabPosition(String id){
    getSharedPreferences("studio_custom_positions",MODE_PRIVATE).edit()
      .putInt(id+"_scroll",customScroll.containsKey(id)?customScroll.get(id):0)
      .putInt(id+"_features",customFeaturePage.containsKey(id)?customFeaturePage.get(id):0).apply();
  }
  void resetCustomFeatureState(String id){
    customEngines.remove(id);
    clearCustomBaselines(id);
    try{
      org.json.JSONArray rows=new org.json.JSONArray(getSharedPreferences("studio_custom_rows",MODE_PRIVATE).getString(id,"[]"));
      int offset=customScroll.containsKey(id)?customScroll.get(id):0;
      customScroll.put(id,Math.max(0,Math.min(offset,Math.max(0,rows.length()-9))));
    }catch(Exception ignored){customScroll.put(id,0);}
    saveCustomTabPosition(id);
    java.util.ArrayList<String> keys=new java.util.ArrayList<>(customChecks.keySet());
    for(String key:keys)if(key.startsWith(id+"|"))customChecks.remove(key);
  }
  void editCustomRow(String id,String prefix,org.json.JSONArray entries,int index,android.content.SharedPreferences prefs,Runnable refresh){
    org.json.JSONObject current=index>=0?entries.optJSONObject(index):null;
    LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(18),dp(8),dp(18),dp(8));
    EditText address=new EditText(this);address.setSingleLine(true);address.setHint(prefix+"-01-01");
    address.setText(current==null?prefix+"-01-01":current.optString("address"));form.addView(address);
    EditText value=new EditText(this);value.setSingleLine(true);value.setHint("HEX значение с checksum");
    value.setText(current==null?"":current.optString("value"));form.addView(value);
    AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle(index<0?"Добавить строку":"Изменить строку").setView(form)
      .setNegativeButton("Отмена",null);
    if(index>=0)builder.setNeutralButton("Удалить",(d,w)->{
      org.json.JSONArray updated=new org.json.JSONArray();
      for(int i=0;i<entries.length();i++)if(i!=index)updated.put(entries.optJSONObject(i));
      prefs.edit().putString(id,updated.toString()).apply();
      resetCustomFeatureState(id);
      if(view!=null)view.invalidate();
      while(entries.length()>0)entries.remove(0);
      for(int i=0;i<updated.length();i++)entries.put(updated.optJSONObject(i));
      refresh.run();
    });
    builder.setPositiveButton("Сохранить",null);
    AlertDialog dialog=builder.create();dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
      try{
        String addr=address.getText().toString().trim().toUpperCase(Locale.US);
        String hex=value.getText().toString().replaceAll("\\s+","").toUpperCase(Locale.US);
        if(!addr.matches(java.util.regex.Pattern.quote(prefix)+"-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException("Неверный адрес блока");
        if(!hex.matches("[0-9A-F]{4,}")||hex.length()%2!=0)throw new IllegalArgumentException("Неверное HEX значение");
        for(int i=0;i<entries.length();i++)if(i!=index&&addr.equals(entries.optJSONObject(i).optString("address")))throw new IllegalArgumentException("Строка уже существует");
        org.json.JSONObject row=new org.json.JSONObject();row.put("address",addr);row.put("value",AbtCodec.recalc(addr,hex));
        if(index<0)entries.put(row);else entries.put(index,row);
        prefs.edit().putString(id,entries.toString()).apply();resetCustomFeatureState(id);refresh.run();if(view!=null)view.invalidate();dialog.dismiss();
      }catch(Exception ex){Toast.makeText(this,"Ошибка: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
    }));dialog.show();
  }
  int appearanceColor(String key,int fallback){
    String value=StudioSettings.appearance(this,key,"").trim();
    if(value.isEmpty())return fallback;
    try{
      if(value.matches("(?i)[0-9a-f]{6}"))value="#"+value;
      return Color.parseColor(value);
    }catch(IllegalArgumentException ex){return fallback;}
  }
  int featureColumns(){
    try{return Math.max(1,Math.min(10,Integer.parseInt(StudioSettings.appearance(this,"feature_columns","3").trim())));}
    catch(Exception ignored){return 3;}
  }
  float featureDimension(String key,int fallback,int min,int max){try{return Math.max(min,Math.min(max,Integer.parseInt(StudioSettings.appearance(this,key,""+fallback).trim())));}catch(Exception ignored){return fallback;}}
  int featureSlots(){return 10;} // Two columns, five rows per page
  float featureCellWidth(){return 119f;}
  float featureX(int index){return 23f+(index/3)*122f;}
  float featureY(int index){return 285f+(index%3)*28f;}
  String lastFeatureStatus="Изменений нет";
  final HashMap<String,String> customFeatureStatuses=new HashMap<>();
  class StudioView extends View {
    Paint p=new Paint(3); HashMap<String,Bitmap> bitmaps=new HashMap<>();
    float sx=1,sy=1,offX=0,offY=0;
    StudioView(){super(MainActivity.this);setBackgroundColor(Color.BLACK);
      String[] keys={"header_logo","header_mazda_no_lock","admin_lock_button","active_red","inactive_1","module_info","features_panel","row_01","checkbox_empty","checkbox_checked","open_abt","save_abt","creator_link","settings","gauge_round","feature_left","feature_right","admin_dialog","about_dialog"};
      for(String key:keys){int id=getResources().getIdentifier(key,"drawable",getPackageName());if(id!=0)bitmaps.put(key,BitmapFactory.decodeResource(getResources(),id));}
    }
    void img(Canvas c,String key,float x,float y,float w,float h){Bitmap b=bitmaps.get(key);if(b!=null){p.setColor(Color.WHITE);p.setAlpha(255);c.drawBitmap(b,null,new RectF(x,y,x+w,y+h),p);}}
    void rect(Canvas c,int color,float x,float y,float w,float h,float r){p.setColor(color);p.setStyle(Paint.Style.FILL);c.drawRoundRect(x,y,x+w,y+h,r,r,p);}
    void txt(Canvas c,String s,float x,float y,float size,int color,boolean bold){p.setColor(color);p.setTypeface(bold?Typeface.create("sans-serif",Typeface.BOLD):Typeface.create("sans-serif",Typeface.NORMAL));p.setTextSize(size);p.setStyle(Paint.Style.FILL);c.drawText(s,x,y,p);}
    void txtFitBold(Canvas c,String value,float x,float y,float size,int color,float maxWidth){
      String display=value==null?"":value;
      p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));p.setTextSize(size);
      if(p.measureText(display)>maxWidth){
        while(!display.isEmpty()&&p.measureText(display+"…")>maxWidth)display=display.substring(0,display.length()-1);
        display+="…";
      }
      txt(c,display,x,y,size,color,true);
    }
    void txtFit(Canvas c,String value,float x,float y,float size,int color,float maxWidth){
      String display=value==null?"":value;
      p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
      p.setTextSize(size);
      if(p.measureText(display)>maxWidth){
        String ellipsis="…";
        while(!display.isEmpty()&&p.measureText(display+ellipsis)>maxWidth)display=display.substring(0,display.length()-1);
        display+=ellipsis;
      }
      txt(c,display,x,y,size,color,false);
    }
    void cleanButton(Canvas c,float x,float y,float w,float h,String label){
      p.setStyle(Paint.Style.FILL);p.setShader(new LinearGradient(x,y,x,y+h,new int[]{Color.WHITE,Color.rgb(247,248,250),Color.rgb(218,223,229)},null,Shader.TileMode.CLAMP));
      c.drawRoundRect(x,y,x+w,y+h,9,9,p);p.setShader(null);
      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.6f);p.setColor(Color.rgb(179,187,196));
      c.drawRoundRect(x+1,y+1,x+w-1,y+h-1,9,9,p);p.setStyle(Paint.Style.FILL);
      p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));p.setTextSize(15);p.setColor(Color.BLACK);
      c.drawText(label,x+(w-p.measureText(label))/2f,y+h/2f-(p.ascent()+p.descent())/2f,p);
    }
    void card(Canvas c,float x,float y,float w,float h,float radius,boolean red){
      p.setStyle(Paint.Style.FILL);p.setShader(null);p.setColor(Color.rgb(85,87,92));c.drawRoundRect(x+1,y+3,x+w+1,y+h+3,radius,radius,p);
      p.setShader(new LinearGradient(x,y,x,y+h,Color.WHITE,Color.rgb(231,235,242),Shader.TileMode.CLAMP));
      c.drawRoundRect(x,y,x+w,y+h,radius,radius,p);p.setShader(null);
      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(red?1.25f:1.15f);p.setColor(red?Color.rgb(225,40,48):Color.rgb(195,202,211));
      c.drawRoundRect(x+1,y+1,x+w-1,y+h-1,radius,radius,p);p.setStyle(Paint.Style.FILL);
    }
    void centered(Canvas c,String s,float x,float y,float w,float h,float size,int color){
      p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));p.setTextSize(size);p.setColor(color);p.setStyle(Paint.Style.FILL);
      c.drawText(s,x+(w-p.measureText(s))/2,y+h/2-(p.ascent()+p.descent())/2,p);
    }
    // Post 476 #10: options-only middle panel; never render addresses or HEX.
    void drawFeatureCards(Canvas c,ArrayList<FeatureEngine.Feature> items,boolean enabled,int page){
      card(c,10,279,380,434,13,true);
      txt(c,"ОПЦИИ БЛОКА  •  "+items.size(),20,308,15,Color.BLACK,true);
      txtFit(c,enabled?"ABT загружен — выберите функции":
          "Загрузите ABT, чтобы включить функции",20,332,12,
          enabled?Color.rgb(64,94,74):Color.DKGRAY,355);
      int slots=10;
      if(page<0||page>=items.size())page=0;
      for(int i=0;i<slots&&page+i<items.size();i++){
        FeatureEngine.Feature feature=items.get(page+i);
        float x=18+(i%2)*187f,y=350+(i/2)*65f;
        float fw=featureDimension("feature_card_width",177,100,180);
        float fh=featureDimension("feature_card_height",57,32,63);
        card(c,x,y,fw,fh,10,false);
        float bx=x+12,by=y+(fh-23)/2;
        card(c,bx,by,23,23,4,false);
        boolean checked=enabled&&(active>=4?customChecked(feature):isChecked(feature));
        if(checked){
          rect(c,Color.rgb(210,28,37),bx+4,by+4,15,15,2);
          p.setColor(Color.WHITE);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);
          Path mark=new Path();mark.moveTo(bx+5,by+12);mark.lineTo(bx+10,by+17);mark.lineTo(bx+19,by+6);
          c.drawPath(mark,p);p.setStyle(Paint.Style.FILL);
        }
        txtFit(c,feature.label,bx+29,y+fh/2+5,12,enabled?Color.BLACK:Color.GRAY,Math.max(30,fw-51));
      }
      if(items.size()>slots)txt(c,"Следующие функции  ›",225,698,12,Color.rgb(150,0,0),true);
    }
    ArrayList<FeatureEngine.Feature> customFeatures(int module){
      ArrayList<FeatureEngine.Feature> result=new ArrayList<>();
      org.json.JSONObject entry=customModuleCatalog().optJSONObject(module-4);
      if(entry!=null)for(FeatureEngine.Feature f:features)
        if(entry.optString("id").equalsIgnoreCase(f.module))result.add(f);
      return result;
    }
    @Override protected void onDraw(Canvas actual){super.onDraw(actual);
      sx=getWidth()/400f;sy=getHeight()/860f;actual.save();actual.scale(sx,sy);
      Canvas c=actual;c.drawColor(appearanceColor("background",Color.BLACK));
      img(c,"header_mazda_no_lock",8,4,384,94);
      if(adminSession){card(c,351,12,33,34,9,false);
      // Centered lock drawn as geometry: no emoji font baseline or glyph offsets.
      p.setShader(null);p.setColor(Color.rgb(35,40,48));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.8f);
      c.drawRoundRect(361.5f,18.5f,373.5f,34.5f,6,6,p);
      p.setStyle(Paint.Style.FILL);c.drawRoundRect(359.5f,27,375.5f,40,2.5f,2.5f,p);
      p.setColor(Color.WHITE);c.drawCircle(367.5f,32,1.3f,p);c.drawRect(366.8f,32,368.2f,36,p);}
      org.json.JSONArray customTabs=customModuleCatalog();
      int tabCount=visibleTabCount();
      float tabWidth=370f/Math.max(1,tabCount);
      card(c,10,125,380,57,13,false);
      c.save();c.clipRect(15,129,385,179);
      for(int i=0;i<tabCount;i++){
        float x=15+i*tabWidth;
        if(x+tabWidth<15||x>385)continue;
        int tabModule=moduleAtTabIndex(i);
        String tab=tabModule<4?modules[tabModule]:customTabs.optJSONObject(tabModule-4)==null?"?":customTabs.optJSONObject(tabModule-4).optString("id","?");
        if(tabModule==active){p.setShader(new LinearGradient(x,132,x,175,Color.rgb(255,74,79),Color.rgb(176,0,10),Shader.TileMode.CLAMP));p.setStyle(Paint.Style.FILL);c.drawRoundRect(x,131,x+tabWidth-3,175,9,9,p);p.setShader(null);}
        else card(c,x,131,tabWidth-3,44,9,false);
        centered(c,tab,x,131,tabWidth-3,44,Math.max(7f,Math.min(13f,65f/Math.max(4,tabCount))),tabModule==active?Color.WHITE:Color.BLACK);
      }
      c.restore();
      if(active>=4){
        org.json.JSONObject module=customTabs.optJSONObject(active-4);
        if(module==null){active=0;actual.restore();return;}
        String id=module.optString("id"), prefix=module.optString("address");
        card(c,10,190,380,82,13,true);
        txtFitBold(c,id+": "+module.optString("name"),25,222,18,Color.BLACK,350);
        p.setColor(Color.rgb(224,57,64));p.setStrokeWidth(1);c.drawLine(25,232,316,232,p);
        String customVersion=getSharedPreferences("studio_custom_abt_loaded",MODE_PRIVATE).getBoolean(id,false)
          ?"ABT загружен":"Образец";
        txt(c,"ID: "+prefix+"  |  Ver: "+customVersion,25,252,12,Color.rgb(91,103,119),false);
      drawFeatureCards(c,active>=4 ? customFeatures(active) : moduleFeatures(active),
          active>=4 ? getSharedPreferences("studio_custom_abt_loaded",MODE_PRIVATE).getBoolean(
              customModuleCatalog().optJSONObject(active-4).optString("id"),false) : loaded[active],
          active>=4 ? customFeaturePage.getOrDefault(
              customModuleCatalog().optJSONObject(active-4).optString("id"),0) : featurePage[active]);
        card(c,10,720,185,52,12,true);centered(c,StudioSettings.appearance(MainActivity.this,"open_text","Открыть ABT"),10,720,185,52,15,Color.BLACK);
        card(c,205,720,185,52,12,true);centered(c,StudioSettings.appearance(MainActivity.this,"save_text","Сохранить ABT"),205,720,185,52,15,Color.BLACK);
        card(c,10,782,260,57,12,true);
        String customStatus=customFeatureStatuses.containsKey(id)?customFeatureStatuses.get(id):"Изменений нет";
        txtFitBold(c,customStatus,22,816,14,Color.BLACK,235);
        // One shared bottom-bar FAQ button, next to the existing About button.
        card(c,277,782,54,57,12,true);
        centered(c,"FAQ",277,782,54,57,14,Color.BLACK);
        card(c,336,782,54,57,12,true);centered(c,"ⓘ",336,782,54,57,23,Color.rgb(70,75,82));
        actual.restore();return;
      }
      card(c,10,190,380,82,13,true);
      txt(c,modules[active]+": "+StudioSettings.moduleName(MainActivity.this,modules[active],names[active]),25,222,18,Color.BLACK,true);
      p.setColor(Color.rgb(224,57,64));p.setStrokeWidth(1);c.drawLine(25,232,316,232,p);
      String configuredVersion=StudioSettings.moduleVersion(MainActivity.this,modules[active]);
      txt(c,"ID: "+ids[active]+"  |  Ver: "+(!configuredVersion.isEmpty()?configuredVersion:(loaded[active]?"ABT загружен":"Образец")),25,252,12,Color.rgb(91,103,119),false);
      drawFeatureCards(c,active>=4 ? customFeatures(active) : moduleFeatures(active),
          active>=4 ? getSharedPreferences("studio_custom_abt_loaded",MODE_PRIVATE).getBoolean(
              customModuleCatalog().optJSONObject(active-4).optString("id"),false) : loaded[active],
          active>=4 ? customFeaturePage.getOrDefault(
              customModuleCatalog().optJSONObject(active-4).optString("id"),0) : featurePage[active]);
      card(c,10,720,185,52,12,true);centered(c,StudioSettings.appearance(MainActivity.this,"open_text","Открыть ABT"),10,720,185,52,15,Color.BLACK);
      card(c,205,720,185,52,12,true);centered(c,StudioSettings.appearance(MainActivity.this,"save_text","Сохранить ABT"),205,720,185,52,15,Color.BLACK);
      card(c,10,782,260,57,12,true);
      String status=lastFeatureStatus;
      p.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));p.setTextSize(14);p.setColor(Color.BLACK);
      final float maxStatusWidth=235f;
      if(p.measureText(status)<=maxStatusWidth){
        c.drawText(status,22,816,p);
      }else{
        int split=0;
        for(int i=1;i<status.length();i++){
          if(p.measureText(status.substring(0,i))>maxStatusWidth)break;
          split=i;
        }
        int boundary=status.lastIndexOf(' ',split);
        if(boundary>Math.max(5,split/2))split=boundary;
        String first=status.substring(0,split).trim();
        String second=status.substring(split).trim();
        while(second.length()>0&&p.measureText(second)>maxStatusWidth)second=second.substring(0,second.length()-1);
        if(second.length()<status.substring(split).trim().length()&&second.length()>1)second=second.substring(0,second.length()-1)+"…";
        c.drawText(first,22,806,p);
        c.drawText(second,22,825,p);
      }
      card(c,277,782,54,57,12,true);
      centered(c,"FAQ",277,782,54,57,14,Color.BLACK);
      card(c,336,782,54,57,12,true);
      centered(c,"ⓘ",336,782,54,57,23,Color.rgb(70,75,82));
      actual.restore();
    }
    float startY,startX,moduleTabOffset=0;
    void revealActiveTab(){moduleTabOffset=0;}
    @Override public boolean onTouchEvent(MotionEvent e){if(e.getAction()==MotionEvent.ACTION_DOWN){startY=e.getY()/sy;startX=e.getX()/sx;return true;}if(e.getAction()!=MotionEvent.ACTION_UP)return true;float x=e.getX()/sx,y=e.getY()/sy;
      // Feature cards use taps, not the former HEX-row swipe scrolling.
      if(startY>=350&&startY<=713&&Math.abs(y-startY)>18)return true;
      if(y>=127&&y<=183){
        int count=visibleTabCount();
        float width=370f/Math.max(1,count);
        if(x<15||x>385)return true;
        int selected=(int)((x-15)/width);
        if(selected<0||selected>=count)return true;
        selected=moduleAtTabIndex(selected);
        if(selected>=4&&selected<count){
          org.json.JSONObject item=customModuleCatalog().optJSONObject(selected-4);
          active=selected;saveSelectedModule();revealActiveTab();invalidate();
          return true;
        }
        if(selected>=0&&selected<modules.length){active=selected;saveSelectedModule();invalidate();}
        return true;
      }
      if(adminSession&&x>=346&&y>=8&&y<=70){admin();return true;}
      if(y>=350&&y<=713){
        boolean ready=active>=4?getSharedPreferences("studio_custom_abt_loaded",MODE_PRIVATE)
          .getBoolean(customModuleCatalog().optJSONObject(active-4).optString("id"),false):loaded[active];
        if(!ready){Toast.makeText(MainActivity.this,"Сначала откройте ABT этого блока",Toast.LENGTH_SHORT).show();return true;}
        if(y>=676){
          if(active>=4){
            String id=customModuleCatalog().optJSONObject(active-4).optString("id");
            int count=customFeatures(active).size(),old=customFeaturePage.getOrDefault(id,0);
            customFeaturePage.put(id,old+10>=count?0:old+10);saveCustomTabPosition(id);
          }else{
            int count=moduleFeatures(active).size();
            featurePage[active]=featurePage[active]+10>=count?0:featurePage[active]+10;
          }
          invalidate();return true;
        }
        int col=x>=205?1:0,row=(int)((y-350)/65f);
        if(row>=0&&row<5){
          int slot=row*2+col;
          if(active>=4){
            String id=customModuleCatalog().optJSONObject(active-4).optString("id");
            toggleCustomFeature(customFeaturePage.getOrDefault(id,0)+slot);
          }else toggleFeature(featurePage[active]+slot);
        }
        return true;
      }
      if(active>=4){
        if(y>=717&&y<=777){
          org.json.JSONObject item=customModuleCatalog().optJSONObject(active-4);
          if(item!=null)customAbtPicker(x>=200,item.optString("id"));
          return true;
        }
        if(y>=779&&x>=277&&x<333){StudioFaq.show(MainActivity.this);return true;}
        if(y>=779&&x>=336){about();return true;}
        return true;
      }
      if(y>=717&&y<=777){if(x<200)open();else save();return true;}
      if(y>=779){if(x>=336)about();else if(x>=277)StudioFaq.show(MainActivity.this);return true;}
      return true;
    }
  }
  final FeatureEngine[] engines={new FeatureEngine(),new FeatureEngine(),new FeatureEngine(),new FeatureEngine()};
  void resetAllEngines(){for(FeatureEngine e:engines)e.reset();checkedFeatures.clear();customEngines.clear();customChecks.clear();for(int m=0;m<4;m++)if(!abtRows[m].isEmpty())syncFeatureChecks(m);}
  void resetCurrentEngine(){resetEngineForModule(active);}
  void resetEngineForModule(int module){engines[module].reset();for(FeatureEngine.Feature f:moduleFeatures(module))checkedFeatures.remove(featureKey(f));}
  final ArrayList<AbtCodec.Row>[] abtRows=new ArrayList[]{new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),new ArrayList<>()};
  final HashMap<String,String> original=new HashMap<>();
  final AbtSnapshot[] importedSnapshots=new AbtSnapshot[4];
  final int[] scrollOffset={0,0,0,0};
  final int[] featurePage={0,0,0,0};
  final HashMap<String,Boolean> checkedFeatures=new HashMap<>();
  String featureKey(FeatureEngine.Feature f){return f.module+"|"+f.address+"|"+f.id;}
  boolean isChecked(FeatureEngine.Feature f){Boolean b=checkedFeatures.get(featureKey(f));return b!=null&&b;}
  void syncFeatureChecks(int module){for(FeatureEngine.Feature f:moduleFeatures(module)){boolean on=engines[module].state(f,findRowValueFor(module,f.address));checkedFeatures.put(featureKey(f),on);engines[module].seed(f,on);}}
  Set<Integer> activeFeaturePositions(int module,AbtCodec.Row row){
    Set<Integer> positions=new HashSet<>();
    String raw=AbtCodec.norm(row.value);
    int payloadLength=Math.max(0,raw.length()-2); // Last byte is the checksum.
    for(FeatureEngine.Feature f:moduleFeatures(module)){
      if(!f.address.equalsIgnoreCase(row.address)||!isChecked(f))continue;
      try{
        if("BITS".equalsIgnoreCase(f.mode)){
          int offset=f.byteIndex*2;
          if(offset>=0&&offset+1<payloadLength){positions.add(offset);positions.add(offset+1);}
        }else{
          for(int index:FeatureEngine.indices(f.indices))
            if(index>=0&&index<payloadLength)positions.add(index);
        }
      }catch(RuntimeException ignored){/* Ignore invalid admin configuration while drawing. */}
    }
    return positions;
  }
  String findRowValueFor(int module,String address){AbtCodec.Row r=findRow(module,address);return r==null?"":r.value;}
  ArrayList<FeatureEngine.Feature> moduleFeatures(int module){ArrayList<FeatureEngine.Feature> result=new ArrayList<>();for(FeatureEngine.Feature f:features)if(f.module.equals(modules[module]))result.add(f);return result;}
  int rowCount(int module){return abtRows[module].size();}
  final ArrayList<FeatureEngine.Feature> features=new ArrayList<>();
  String key(AbtCodec.Row r){return r.module+"|"+r.address;}
  AbtCodec.Row findRow(int module,String address){for(AbtCodec.Row r:abtRows[module])if(r.address.equals(address))return r;return null;}
  void initFeatures(){
    features.add(new FeatureEngine.Feature("rvm","IC","720-01-02","HEX","0","8",0));
    features.add(new FeatureEngine.Feature("new_feature","IC","720-01-01","HEX","2,3","40",0));
    features.add(new FeatureEngine.Feature("keyless","IC","720-01-01","HEX","0,1","2B",0));
  }
  void toggleFeature(int index){
    ArrayList<FeatureEngine.Feature> available=moduleFeatures(active);
    if(index>=available.size()){Toast.makeText(this,"Функция для этого блока ещё не настроена",Toast.LENGTH_SHORT).show();return;}
    FeatureEngine.Feature f=available.get(index);AbtCodec.Row row=findRow(active,f.address);
    if(row==null){
      final int targetModule=active;
      if(!loaded[targetModule]){
        org.json.JSONArray templates=PublishedFullSettings.builtInRowTemplates(this,modules[targetModule]);
        if(templates!=null)for(int i=0;i<templates.length();i++){
          org.json.JSONObject item=templates.optJSONObject(i);
          if(item==null||!f.address.equalsIgnoreCase(item.optString("address")))continue;
          // A checkbox click explicitly requests an edit. Create only a local
          // working copy; the signed template and imported vehicle ABT stay intact.
          try{
            if(active!=targetModule||loaded[targetModule])return;
            String value=item.optString("value");
            String hex=AbtCodec.norm(value);
            if(!hex.matches("[0-9A-F]{4,}")||hex.length()%2!=0)
              throw new IllegalArgumentException("Некорректное HEX-значение шаблона");
            if(findRow(targetModule,f.address)==null){
              AbtCodec.Row copy=new AbtCodec.Row(modules[targetModule],f.address,value,Integer.parseInt(f.address.split("-")[1]));
              abtRows[targetModule].add(copy);
              original.put(key(copy),hex);
              saveAdminRows(targetModule);
              refreshRows(targetModule);
            }
            toggleFeature(index);
          }catch(Exception ex){
            Toast.makeText(this,"Не удалось создать рабочую копию: "+ex.getMessage(),Toast.LENGTH_LONG).show();
          }
          return;
        }
      }
      Toast.makeText(this,"Строка "+f.address+" отсутствует в рабочих данных блока "+modules[active]+". Откройте ABT или добавьте строку.",Toast.LENGTH_LONG).show();return;
    }
    try{
      boolean next=!isChecked(f);
      String before=row.value;
      engines[active].apply(row,f,next,features);
      checkedFeatures.put(featureKey(f),next);
      if(!loaded[active])saveAdminRows(active);
      String after=row.value;
      StringBuilder changes=new StringBuilder();
      // Do not include the final checksum byte in the displayed feature changes.
      int dataEnd=Math.min(before.length(),after.length())-2;
      for(int i=0;i<dataEnd;i++){
        char oldChar=Character.toUpperCase(before.charAt(i));
        char newChar=Character.toUpperCase(after.charAt(i));
        if(oldChar!=newChar){
          if(changes.length()>0)changes.append(", ");
          changes.append(oldChar).append(" на ").append(newChar);
        }
      }
      lastFeatureStatus=f.label+": "+(next?"активирована":"деактивирована");
      refreshRows(active);view.invalidate();
    }
    catch(Exception ex){Toast.makeText(this,"Ошибка функции: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
  }
  void inspectHexRow(int index){
    if(index<0||index>=abtRows[active].size())return;
    final int inspectedModule=active;
    AbtCodec.Row row=abtRows[inspectedModule].get(index);
    boolean checksumMatches=AbtCodec.checksumValid(row.address,row.value);
    String checksumInfo=checksumMatches?"совпадает":"не совпадает. Это диагностическая проверка редактора, а не доказательство ошибки исходного ABT";
    new AlertDialog.Builder(this).setTitle(modules[inspectedModule]+" · "+row.address)
      .setMessage("Полное значение HEX:\n"+row.value+"\n\nChecksum (алгоритм редактора): "+checksumInfo+"\n\nДля изменения выберите «Редактировать».")
      .setNegativeButton("Закрыть",null)
      .setPositiveButton("Редактировать",(d,w)->{
        if(active!=inspectedModule||index>=abtRows[inspectedModule].size()||abtRows[inspectedModule].get(index)!=row){
          Toast.makeText(this,"Строка изменилась. Откройте её повторно.",Toast.LENGTH_LONG).show();return;
        }
        editHexRow(index);
      }).show();
  }
  void editHexRow(int index){
    if(index<0||index>=abtRows[active].size())return;
    final int editingModule=active;
    final AbtCodec.Row row=abtRows[editingModule].get(index);
    final String startingValue=row.value;
    final EditText edit=new EditText(this);edit.setSingleLine(false);edit.setMinLines(2);
    edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
    edit.setTypeface(Typeface.MONOSPACE);edit.setText(row.value);edit.selectAll();
    LinearLayout container=new LinearLayout(this);container.setOrientation(LinearLayout.VERTICAL);container.setPadding(dp(16),dp(8),dp(16),0);
    TextView hint=new TextView(this);hint.setText("Введите HEX-байты. Последний байт — контрольная сумма, она пересчитывается автоматически.");
    container.addView(hint);container.addView(edit);
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Редактор "+row.address).setView(container)
      .setNegativeButton("Отмена",null)
      .setPositiveButton("Сохранить",null).create();
    dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
        try{
          if(active!=editingModule||!abtRows[editingModule].contains(row)||!row.value.equals(startingValue)){
            edit.setError("Строка или блок изменились. Откройте редактор повторно.");return;
          }
          String input=edit.getText().toString().replaceAll("\\s+","");
          if(input.isEmpty()||input.length()%2!=0||!input.matches("[0-9A-Fa-f]+"))throw new IllegalArgumentException("Допустимы только полные HEX-байты");
          if(input.length()!=AbtCodec.norm(row.value).length())throw new IllegalArgumentException("Длина строки должна остаться прежней");
          if(input.length()<=2)throw new IllegalArgumentException("В строке нет байтов данных для редактирования");
          String originalHex=AbtCodec.norm(row.value);
          if(input.substring(0,input.length()-2).equalsIgnoreCase(originalHex.substring(0,originalHex.length()-2))){
            Toast.makeText(this,"Байты данных не изменились; checksum редактировать не нужно",Toast.LENGTH_LONG).show();return;
          }
          String updated=AbtCodec.recalc(row.address,input);
          new AlertDialog.Builder(this).setTitle("Подтвердите изменение "+row.address)
            .setMessage("Было: "+row.value+"\nСтанет: "+updated+"\n\nКонтрольная сумма будет пересчитана.")
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Применить",(confirm,choice)->{
              if(active!=editingModule||!abtRows[editingModule].contains(row)||!row.value.equals(startingValue)){
                Toast.makeText(this,"Данные изменились. Откройте строку повторно.",Toast.LENGTH_LONG).show();return;
              }
              row.value=updated;dialog.dismiss();resetCurrentEngine();syncFeatureChecks(editingModule);
              refreshRows(editingModule);view.invalidate();
              Toast.makeText(this,"Строка сохранена, checksum пересчитан",Toast.LENGTH_SHORT).show();
            }).show();
        }catch(Exception ex){edit.setError(ex.getMessage());edit.requestFocus();}
      }));
    dialog.show();
  }
  void refreshRows(int module){rows[module].clear();for(AbtCodec.Row row:abtRows[module])rows[module].add(row.value);}
  void loadRows(String content){
    HashMap<String,String> byPrefix=new HashMap<>();for(int i=0;i<4;i++)byPrefix.put(ids[i],modules[i]);
    List<AbtCodec.Row> parsed=AbtCodec.parseChecked(content,byPrefix);
    if(parsed.isEmpty())throw new IllegalArgumentException("Формат ABT не распознан");
    ArrayList<AbtCodec.Row> selected=AbtCodec.requireModule(parsed,modules[active]);
    HashSet<String> addresses=new HashSet<>();
    for(AbtCodec.Row row:selected){
      if(!addresses.add(row.address))throw new IllegalArgumentException("Повтор адреса "+row.address+" в ABT");
      String hex=AbtCodec.norm(row.value);
      if(hex.length()<4||hex.length()%2!=0)throw new IllegalArgumentException("Неверная длина строки "+row.address);
    }
    for(AbtCodec.Row old:abtRows[active])original.remove(key(old));
    importedSnapshots[active]=new AbtSnapshot(selected);
    abtRows[active].clear();abtRows[active].addAll(selected);scrollOffset[active]=0;
    for(AbtCodec.Row row:selected)original.put(key(row),AbtCodec.norm(row.value));
    resetCurrentEngine();
    featurePage[active]=0;syncFeatureChecks(active);
    refreshRows(active);loaded[active]=true;view.invalidate();
  }
  String findRowValue(String address){AbtCodec.Row row=findRow(active,address);return row==null?"":row.value;}
  boolean[] loaded=new boolean[4];
  int pendingModule=-1;
  void open(){pendingModule=active;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,10);}
  void save(){
    if(abtRows[active].isEmpty()){Toast.makeText(this,"В блоке "+modules[active]+" пока нет строк. Добавьте их через админку или откройте ABT.",Toast.LENGTH_LONG).show();return;}
    int suspect=0;
    for(AbtCodec.Row row:abtRows[active])if(!AbtCodec.checksumValid(row.address,row.value))suspect++;
    if(suspect>0){
      final int count=suspect;
      new AlertDialog.Builder(this).setTitle("Внимание: контрольные суммы")
        .setMessage("У "+count+" строк блока "+modules[active]+" контрольные суммы не совпадают. Исходные HEX и контрольные суммы будут сохранены без автоматического исправления. Продолжить сохранение?")
        .setNegativeButton("Отмена",null)
        .setPositiveButton("Продолжить",(d,w)->launchSavePicker()).show();
      return;
    }
    launchSavePicker();
  }
  void launchSavePicker(){
    pendingModule=active;
    Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
    i.setType("application/octet-stream");i.putExtra(Intent.EXTRA_TITLE,modules[active]+".abt");
    startActivityForResult(i,11);
  }
  @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);
    if(req==SPLASH_PICK_REQUEST){
      if(result==RESULT_OK&&data!=null&&data.getData()!=null){
        try{
          saveChosenSplash(data.getData());
          if(!splashFile().isFile()||splashFile().length()==0)throw new IOException("Файл не записался");
          Toast.makeText(this,"Новая заставка установлена! Полностью закройте и откройте приложение.",Toast.LENGTH_LONG).show();
        }catch(Exception ex){Toast.makeText(this,"Ошибка изображения: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
      }
      return;
    }
    if(req==9088){
      if(result==RESULT_OK && data!=null && data.getData()!=null){
        try{
          java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream();
          try(java.io.InputStream input=getContentResolver().openInputStream(data.getData())){
            if(input==null)throw new java.io.IOException("Файл недоступен");
            byte[] chunk=new byte[4096];int count;
            while((count=input.read(chunk))!=-1){
              if(buffer.size()+count>1048576)throw new java.io.IOException("Файл больше 1 МБ");
              buffer.write(chunk,0,count);
            }
          }
          org.json.JSONObject snapshot=new org.json.JSONObject(
            new String(buffer.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
          AdminSnapshot.validate(snapshot);
          org.json.JSONObject stores=snapshot.getJSONObject("stores");
          int functions=new org.json.JSONArray(stores.getJSONObject("run35_settings").optString("features","[]")).length();
          new AlertDialog.Builder(this).setTitle("Импорт настроек M6GH CONFIG")
            .setMessage("Функций в файле: "+functions+
              "\nБудут заменены сохранённые настройки блоков, строк, функций и оформления. "+
              "Сначала сохраните резервную копию текущих настроек. Продолжить?")
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Импортировать",(dialog,which)->{
              try{
                AdminSnapshot.importLocal(this,snapshot);
                // The admin UI uses an in-memory feature list. Refresh it after
                // restoring preferences so imported functions are visible now.
                features.clear();
                features.addAll(StudioSettings.load(this));
                restoreAdminRows();
                if(view!=null)view.invalidate();
                Toast.makeText(this,"Импорт завершён. Функции обновлены.",Toast.LENGTH_LONG).show();
              }catch(Exception ex){
                Toast.makeText(this,"Ошибка импорта: "+ex.getMessage(),Toast.LENGTH_LONG).show();
              }
            }).show();
        }catch(Exception ex){
          Toast.makeText(this,"Неверный файл: "+ex.getMessage(),Toast.LENGTH_LONG).show();
        }
      }
      return;
    }
    if(req==9087){
      int requestCode=req, resultCode=result;
    if(requestCode==9087 && resultCode==RESULT_OK && data!=null &&
       data.getData()!=null && pendingAdminSnapshot!=null){
      try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData())){
        if(out==null)throw new java.io.IOException("No output stream");
        out.write(pendingAdminSnapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Toast.makeText(this,"Настройки экспортированы",Toast.LENGTH_LONG).show();
      }catch(Exception ex){
        Toast.makeText(this,"Ошибка записи: "+ex.getMessage(),Toast.LENGTH_LONG).show();
      }finally{pendingAdminSnapshot=null;}
    }
      return;
    }if(req==12||req==13){if(result==RESULT_OK&&data!=null&&data.getData()!=null){try{handleCustomAbt(req,data.getData());}catch(Exception ex){Toast.makeText(this,"Ошибка ABT: "+ex.getMessage(),Toast.LENGTH_LONG).show();}}else pendingCustomId="";return;}if(req!=10&&req!=11)return;if(result!=RESULT_OK||data==null||data.getData()==null){pendingModule=-1;return;}
    Uri uri=data.getData();
    if(req==10&&handleAdminKey(uri)){pendingModule=-1;return;}
    int previous=active;
    if(pendingModule>=0&&pendingModule<modules.length)active=pendingModule;
    pendingModule=-1;
    try{
      if(req==10){
        ByteArrayOutputStream buf=new ByteArrayOutputStream();byte[] chunk=new byte[4096];int n;
        try(InputStream in=getContentResolver().openInputStream(uri)){
          if(in==null)throw new IOException("Файл недоступен");
          while((n=in.read(chunk))!=-1){if(buf.size()+n>4*1024*1024)throw new IOException("ABT слишком большой");buf.write(chunk,0,n);}
        }
        loadRows(new String(buf.toByteArray(),"UTF-8"));
        int suspect=0;for(AbtCodec.Row row:abtRows[active])if(!AbtCodec.checksumValid(row.address,row.value))suspect++;
        if(suspect>0)new AlertDialog.Builder(this).setTitle("Проверка ABT")
          .setMessage("Загружено строк: "+abtRows[active].size()+". Контрольная сумма не совпала у "+suspect+" строк. Исходные значения не изменены. Проверьте формат и файл перед записью в автомобиль.")
          .setPositiveButton("Понятно",null).show();
        else Toast.makeText(this,"Загружено строк: "+abtRows[active].size(),Toast.LENGTH_SHORT).show();
      }else if(req==11){
        if(abtRows[active].isEmpty())throw new IllegalArgumentException("Сначала откройте ABT");
        String text=AbtCodec.write(abtRows[active],modules[active]);
        if(text.isEmpty())throw new IOException("Нет строк для сохранения");
        try(OutputStream out=getContentResolver().openOutputStream(uri)){
          if(out==null)throw new IOException("Невозможно открыть файл для записи");
          out.write(text.getBytes("US-ASCII"));out.flush();
        }
        Toast.makeText(this,"ABT блока "+modules[active]+" сохранён",Toast.LENGTH_SHORT).show();
      }
    }catch(Exception ex){Toast.makeText(this,"Ошибка ABT: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
    finally{active=previous;view.invalidate();}
  }

  int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
  android.graphics.drawable.GradientDrawable panel(int top,int bottom,int radius,int border){
    android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable(
      android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,new int[]{top,bottom});
    g.setCornerRadius(dp(radius));g.setStroke(dp(1.5f),border);return g;
  }
  void place(FrameLayout root,View child,float left,float top,float width,float height,int totalW,int totalH){
    FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(Math.round(totalW*width),Math.round(totalH*height));
    lp.leftMargin=Math.round(totalW*left);lp.topMargin=Math.round(totalH*top);root.addView(child,lp);
  }
  TextView caption(String s,int size,int color,boolean bold){
    TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
    t.setGravity(Gravity.CENTER_VERTICAL);if(bold)t.setTypeface(null,android.graphics.Typeface.BOLD);return t;
  }
  void modal(boolean isAdmin){
    final Dialog dialog=new Dialog(this);
    final int width=Math.round(getResources().getDisplayMetrics().widthPixels*.87f);
    final String aboutText=StudioSettings.appearance(this,"about_description","Редактор As-Built для Mazda 6 GH");
    final int screenHeight=getResources().getDisplayMetrics().heightPixels;
    final int estimatedLines=Math.max(1,(int)Math.ceil(aboutText.length()/37.0)+aboutText.split("\n",-1).length-1);
    final int height=isAdmin?Math.round(width*1.24f):Math.min(Math.round(screenHeight*.89f),Math.max(Math.round(width*1.39f),Math.round(width*1.22f)+Math.max(0,estimatedLines-4)*dp(19)));
    final FrameLayout root=new FrameLayout(this);
    root.setBackground(panel(Color.rgb(252,253,254),Color.rgb(234,239,244),14,Color.rgb(225,50,51)));
    root.setClipToOutline(true);
    View header=new View(this);header.setBackground(panel(Color.WHITE,Color.rgb(235,239,244),10,Color.rgb(215,222,230)));
    place(root,header,.025f,.025f,.95f,isAdmin?.16f:(float)dp(56)/height,width,height);
    TextView title=caption(isAdmin?StudioSettings.appearance(this,"admin_text","Админка"):"О программе",20,Color.BLACK,true);
    if(!isAdmin)title.setGravity(Gravity.CENTER);
    place(root,title,isAdmin?.16f:.08f,isAdmin?.045f:.025f,isAdmin?.60f:.84f,isAdmin?.11f:(float)dp(56)/height,width,height);
    if(isAdmin){
      TextView lock=caption("🔒",25,Color.rgb(190,15,22),true);
      place(root,lock,.065f,.045f,.11f,.11f,width,height);
    }
    if(isAdmin){
    TextView close=caption("×",31,Color.BLACK,true);close.setGravity(Gravity.CENTER);close.setIncludeFontPadding(false);close.setPadding(0,0,0,0);
    close.setBackground(panel(Color.WHITE,Color.rgb(235,238,242),8,Color.rgb(200,205,212)));
    place(root,close,.82f,.045f,.125f,.105f,width,height);
    close.setOnClickListener(v->dialog.dismiss());
    }
    if(isAdmin){
      TextView help=caption("Вход администратора",15,Color.DKGRAY,false);
      place(root,help,.085f,.22f,.80f,.07f,width,height);
      EditText login=new EditText(this),password=new EditText(this);
      login.setSingleLine(true);login.setTextColor(Color.BLACK);login.setHintTextColor(Color.DKGRAY);login.setHint("Логин");login.setTextSize(16);login.setPadding(dp(12),0,dp(12),0);
      login.setBackground(panel(Color.WHITE,Color.rgb(247,248,250),8,Color.rgb(192,200,209)));
      place(root,login,.08f,.33f,.84f,.125f,width,height);
      password.setSingleLine(true);password.setTextColor(Color.BLACK);password.setHintTextColor(Color.DKGRAY);password.setHint("Пароль");password.setInputType(129);password.setTextSize(16);
      password.setPadding(dp(12),0,dp(12),0);
      password.setBackground(panel(Color.WHITE,Color.rgb(247,248,250),8,Color.rgb(192,200,209)));
      place(root,password,.08f,.49f,.84f,.125f,width,height);
      TextView cancel=caption("Отмена",15,Color.BLACK,true);cancel.setGravity(Gravity.CENTER);
      cancel.setBackground(panel(Color.WHITE,Color.rgb(218,224,231),9,Color.rgb(185,193,201)));
      place(root,cancel,.08f,.75f,.39f,.13f,width,height);cancel.setOnClickListener(v->dialog.dismiss());
      TextView enter=caption("Войти",15,Color.WHITE,true);enter.setGravity(Gravity.CENTER);
      enter.setBackground(panel(Color.rgb(240,66,67),Color.rgb(169,0,8),9,Color.rgb(255,108,112)));
      place(root,enter,.53f,.75f,.39f,.13f,width,height);
      enter.setOnClickListener(v->{if(login.getText().toString().equals("admin")&&verifyStudioAdminPassword(password.getText().toString())){dialog.dismiss();adminSession=true;view.invalidate();showAdminTabs();}else Toast.makeText(this,"Неверный логин или пароль",Toast.LENGTH_SHORT).show();});
    }else{
      // Information-only layout without duplicated Mazda logo/banner.
      TextView version=caption("M6GH AS-BUILT CONFIG",22,Color.BLACK,true);
      version.setGravity(Gravity.CENTER);
      place(root,version,.09f,.112f,.82f,.070f,width,height);
      View divider=new View(this);divider.setBackgroundColor(Color.rgb(220,50,55));
      place(root,divider,.09f,.195f,.82f,.004f,width,height);
      // Give the complete description a substantially taller region; scroll only for unusually long text.
      android.widget.ScrollView descriptionScroll=new android.widget.ScrollView(this);
      descriptionScroll.setFillViewport(false);
      descriptionScroll.setVerticalScrollBarEnabled(true);
      TextView description=caption(aboutText,15,Color.BLACK,false);
      // Plain-text description keeps line breaks and paragraph indentation exactly as entered.
      // Existing rich-text descriptions retain their HTML formatting.
      // Keep literal paragraph breaks even when the administrator uses <b>/<u> markup.
      CharSequence parsed=aboutText.matches("(?s).*<[/a-zA-Z][^>]*>.*")
          ?android.text.Html.fromHtml(aboutText.replace("\r\n","\n").replace("\n","<br>"),
              android.text.Html.FROM_HTML_MODE_COMPACT):aboutText;
      android.text.SpannableStringBuilder indented=new android.text.SpannableStringBuilder(parsed);
      int paragraphStart=0;
      while(paragraphStart<indented.length()){
        int paragraphEnd=android.text.TextUtils.indexOf(indented,'\n',paragraphStart);
        if(paragraphEnd<0)paragraphEnd=indented.length();
        else paragraphEnd++;
        if(paragraphEnd>paragraphStart&&indented.subSequence(paragraphStart,paragraphEnd).toString().trim().length()>0){
          indented.setSpan(new android.text.style.LeadingMarginSpan.Standard(dp(18),0),
              paragraphStart,paragraphEnd,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        paragraphStart=paragraphEnd;
      }
      description.setText(indented);
      description.setGravity(Gravity.TOP|Gravity.START);
      description.setSingleLine(false);
      description.setMaxLines(Integer.MAX_VALUE);
      description.setPadding(0,dp(3),dp(5),dp(3));
      descriptionScroll.addView(description,new android.widget.ScrollView.LayoutParams(-1,-2));
      place(root,descriptionScroll,.09f,.212f,.84f,.433f,width,height);
      TextView warningTitle=caption(StudioSettings.appearance(this,"warning_title","⚠️WARNING⚠️"),16,Color.rgb(204,24,38),true);
      warningTitle.setGravity(Gravity.CENTER);
      place(root,warningTitle,.09f,.653f,.82f,.030f,width,height);
      TextView warningBody=caption(StudioSettings.appearance(this,"warning_body","НЕ ЗАБЫВАЙТЕ ДЕЛАТЬ КОПИЮ СКАЧЕННОГО ФАЙЛА НАСТРОЕК ИЗ FORSCAN."),12,Color.rgb(204,24,38),true);
      warningBody.setGravity(Gravity.CENTER);
      warningBody.setSingleLine(false);
      place(root,warningBody,.09f,.687f,.82f,.052f,width,height);
      String[] creatorNames={
        StudioSettings.appearance(this,"creator1_name","Dim304"),
        StudioSettings.appearance(this,"creator2_name","Wolis11")
      };
      String[] creatorUrls={
        StudioSettings.appearance(this,"creator1_url","https://www.drive2.ru/users/dim304/"),
        StudioSettings.appearance(this,"creator2_url","https://www.drive2.ru/users/wolis11/")
      };
      for(int i=0;i<2;i++){
        final String url=creatorUrls[i].trim();
        String role=i==0
          ?StudioSettings.appearance(this,"creator1_role","Разработчик")
          :StudioSettings.appearance(this,"creator2_role","Помощник");
        role=role.trim();
        if(!role.isEmpty())role+=": ";
        TextView person=caption(role+creatorNames[i]+"   D  Drive2",13,Color.BLACK,true);
        android.text.SpannableString styled=new android.text.SpannableString(person.getText());
        int iconStart=styled.toString().lastIndexOf("D  Drive2");
        if(iconStart>=0){
          styled.setSpan(new android.text.style.BackgroundColorSpan(Color.rgb(192,14,47)),iconStart,iconStart+1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
          styled.setSpan(new android.text.style.ForegroundColorSpan(Color.WHITE),iconStart,iconStart+1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
          styled.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),iconStart,iconStart+1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int linkStart=styled.toString().lastIndexOf("Drive2");
        styled.setSpan(new android.text.style.ForegroundColorSpan(Color.rgb(24,96,191)),linkStart,styled.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new android.text.style.UnderlineSpan(),linkStart,styled.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        person.setText(styled);
        place(root,person,.09f,i==0?.773f:.795f,.84f,.038f,width,height);
        android.text.style.ClickableSpan onlyLink=new android.text.style.ClickableSpan(){
          @Override public void onClick(android.view.View widget){
            if(!url.isEmpty()&&(url.startsWith("https://")||url.startsWith("http://"))){
              try{startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(url)));}
              catch(Exception ex){Toast.makeText(MainActivity.this,"Не удалось открыть ссылку",Toast.LENGTH_SHORT).show();}
            }
          }
        };
        styled.setSpan(onlyLink,linkStart,styled.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        person.setText(styled);
        person.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        person.setHighlightColor(Color.TRANSPARENT);
      }
      String appVersion="1.0.0 (CONFIG)";
      long appBuild=1;
      try{
        android.content.pm.PackageInfo pkg=getPackageManager().getPackageInfo(getPackageName(),0);
        if(pkg.versionName!=null&&!pkg.versionName.isEmpty())appVersion=pkg.versionName;
        appBuild=android.os.Build.VERSION.SDK_INT>=28?pkg.getLongVersionCode():pkg.versionCode;
      }catch(Exception ignored){}
      TextView versionInfo=caption("◉  Версия: "+appVersion,10,Color.rgb(98,106,116),false);
      place(root,versionInfo,.095f,.835f,.78f,.030f,width,height);

      TextView ok=caption("Закрыть",16,Color.WHITE,true);ok.setGravity(Gravity.CENTER);
      ok.setBackground(panel(Color.rgb(245,69,69),Color.rgb(170,0,10),9,Color.rgb(255,103,109)));
      place(root,ok,.09f,.89f,.82f,.075f,width,height);ok.setOnClickListener(v->dialog.dismiss());
    }
    dialog.setContentView(root);dialog.show();
    Window window=dialog.getWindow();if(window!=null){
      window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      window.setDimAmount(.65f);window.setLayout(width,height);
    }
  }
  void restoreCurrentModule(){
    if(!loaded[active]){Toast.makeText(this,"Сначала откройте ABT блока "+modules[active],Toast.LENGTH_LONG).show();return;}
    final int module=active;
    final int[] diff=importedSnapshots[module].differences(abtRows[module]);
    new AlertDialog.Builder(this).setTitle("Восстановить исходный ABT?")
      .setMessage("Блок "+modules[module]+": изменено HEX — "+diff[0]+", добавлено строк — "+diff[1]+", удалено строк — "+diff[2]+". Восстановить исходный файл целиком? Остальные блоки не затрагиваются.")
      .setNegativeButton("Отмена",null)
      .setPositiveButton("Восстановить",(d,w)->{
        for(AbtCodec.Row row:abtRows[module])original.remove(key(row));
        abtRows[module].clear();
        for(AbtCodec.Row row:importedSnapshots[module].restore()){
          AbtCodec.Row copy=new AbtCodec.Row(row.module,row.address,row.value,row.block);
          abtRows[module].add(copy);
          original.put(key(copy),AbtCodec.norm(copy.value));
        }
        engines[module].reset();
        for(FeatureEngine.Feature f:moduleFeatures(module))checkedFeatures.remove(featureKey(f));
        scrollOffset[module]=0;featurePage[module]=0;
        syncFeatureChecks(module);refreshRows(module);view.invalidate();
        Toast.makeText(this,"Исходный ABT блока "+modules[module]+" восстановлен",Toast.LENGTH_SHORT).show();
      }).show();
  }
  void showModuleAdmin(){
    final String module=modules[active];
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(12),dp(18),0);
    TextView fixed=new TextView(this);fixed.setText("Блок: "+module+"  |  адрес: "+ids[active]+" (не изменяется)");root.addView(fixed);
    EditText name=new EditText(this);name.setSingleLine(true);name.setHint("Название блока");
    name.setText(StudioSettings.moduleName(this,module,names[active]));root.addView(name);
    EditText version=new EditText(this);version.setSingleLine(true);version.setHint("Версия / обозначение");
    version.setText(StudioSettings.moduleVersion(this,module));root.addView(version);
    new AlertDialog.Builder(this).setTitle("Админка · блок "+module).setView(root)
      .setNegativeButton("Отмена",null)
      .setPositiveButton("Сохранить",(d,w)->{
        String newName=name.getText().toString().trim();
        if(newName.isEmpty()){Toast.makeText(this,"Название не может быть пустым",Toast.LENGTH_LONG).show();return;}
        StudioSettings.saveModule(this,module,newName,version.getText().toString().trim());
        view.invalidate();Toast.makeText(this,"Настройки блока сохранены",Toast.LENGTH_SHORT).show();
      }).show();
  }
  void showRowsAdmin(){
    final ArrayList<AbtCodec.Row> list=abtRows[active];
    if(!loaded[active]){Toast.makeText(this,"Сначала откройте ABT текущего блока",Toast.LENGTH_LONG).show();return;}
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(14),dp(8),dp(14),dp(8));
    Spinner selector=new Spinner(this);ArrayList<String> names=new ArrayList<>();
    for(AbtCodec.Row r:list)names.add(r.address);
    ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names);
    selector.setAdapter(adapter);root.addView(selector);
    EditText address=new EditText(this);address.setSingleLine(true);address.setHint("Адрес: "+ids[active]+"-01-01");root.addView(address);
    EditText value=new EditText(this);value.setSingleLine(false);value.setMinLines(2);value.setHint("HEX, включая checksum");root.addView(value);
    selector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
      public void onItemSelected(android.widget.AdapterView<?> parent,View v,int position,long id){
        if(position>=0&&position<list.size()){address.setText(list.get(position).address);value.setText(list.get(position).value);}
      }
      public void onNothingSelected(android.widget.AdapterView<?> parent){}
    });
    LinearLayout buttons=new LinearLayout(this);root.addView(buttons);
    Button add=new Button(this);add.setText("Добавить");buttons.addView(add);
    Button change=new Button(this);change.setText("Изменить");buttons.addView(change);
    Button remove=new Button(this);remove.setText("Удалить");buttons.addView(remove);
    final boolean[] creating={false};
    add.setOnClickListener(v->{creating[0]=true;address.setText(ids[active]+"-01-01");value.setText("");Toast.makeText(this,"Введите новый адрес и HEX, затем нажмите Изменить",Toast.LENGTH_LONG).show();});
    change.setOnClickListener(v->{
      try{
        String addr=address.getText().toString().trim().toUpperCase(Locale.US);
        String hex=value.getText().toString().replaceAll("\\s+","").toUpperCase(Locale.US);
        if(!addr.matches(ids[active]+"-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException("Адрес должен принадлежать текущему блоку");
        if(hex.length()<4||hex.length()%2!=0||!hex.matches("[0-9A-F]+"))throw new IllegalArgumentException("Неверные HEX-байты");
        String updated=AbtCodec.recalc(addr,hex);
        int selected=selector.getSelectedItemPosition();
        if(creating[0]){
          for(AbtCodec.Row r:list)if(r.address.equals(addr))throw new IllegalArgumentException("Строка уже существует");
          list.add(new AbtCodec.Row(modules[active],addr,updated,Integer.parseInt(addr.split("-")[1])));
          creating[0]=false;
        }else{
          if(selected<0||selected>=list.size())throw new IllegalArgumentException("Выберите строку");
          AbtCodec.Row old=list.get(selected);
          if(!old.address.equals(addr))throw new IllegalArgumentException("Адрес существующей строки менять нельзя — добавьте новую");
          if(AbtCodec.norm(old.value).length()!=hex.length())throw new IllegalArgumentException("Длина существующей строки должна остаться прежней");
          old.value=updated;
        }
        resetCurrentEngine();syncFeatureChecks(active);refreshRows(active);
        names.clear();for(AbtCodec.Row r:list)names.add(r.address);adapter.notifyDataSetChanged();view.invalidate();
        Toast.makeText(this,"Строка обновлена; сохраните ABT в файл",Toast.LENGTH_SHORT).show();
      }catch(Exception ex){Toast.makeText(this,"Ошибка: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
    });
    remove.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Удалить строку?")
      .setMessage("Удаление будет записано только при сохранении ABT.")
      .setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)->{
        int n=selector.getSelectedItemPosition();if(n<0||n>=list.size())return;
        list.remove(n);resetCurrentEngine();syncFeatureChecks(active);refreshRows(active);
        names.clear();for(AbtCodec.Row r:list)names.add(r.address);adapter.notifyDataSetChanged();view.invalidate();
      }).show());
    new AlertDialog.Builder(this).setTitle("Админка · строки "+modules[active]).setView(root).setPositiveButton("Закрыть",null).show();
  }
  void showFeatureAdmin(){
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(14),dp(8),dp(14),dp(8));
    ScrollView scroll=new ScrollView(this);scroll.addView(root);
    final String[] fields={"ID","Блок IC/BCM/RKE/ABS","Адрес строки","Режим HEX/BITS","Индексы HEX или биты","Значение ON","Значение OFF","Номер байта BITS"};
    final EditText[] edits=new EditText[fields.length];
    Spinner selector=new Spinner(this);ArrayList<String> labels=new ArrayList<>();
    for(FeatureEngine.Feature f:features)labels.add(f.label+" · "+f.module+" · "+f.address);
    ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels);
    selector.setAdapter(adapter);root.addView(selector);
    for(int i=0;i<fields.length;i++){EditText ed=new EditText(this);ed.setSingleLine(true);ed.setHint(fields[i]);root.addView(ed);edits[i]=ed;}
    Runnable fill=()->{int n=selector.getSelectedItemPosition();if(n<0||n>=features.size())return;
      FeatureEngine.Feature f=features.get(n);String[] v={f.id,f.module,f.address,f.mode,f.indices,f.on,f.off,String.valueOf(f.byteIndex)};
      for(int i=0;i<v.length;i++)edits[i].setText(v[i]);
    };
    selector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
      public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){fill.run();}
      public void onNothingSelected(android.widget.AdapterView<?> p){}
    });
    final boolean[] creating={false};
    final Runnable[] saveEntry=new Runnable[1];
    saveEntry[0]=()->{
      try{
        String id=edits[0].getText().toString().trim(),module=edits[1].getText().toString().trim().toUpperCase(Locale.US);
        String address=edits[2].getText().toString().trim().toUpperCase(Locale.US),mode=edits[3].getText().toString().trim().toUpperCase(Locale.US);
        if(id.isEmpty()||!Arrays.asList(modules).contains(module)||!address.matches("[0-9A-F]{3}-[0-9]{2}-[0-9]{2}")||!Arrays.asList("HEX","BITS").contains(mode))throw new IllegalArgumentException("Проверьте ID, блок, адрес и режим");
        int bi=Integer.parseInt(edits[7].getText().toString().trim());
        FeatureEngine.Feature f=new FeatureEngine.Feature(id,module,address,mode,edits[4].getText().toString(),edits[5].getText().toString(),edits[6].getText().toString(),bi);
        int n=selector.getSelectedItemPosition();if(!creating[0]&&n>=0&&n<features.size())features.set(n,f);else {features.add(f);creating[0]=false;}
        StudioSettings.save(this,features);resetAllEngines();
        labels.clear();for(FeatureEngine.Feature item:features)labels.add(item.id+" · "+item.module+" · "+item.address);adapter.notifyDataSetChanged();Toast.makeText(this,"Функция сохранена",Toast.LENGTH_SHORT).show();view.invalidate();
      }catch(Exception ex){Toast.makeText(this,"Ошибка: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
    };
    LinearLayout actions=new LinearLayout(this);
    Button save=new Button(this);save.setText("Изменить");actions.addView(save);save.setOnClickListener(v->saveEntry[0].run());
    Button add=new Button(this);add.setText("Добавить");actions.addView(add);add.setOnClickListener(v->{
      creating[0]=true;for(EditText ed:edits)ed.setText("");edits[7].setText("0");
      new AlertDialog.Builder(this).setTitle("Новая функция").setMessage("Заполните поля и нажмите Изменить для сохранения.").setPositiveButton("OK",null).show();
    });
    Button remove=new Button(this);remove.setText("Удалить");actions.addView(remove);remove.setOnClickListener(v->{
      int n=selector.getSelectedItemPosition();if(n<0||n>=features.size())return;
      features.remove(n);StudioSettings.save(this,features);resetAllEngines();
      labels.clear();for(FeatureEngine.Feature f:features)labels.add(f.label+" · "+f.module+" · "+f.address);adapter.notifyDataSetChanged();view.invalidate();
    });
    root.addView(actions);
    Button rowsAdmin=new Button(this);rowsAdmin.setText("Редактор строк ABT");root.addView(rowsAdmin);rowsAdmin.setOnClickListener(v->showRowsAdmin());
    Button restore=new Button(this);restore.setText("Восстановить исходный ABT");root.addView(restore);restore.setOnClickListener(v->restoreCurrentModule());
    Button moduleAdmin=new Button(this);moduleAdmin.setText("Настройки блока");root.addView(moduleAdmin);moduleAdmin.setOnClickListener(v->showModuleAdmin());
    Button templates=new Button(this);templates.setText("Добавить шаблоны функций");root.addView(templates);
    templates.setOnClickListener(v->new AlertDialog.Builder(this)
      .setTitle("Шаблоны из Run #89")
      .setMessage("Добавить отсутствующие функции IC, BCM, RKE, ABS? Существующие настройки сохранятся. Кодировки требуют проверки на автомобиле.")
      .setNegativeButton("Отмена",null)
      .setPositiveButton("Добавить",(dialog,which)->{
        int added=0;
        for(FeatureEngine.Feature item:StudioSettings.defaults()){
          boolean exists=false;
          for(FeatureEngine.Feature current:features)
            if(current.module.equals(item.module)&&current.id.equals(item.id)){exists=true;break;}
          if(!exists){features.add(item);added++;}
        }
        StudioSettings.save(this,features);resetAllEngines();
        labels.clear();for(FeatureEngine.Feature item:features)labels.add(item.id+" · "+item.module+" · "+item.address);
        adapter.notifyDataSetChanged();view.invalidate();
        Toast.makeText(this,"Добавлено шаблонов: "+added,Toast.LENGTH_LONG).show();
      }).show());
    new AlertDialog.Builder(this).setTitle("Админка · функции и биты").setView(scroll).setPositiveButton("Закрыть",null).show();
  }
  boolean isFactoryModuleHidden(int index){
    return index>=0&&index<modules.length&&getSharedPreferences("studio_hidden_factory_modules",MODE_PRIVATE).getBoolean(modules[index],false);
  }
  int visibleTabCount(){
    int count=customModuleCatalog().length();
    for(int i=0;i<modules.length;i++)if(!isFactoryModuleHidden(i))count++;
    return count;
  }
  int visibleTabIndex(int underlying){
    int position=0;
    for(int i=0;i<modules.length;i++)if(!isFactoryModuleHidden(i)){if(i==underlying)return position;position++;}
    if(underlying>=modules.length)return position+underlying-modules.length;
    return 0;
  }
  int moduleAtTabIndex(int position){
    for(int i=0;i<modules.length;i++)if(!isFactoryModuleHidden(i)){if(position--==0)return i;}
    return modules.length+position;
  }
  int adminRowsModule=0;
  void showAdminTabs(){
    adminRowsModule=active;
    final Dialog dialog=new Dialog(this);
    final Runnable[] commitCurrent={null};
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(8),dp(8),dp(8),dp(8));root.setBackgroundColor(Color.rgb(44,44,44));
    TextView title=new TextView(this);title.setText("Администрирование интерфейса");
    title.setTextSize(18);title.setTextColor(Color.WHITE);title.setPadding(dp(8),dp(8),0,dp(14));root.addView(title);
    View line=new View(this);line.setBackgroundColor(Color.rgb(40,170,210));
    root.addView(line,new LinearLayout.LayoutParams(-1,dp(2)));
    android.widget.HorizontalScrollView horizontal=new android.widget.HorizontalScrollView(this);
    horizontal.setHorizontalScrollBarEnabled(true);
    LinearLayout tabs=new LinearLayout(this);tabs.setOrientation(LinearLayout.HORIZONTAL);
    horizontal.addView(tabs);root.addView(horizontal);
    FrameLayout body=new FrameLayout(this);
    LinearLayout.LayoutParams bodyParams=new LinearLayout.LayoutParams(-1,0,1);
    root.addView(body,bodyParams);
    final String[] names={"Функции и биты","Строки As-Built","Блоки и доступ","Оформление"};
    final Button[] buttons=new Button[4];
    final View[] underlines=new View[4];
    final int[] selected={0};
    final Runnable[] redrawRef=new Runnable[1];
    final Button[] footerSave={null};
    final LinearLayout[] footerCloseLayout={null};
    final ScrollView[] adminScroll={null};
    Runnable redraw=()->{
      int maxRowsModule=modules.length+customModuleCatalog().length();
      if(adminRowsModule<0||adminRowsModule>=maxRowsModule){
        adminRowsModule=Math.max(0,Math.min(active,maxRowsModule-1));
      }
      commitCurrent[0]=null;
      if(footerSave[0]!=null){footerSave[0].setVisibility(selected[0]==3?View.VISIBLE:View.GONE);footerSave[0].setText("Сохранить оформление");}
      if(footerCloseLayout[0]!=null)footerCloseLayout[0].setWeightSum(selected[0]==3?2f:1f);
      body.removeAllViews();
      LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);
      panel.setPadding(dp(3),dp(5),dp(3),dp(5));
      if(selected[0]==0||selected[0]==1||selected[0]==2){
        ScrollView moduleScroll=new ScrollView(this);
        adminScroll[0]=moduleScroll;
        moduleScroll.setFillViewport(true);
        moduleScroll.setSmoothScrollingEnabled(true);
        moduleScroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
        moduleScroll.addView(panel,new ScrollView.LayoutParams(-1,-2));
        body.addView(moduleScroll,new FrameLayout.LayoutParams(-1,-1));
      }else body.addView(panel,new FrameLayout.LayoutParams(-1,-1));
      TextView heading=new TextView(this);heading.setText("Выберите запись");
      heading.setTextSize(20);heading.setTextColor(Color.WHITE);heading.setGravity(Gravity.CENTER);
      heading.setBackgroundColor(Color.rgb(84,84,84));heading.setPadding(0,dp(15),0,dp(15));
      panel.addView(heading,new LinearLayout.LayoutParams(-1,-2));
      if(selected[0]==0){
        final String[] labels={"ID функции","Модуль","Надпись / функция","Строка","Режим HEX/BITS","HEX индексы","Byte (BITS)","Биты","ВКЛ","ВЫКЛ"};
        final EditText[] values=new EditText[labels.length];
        final LinearLayout[] fieldRows=new LinearLayout[labels.length];
        final int[] chosen={-1};
        final int[] selectedFeature={-1};
        Button featureSelector=new Button(this);
        featureSelector.setAllCaps(false);featureSelector.setTextSize(13);
        featureSelector.setText("Выберите функцию…");
        // The heading is the single feature selector; avoid a duplicate button.
        Runnable refreshFeatureChoices=()->{
          int n=chosen[0];
          heading.setText(n>=0&&n<features.size()?(n+1)+" · "+features.get(n).label:"Выберите запись");
        };
        heading.setOnClickListener(v->{
          String[] options=new String[features.size()];
          for(int n=0;n<features.size();n++)options[n]=(n+1)+" · "+features.get(n).label;
          new AlertDialog.Builder(this).setTitle("Выберите функцию")
            .setSingleChoiceItems(options,chosen[0],(choiceDialog,position)->{
              chosen[0]=position;
              FeatureEngine.Feature f=features.get(position);
              heading.setText((position+1)+" · "+f.label);
              String[] info={f.id,f.module,f.label,f.address,f.mode,
                "HEX".equalsIgnoreCase(f.mode)?f.indices:"",
                String.valueOf(f.byteIndex),"BITS".equalsIgnoreCase(f.mode)?f.indices:"",f.on,f.off};
              for(int j=0;j<values.length;j++)values[j].setText(info[j]);
              refreshFeatureChoices.run();choiceDialog.dismiss();
            }).setNegativeButton("Закрыть",null).show();
        });
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        panel.addView(content,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);
        for(int j=0;j<labels.length;j++){
          LinearLayout lineRow=new LinearLayout(this);lineRow.setOrientation(LinearLayout.HORIZONTAL);
          TextView label=new TextView(this);label.setText(labels[j]);label.setTextSize(12);label.setTextColor(Color.WHITE);
          label.setGravity(Gravity.CENTER_VERTICAL);lineRow.addView(label,new LinearLayout.LayoutParams(0,dp(42),1));
          EditText value=new EditText(this);value.setSingleLine(true);value.setTextColor(Color.BLACK);value.setTextSize(13);
          value.setGravity(Gravity.CENTER_VERTICAL);value.setPadding(dp(6),0,dp(5),0);
          value.setBackgroundColor(Color.WHITE);lineRow.addView(value,new LinearLayout.LayoutParams(0,dp(42),1));
          lineRow.setPadding(dp(4),dp(3),dp(4),dp(3));
          values[j]=value;fieldRows[j]=lineRow;fields.addView(lineRow);
          value.setOnFocusChangeListener((focusedView,hasFocus)->{
            if(hasFocus&&adminScroll[0]!=null){
              adminScroll[0].post(()->{
                android.graphics.Rect rect=new android.graphics.Rect();
                focusedView.getDrawingRect(rect);
                adminScroll[0].offsetDescendantRectToMyCoords(focusedView,rect);
                adminScroll[0].smoothScrollTo(0,Math.max(0,rect.bottom-adminScroll[0].getHeight()+dp(72)));
              });
            }
          });
        }
        Runnable updateModeFields=()->{
          boolean bits="BITS".equalsIgnoreCase(values[4].getText().toString().trim());
          fieldRows[5].setVisibility(bits?View.GONE:View.VISIBLE);
          fieldRows[6].setVisibility(bits?View.VISIBLE:View.GONE);
          fieldRows[7].setVisibility(bits?View.VISIBLE:View.GONE);
          fieldRows[8].setVisibility(bits?View.GONE:View.VISIBLE);
          fieldRows[9].setVisibility(bits?View.GONE:View.VISIBLE);
        };
        values[5].setHint("Например: 0,1 или 2-4");
        values[6].setHint("Номер байта с 0");
        values[7].setHint("Например: 0,2,7");
        values[8].setHint("HEX, например 8F");
        values[9].setHint("HEX, например 0F");
        values[1].setFocusable(false);
        values[1].setClickable(true);
        values[1].setHint("Выберите модуль");
        values[1].setOnClickListener(v->{
          org.json.JSONArray catalog=customModuleCatalog();
          String[] choices=new String[modules.length+catalog.length()];
          for(int i=0;i<modules.length;i++)choices[i]=modules[i];
          for(int i=0;i<catalog.length();i++){
            org.json.JSONObject entry=catalog.optJSONObject(i);
            choices[modules.length+i]=entry==null?"":entry.optString("id");
          }
          new AlertDialog.Builder(this).setTitle("Модуль As-Built")
            .setItems(choices,(d,which)->{
              values[1].setText(choices[which]);
              if(which>=modules.length){
                org.json.JSONObject entry=catalog.optJSONObject(which-modules.length);
                if(entry!=null){
                  String prefix=entry.optString("address");
                  String current=values[3].getText().toString().trim().toUpperCase(Locale.US);
                  if(!current.startsWith(prefix+"-"))values[3].setText(prefix+"-01-01");
                }
              }
            }).show();
        });
        values[4].setFocusable(false);
        values[4].setClickable(true);
        values[4].setHint("Нажмите: HEX или BITS");
        values[4].setOnClickListener(v->new AlertDialog.Builder(this)
          .setTitle("Режим функции")
          .setItems(new String[]{"HEX — изменение символов","BITS — изменение битов"},(d,which)->values[4].setText(which==0?"HEX":"BITS"))
          .show());
        values[4].addTextChangedListener(new android.text.TextWatcher(){
          public void beforeTextChanged(CharSequence s,int start,int count,int after){}
          public void onTextChanged(CharSequence s,int start,int before,int count){updateModeFields.run();}
          public void afterTextChanged(android.text.Editable e){}
        });
        updateModeFields.run();
        content.addView(fields);
        TextView bitHelp=new TextView(this);
        bitHelp.setText("HEX: индексы символов и значения ВКЛ/ВЫКЛ. BITS: номер байта и номера битов 0–7; поля ВКЛ/ВЫКЛ не используются.");
        bitHelp.setTextColor(Color.rgb(190,218,228));bitHelp.setTextSize(12);
        bitHelp.setPadding(dp(6),dp(10),dp(6),dp(10));content.addView(bitHelp);
        LinearLayout actions=new LinearLayout(this);panel.addView(actions);
        final String[] actionNames={"Добавить","Изменить","Удалить"};
        final Button[] featureActions=new Button[3];
        for(int action=0;action<3;action++){
          final int kind=action;Button actionButton=new Button(this);featureActions[action]=actionButton;actionButton.setAllCaps(false);
          actionButton.setText(actionNames[action]);actionButton.setTextSize(12);
          actionButton.setMinWidth(0);actionButton.setPadding(dp(3),0,dp(3),0);
          actions.addView(actionButton,new LinearLayout.LayoutParams(0,dp(48),1));
          actionButton.setOnClickListener(v->{
            if(kind==2){
              if(chosen[0]<0||chosen[0]>=features.size()){Toast.makeText(this,"Выберите запись",Toast.LENGTH_SHORT).show();return;}
              final int index=chosen[0];
              new AlertDialog.Builder(this).setTitle("Удалить функцию?")
                .setMessage(features.get(index).id)
                .setNegativeButton("Отмена",null)
                .setPositiveButton("Удалить",(d,w)->{
                  features.remove(index);chosen[0]=-1;StudioSettings.save(this,features);resetAllEngines();view.invalidate();refreshFeatureChoices.run();redrawRef[0].run();
                }).show();return;
            }
            if(kind==1&&(chosen[0]<0||chosen[0]>=features.size())){
              Toast.makeText(this,"Выберите запись для изменения",Toast.LENGTH_SHORT).show();return;
            }
            try{
              String[] vls=new String[values.length];
              for(int j=0;j<values.length;j++)vls[j]=values[j].getText().toString().trim();
              String id=vls[0],module=vls[1].toUpperCase(java.util.Locale.ROOT),address=vls[3].toUpperCase(java.util.Locale.ROOT);
              String mode=vls[4].toUpperCase(java.util.Locale.ROOT);
              if(id.isEmpty()||address.isEmpty()||!knownFeatureModule(module)||!(mode.equals("HEX")||mode.equals("BITS")))
                throw new IllegalArgumentException("Укажите ID, модуль, строку и режим HEX/BITS");
              org.json.JSONArray catalog=customModuleCatalog();
              for(int ci=0;ci<catalog.length();ci++){
                org.json.JSONObject custom=catalog.optJSONObject(ci);
                if(custom!=null&&module.equalsIgnoreCase(custom.optString("id"))&&!address.startsWith(custom.optString("address")+"-"))
                  throw new IllegalArgumentException("Адрес строки не принадлежит выбранному блоку");
              }
              if(!address.matches("[0-9A-F]{3}-[0-9]{2}-[0-9]{2}"))
                throw new IllegalArgumentException("Адрес строки должен иметь вид 720-01-01");
              try{AbtCodec.checksum(address,"0000");}
              catch(RuntimeException badAddress){throw new IllegalArgumentException("Неверный адрес строки As-Built");}
              String indices=mode.equals("BITS")?vls[7]:vls[5];
              int byteIndex=mode.equals("BITS")?Integer.parseInt(vls[6]):0;
              java.util.List<Integer> positions=FeatureEngine.indices(indices);
              if(byteIndex<0||positions.isEmpty()||new java.util.HashSet<>(positions).size()!=positions.size())
                throw new IllegalArgumentException("Проверьте индексы: повторы недопустимы");
              for(int ci=0;ci<catalog.length();ci++){
                org.json.JSONObject custom=catalog.optJSONObject(ci);
                if(custom==null||!module.equalsIgnoreCase(custom.optString("id")))continue;
                org.json.JSONArray customRows=new org.json.JSONArray(getSharedPreferences("studio_custom_rows",MODE_PRIVATE).getString(module,"[]"));
                boolean addressFound=false;
                for(int ri=0;ri<customRows.length();ri++){
                  org.json.JSONObject item=customRows.optJSONObject(ri);
                  if(item==null||!address.equalsIgnoreCase(item.optString("address")))continue;
                  addressFound=true;
                  int hexLength=AbtCodec.norm(item.optString("value")).length();
                  int dataLength=Math.max(0,hexLength-2);
                  if(mode.equals("BITS")){
                    if(byteIndex*2+1>=dataLength)throw new IllegalArgumentException("Байт "+byteIndex+" выходит за пределы HEX-строки");
                  }else{
                    for(int position:positions)
                      if(position<0||position>=dataLength)throw new IllegalArgumentException("HEX индекс "+position+" выходит за пределы данных строки");
                  }
                }
                if(!addressFound)throw new IllegalArgumentException("Строка "+address+" отсутствует в блоке "+module+". Сначала добавьте строку.");
              }
              if(mode.equals("BITS")){
                for(int bit:positions)if(bit<0||bit>7)throw new IllegalArgumentException("Биты должны быть от 0 до 7");
                if(!vls[8].isEmpty()||!vls[9].isEmpty())
                  throw new IllegalArgumentException("В режиме BITS поля ВКЛ/ВЫКЛ оставьте пустыми: приложение устанавливает/снимает указанные биты");
              }else{
                for(int position:positions)if(position<0)throw new IllegalArgumentException("HEX индекс не может быть отрицательным");
                if(!vls[8].matches("(?i)[0-9a-f]+")||(!vls[9].isEmpty()&&!vls[9].matches("(?i)[0-9a-f]+")))
                  throw new IllegalArgumentException("ВКЛ и ВЫКЛ должны содержать только HEX-символы 0–9 и A–F");
                String on=AbtCodec.norm(vls[8]),off=AbtCodec.norm(vls[9]);
                if(!on.matches("[0-9A-F]+")||positions.size()!=on.length())
                  throw new IllegalArgumentException("Количество HEX индексов должно совпадать с длиной ВКЛ");
                if(!off.isEmpty()&&(!off.matches("[0-9A-F]+")||positions.size()!=off.length()))
                  throw new IllegalArgumentException("Количество HEX индексов должно совпадать с длиной ВЫКЛ");
              }
              int moduleIndex=java.util.Arrays.asList(modules).indexOf(module);
              if(moduleIndex>=0&&!abtRows[moduleIndex].isEmpty()){
                AbtCodec.Row matching=null;
                for(AbtCodec.Row candidate:abtRows[moduleIndex])if(candidate.address.equalsIgnoreCase(address)){matching=candidate;break;}
                if(matching==null)throw new IllegalArgumentException("Строка "+address+" отсутствует в загруженном ABT блока "+module);
                int hexLength=AbtCodec.norm(matching.value).length();
                int payloadLength=hexLength-2; // Последний байт — контрольная сумма FORScan.
                if(mode.equals("BITS")){
                  if(byteIndex*2+2>payloadLength)throw new IllegalArgumentException("Byte (BITS) выходит за данные или затрагивает checksum строки "+address);
                }else{
                  for(int position:positions)if(position>=payloadLength)throw new IllegalArgumentException("HEX индекс "+position+" выходит за данные или затрагивает checksum строки "+address);
                }
              }
              for(int j=0;j<features.size();j++)if((kind==0||j!=chosen[0])&&features.get(j).id.equals(id)&&features.get(j).module.equals(module)&&features.get(j).address.equals(address))
                throw new IllegalArgumentException("Функция с таким ID и адресом уже есть");
              FeatureEngine.Feature updated=new FeatureEngine.Feature(id,module,address,mode,indices,vls[8],vls[9],byteIndex,vls[2]);
              if(kind==0){features.add(updated);chosen[0]=features.size()-1;}else features.set(chosen[0],updated);
              StudioSettings.save(this,features);resetAllEngines();view.invalidate();refreshFeatureChoices.run();
              heading.setText((chosen[0]+1)+" · "+updated.label);
              // Keep the selected feature and all edited fields visible after saving.
              Toast.makeText(this,"Настройки функции сохранены",Toast.LENGTH_SHORT).show();
            }catch(Exception ex){Toast.makeText(this,"Ошибка: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
          });
        }
        commitCurrent[0]=()->featureActions[chosen[0]>=0?1:0].performClick();
      }
      if(selected[0]==1){
        Button chooseRowsBlock=new Button(this);
        chooseRowsBlock.setAllCaps(false);
        chooseRowsBlock.setText("Выбрать блок  ▾");
        chooseRowsBlock.setTextSize(12);
        chooseRowsBlock.setMinWidth(0);
        panel.addView(chooseRowsBlock,new LinearLayout.LayoutParams(-1,dp(42)));
        chooseRowsBlock.setOnClickListener(v->{
          org.json.JSONArray catalog=customModuleCatalog();
          String[] options=new String[modules.length+catalog.length()];
          for(int k=0;k<modules.length;k++)options[k]=modules[k]+(isFactoryModuleHidden(k)?" · скрыт":"");
          for(int k=0;k<catalog.length();k++){
            org.json.JSONObject item=catalog.optJSONObject(k);
            options[modules.length+k]=item==null?"?":item.optString("id")+" · "+item.optString("name");
          }
          new AlertDialog.Builder(this).setTitle("Строки As-Built · выберите блок")
            .setSingleChoiceItems(options,Math.min(adminRowsModule,options.length-1),(d,which)->{
              adminRowsModule=which;
              d.dismiss();
              redrawRef[0].run();
            }).setNegativeButton("Отмена",null).show();
        });
      }
      if(selected[0]==1&&adminRowsModule>=4){
        org.json.JSONObject custom=customModuleCatalog().optJSONObject(adminRowsModule-4);
        if(custom!=null){
          final String id=custom.optString("id"),prefix=custom.optString("address");
          heading.setText("Строки "+id+" · "+custom.optString("name"));
          android.content.SharedPreferences rowPrefs=getSharedPreferences("studio_custom_rows",MODE_PRIVATE);
          org.json.JSONArray storedRows;
          try{storedRows=new org.json.JSONArray(rowPrefs.getString(id,"[]"));}catch(Exception ex){storedRows=new org.json.JSONArray();}
          final org.json.JSONArray entries=storedRows;
          final int[] chosen={-1};
          LinearLayout customForm=new LinearLayout(this);customForm.setOrientation(LinearLayout.VERTICAL);
          panel.addView(customForm);
          EditText addrInput=new EditText(this);
          EditText hexInput=new EditText(this);
          EditText[] customInputs={addrInput,hexInput};
          String[] customLabels={"Строка","Значение"};
          for(int k=0;k<customInputs.length;k++){
            LinearLayout field=new LinearLayout(this);field.setOrientation(LinearLayout.HORIZONTAL);
            TextView label=new TextView(this);label.setText(customLabels[k]);label.setTextColor(Color.WHITE);
            label.setGravity(Gravity.CENTER_VERTICAL);label.setTextSize(12);
            field.addView(label,new LinearLayout.LayoutParams(0,dp(44),1));
            EditText input=customInputs[k];input.setSingleLine(true);input.setTextColor(Color.BLACK);
            input.setTextSize(14);input.setPadding(dp(7),0,dp(5),0);input.setBackgroundColor(Color.WHITE);
            field.addView(input,new LinearLayout.LayoutParams(0,dp(44),1));
            field.setPadding(dp(4),dp(3),dp(4),dp(3));customForm.addView(field);
          }
          addrInput.setHint(prefix+"-01-01");addrInput.setText(prefix+"-01-01");
          hexInput.setHint("HEX значение");
          ScrollView scroll=new ScrollView(this);LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);
          scroll.addView(list);panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(370)));
          Runnable refresh=()->{
            list.removeAllViews();
            for(int j=0;j<entries.length();j++){
              final int index=j;org.json.JSONObject item=entries.optJSONObject(j);if(item==null)continue;
              Button entry=new Button(this);entry.setAllCaps(false);entry.setText((j+1)+" · "+item.optString("address"));
              list.addView(entry,new LinearLayout.LayoutParams(-1,dp(48)));
              entry.setOnClickListener(v->{chosen[0]=index;addrInput.setText(item.optString("address"));hexInput.setText(item.optString("value"));});
            }
          };
          refresh.run();
          LinearLayout actions=new LinearLayout(this);panel.addView(actions);
          String[] labels={"Добавить","Изменить","Удалить"};
          final Button[] customRowActions=new Button[3];
          for(int action=0;action<3;action++){
            final int kind=action;Button button=new Button(this);customRowActions[action]=button;button.setAllCaps(false);button.setText(labels[action]);
            actions.addView(button,new LinearLayout.LayoutParams(0,dp(48),1));
            button.setOnClickListener(v->{
              try{
                if(kind!=0&&(chosen[0]<0||chosen[0]>=entries.length()))throw new IllegalArgumentException("Выберите строку");
                if(kind==2){
                  entries.remove(chosen[0]);chosen[0]=-1;
                }else{
                  String address=addrInput.getText().toString().trim().toUpperCase(Locale.US);
                  String hex=hexInput.getText().toString().replaceAll("\\s+","").toUpperCase(Locale.US);
                  if(!address.matches(java.util.regex.Pattern.quote(prefix)+"-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException("Неверный адрес блока");
                  if(hex.length()<4||hex.length()%2!=0||!hex.matches("[0-9A-F]+"))throw new IllegalArgumentException("Неверное HEX значение");
                  for(int j=0;j<entries.length();j++)if((kind==0||j!=chosen[0])&&address.equalsIgnoreCase(entries.optJSONObject(j).optString("address")))throw new IllegalArgumentException("Адрес уже существует");
                  if(kind==1&&!address.equalsIgnoreCase(entries.optJSONObject(chosen[0]).optString("address")))throw new IllegalArgumentException("Адрес изменять нельзя — добавьте новую строку");
                  org.json.JSONObject row=new org.json.JSONObject();row.put("address",address);row.put("value",AbtCodec.recalc(address,hex));
                  if(kind==0)entries.put(row);else entries.put(chosen[0],row);
                }
                if(!rowPrefs.edit().putString(id,entries.toString()).commit())throw new IllegalStateException("Не удалось сохранить строки");
                // Keep only administrator-edited rows in a separate exportable store.
                // Imported vehicle ABT rows must never leak into the admin snapshot.
                android.content.SharedPreferences adminRows=getSharedPreferences("studio_admin_custom_rows",MODE_PRIVATE);
                org.json.JSONArray edited=new org.json.JSONArray(adminRows.getString(id,"[]"));
                String changedAddress=kind==2?addrInput.getText().toString().trim().toUpperCase(Locale.US):
                  (kind==0?entries.optJSONObject(entries.length()-1):entries.optJSONObject(chosen[0])).optString("address");
                org.json.JSONArray updated=new org.json.JSONArray();
                for(int j=0;j<edited.length();j++){
                  org.json.JSONObject item=edited.optJSONObject(j);
                  if(item!=null&&!changedAddress.equalsIgnoreCase(item.optString("address")))updated.put(item);
                }
                if(kind!=2){
                  org.json.JSONObject changed=kind==0?entries.optJSONObject(entries.length()-1):entries.optJSONObject(chosen[0]);
                  updated.put(changed);
                }
                if(!adminRows.edit().putString(id,updated.toString()).commit())
                  throw new IllegalStateException("Не удалось сохранить строки администратора");
                resetCustomFeatureState(id);view.invalidate();refresh.run();
                Toast.makeText(this,"Строки блока "+id+" сохранены",Toast.LENGTH_SHORT).show();
              }catch(Exception ex){Toast.makeText(this,"Ошибка: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
            });
          }
        }
      }
      if(selected[0]==1&&adminRowsModule<4){
        heading.setText("Строки "+modules[adminRowsModule]+" · "+StudioSettings.moduleName(this,modules[adminRowsModule],names[adminRowsModule]));
        final int rowsModule=adminRowsModule;
        final ArrayList<AbtCodec.Row> rows=abtRows[rowsModule];
        final int[] chosen={-1};
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
        panel.addView(form);
        final EditText[] inputs=new EditText[3];
        String[] labels={"Модуль","Строка","Значение"};
        for(int k=1;k<3;k++){
          LinearLayout field=new LinearLayout(this);field.setOrientation(LinearLayout.HORIZONTAL);
          TextView label=new TextView(this);label.setText(labels[k]);label.setTextColor(Color.WHITE);
          label.setGravity(Gravity.CENTER_VERTICAL);label.setTextSize(12);field.addView(label,new LinearLayout.LayoutParams(0,dp(44),1));
          EditText input=new EditText(this);input.setSingleLine(true);
          input.setTextSize(14);input.setTextColor(Color.BLACK);
          input.setBackgroundColor(Color.WHITE);input.setPadding(dp(7),0,dp(5),0);
          field.addView(input,new LinearLayout.LayoutParams(0,dp(44),1));field.setPadding(dp(4),dp(3),dp(4),dp(3));form.addView(field);inputs[k]=input;
        }
        android.widget.ScrollView listScroll=new android.widget.ScrollView(this);
        LinearLayout entries=new LinearLayout(this);entries.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(entries);panel.addView(listScroll,new LinearLayout.LayoutParams(-1,dp(370)));
        {
          for(int n=0;n<rows.size();n++){
            final int index=n;AbtCodec.Row row=rows.get(n);
            Button entry=new Button(this);entry.setAllCaps(false);entry.setText((n+1)+" · "+row.address);
            entries.addView(entry,new LinearLayout.LayoutParams(-1,dp(48)));
            entry.setOnClickListener(v->{chosen[0]=index;
              inputs[1].setText(row.address);inputs[2].setText(row.value);});
          }
        }
        LinearLayout actions=new LinearLayout(this);panel.addView(actions);
        String[] labelsActions={"Добавить","Изменить","Удалить"};
        final Button[] rowActions=new Button[3];
        for(int action=0;action<3;action++){
          final int kind=action;Button button=new Button(this);rowActions[action]=button;button.setText(labelsActions[action]);button.setAllCaps(false);
          button.setTextSize(12);button.setMinWidth(0);button.setPadding(dp(3),0,dp(3),0);actions.addView(button,new LinearLayout.LayoutParams(0,dp(48),1));
          button.setOnClickListener(v->{
            if(kind==2){
              if(chosen[0]<0||chosen[0]>=rows.size()){Toast.makeText(this,"Выберите строку",Toast.LENGTH_SHORT).show();return;}
              final int index=chosen[0];
              new AlertDialog.Builder(this).setTitle("Удалить строку "+rows.get(index).address+"?")
                .setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)->{
                  AbtCodec.Row removed=rows.remove(index);original.remove(key(removed));saveAdminRowChange(rowsModule,removed.address,true);resetEngineForModule(rowsModule);syncFeatureChecks(rowsModule);refreshRows(rowsModule);view.invalidate();redrawRef[0].run();
                }).show();return;
            }
            try{
              String addr=inputs[1].getText().toString().trim().toUpperCase(Locale.US);
              String hex=inputs[2].getText().toString().replaceAll("\\s+","").toUpperCase(Locale.US);
              if(!addr.matches(ids[rowsModule]+"-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException("Адрес не принадлежит текущему блоку");
              if(hex.length()<4||hex.length()%2!=0||!hex.matches("[0-9A-F]+"))throw new IllegalArgumentException("Неверное значение HEX");
              String updated=AbtCodec.recalc(addr,hex);
              if(kind==0){
                for(AbtCodec.Row existing:rows)if(existing.address.equals(addr))throw new IllegalArgumentException("Строка уже существует");
                rows.add(new AbtCodec.Row(modules[rowsModule],addr,updated,Integer.parseInt(addr.split("-")[1])));
              }else{
                if(chosen[0]<0||chosen[0]>=rows.size())throw new IllegalArgumentException("Выберите строку");
                AbtCodec.Row existing=rows.get(chosen[0]);
                if(!existing.address.equals(addr))throw new IllegalArgumentException("Адрес менять нельзя — добавьте новую строку");
                if(AbtCodec.norm(existing.value).length()!=hex.length())throw new IllegalArgumentException("Длина HEX должна остаться прежней");
                existing.value=updated;
              }
              for(AbtCodec.Row current:rows)original.put(key(current),AbtCodec.norm(current.value));
              saveAdminRowChange(rowsModule,addr,false);resetEngineForModule(rowsModule);syncFeatureChecks(rowsModule);refreshRows(rowsModule);view.invalidate();redrawRef[0].run();
              Toast.makeText(this,"Строки сохранены в приложении",Toast.LENGTH_LONG).show();
            }catch(Exception ex){Toast.makeText(this,"Ошибка: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
          });
        }
        commitCurrent[0]=()->rowActions[chosen[0]>=0?1:0].performClick();
      }
      LinearLayout choices=new LinearLayout(this);choices.setOrientation(LinearLayout.VERTICAL);
      final int[] selectedModuleIndex={Math.min(active,modules.length-1)};
      final Runnable[] refreshModuleFields={null};
      if(selected[0]==2){
        final int[] moduleSelection=selectedModuleIndex;
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);
        panel.addView(fields);
        final EditText[] inputs=new EditText[4];
        String[] labels={"ID","Название блока","Адрес","Версия"};
        for(int k=0;k<4;k++){
          LinearLayout field=new LinearLayout(this);field.setOrientation(LinearLayout.HORIZONTAL);
          TextView label=new TextView(this);label.setText(labels[k]);label.setTextColor(Color.WHITE);
          label.setGravity(Gravity.CENTER_VERTICAL);label.setTextSize(12);field.addView(label,new LinearLayout.LayoutParams(0,dp(44),1));
          EditText input=new EditText(this);input.setSingleLine(true);input.setTextColor(Color.BLACK);
          input.setTextSize(14);input.setPadding(dp(8),0,dp(5),0);input.setBackgroundColor(Color.WHITE);
          field.addView(input,new LinearLayout.LayoutParams(0,dp(44),1));field.setPadding(dp(4),dp(3),dp(4),dp(3));fields.addView(field);inputs[k]=input;
        }
        Runnable fill=()->{
          inputs[0].setEnabled(moduleSelection[0]>=modules.length);
          inputs[2].setEnabled(moduleSelection[0]>=modules.length);
          int m=moduleSelection[0];
          if(m>=modules.length){
            org.json.JSONObject item=customModuleCatalog().optJSONObject(m-modules.length);
            if(item==null)return;
            inputs[0].setText(item.optString("id"));
            inputs[1].setText(item.optString("name"));
            inputs[2].setText(item.optString("address"));
            inputs[3].setText(item.optString("version"));
            heading.setText("Блок "+item.optString("id"));
            return;
          }
          inputs[0].setText(modules[m]);
          inputs[1].setText(StudioSettings.moduleName(this,modules[m],names[m]));
          inputs[2].setText(ids[m]);inputs[3].setText(StudioSettings.moduleVersion(this,modules[m]));
          heading.setText("Блок "+modules[m]);
        };
        refreshModuleFields[0]=fill;
        fill.run();
        panel.addView(choices,new LinearLayout.LayoutParams(-1,-2));
        for(int m=0;m<modules.length;m++){
          final int index=m;Button choose=new Button(this);choose.setAllCaps(false);
          choose.setText((m+1)+" · "+modules[m]+" · "+names[m]+(isFactoryModuleHidden(m)?" [СКРЫТ]":""));
          choose.setTextSize(12);choose.setMinWidth(0);choose.setPadding(dp(5),0,dp(5),0);
          choices.addView(choose,new LinearLayout.LayoutParams(-1,dp(42)));
          choose.setOnClickListener(v->{moduleSelection[0]=index;fill.run();});
        }
        TextView warning=new TextView(this);
        warning.setText("ID и HEX-адрес определяют распознавание ABT. Изменение адреса требует переноса строк и функций.");
        warning.setTextColor(Color.LTGRAY);warning.setTextSize(12);panel.addView(warning);
        Button saveModule=new Button(this);saveModule.setAllCaps(false);saveModule.setText("Сохранить настройки блока");
        saveModule.setTextSize(12);saveModule.setMinWidth(0);saveModule.setPadding(dp(4),0,dp(4),0);
        panel.addView(saveModule,new LinearLayout.LayoutParams(-1,dp(46)));
        saveModule.setOnClickListener(v->{
          String newName=inputs[1].getText().toString().trim();
          if(newName.isEmpty()){inputs[1].setError("Название не может быть пустым");return;}
          int m=moduleSelection[0];
          if(m>=modules.length){
            org.json.JSONArray catalog=customModuleCatalog();
            org.json.JSONObject item=catalog.optJSONObject(m-modules.length);
            if(item==null){Toast.makeText(this,"Блок не найден",Toast.LENGTH_LONG).show();return;}
            try{
              String oldId=item.optString("id"),oldAddress=item.optString("address");
              String newId=inputs[0].getText().toString().trim().toUpperCase(Locale.US);
              String newAddress=inputs[2].getText().toString().trim().toUpperCase(Locale.US);
              if(!newId.matches("[A-Z0-9_]{2,12}")||!newAddress.matches("[0-9A-F]{3}"))throw new IllegalArgumentException("ID или HEX-адрес указан неверно");
              if(!newId.equals(oldId)||!newAddress.equals(oldAddress)){
                for(int k=0;k<modules.length;k++)if(modules[k].equalsIgnoreCase(newId)||ids[k].equalsIgnoreCase(newAddress))throw new IllegalArgumentException("ID или адрес занят заводским блоком");
                for(int k=0;k<catalog.length();k++)if(k!=m-modules.length){
                  org.json.JSONObject other=catalog.optJSONObject(k);
                  if(other!=null&&(newId.equalsIgnoreCase(other.optString("id"))||newAddress.equalsIgnoreCase(other.optString("address"))))throw new IllegalArgumentException("ID или адрес уже занят");
                }
                org.json.JSONArray existingRows=new org.json.JSONArray(getSharedPreferences("studio_custom_rows",MODE_PRIVATE).getString(oldId,"[]"));
                if(existingRows.length()>0)throw new IllegalArgumentException("Сначала удалите HEX-строки блока перед сменой ID или адреса");
                if(!newId.equals(oldId)){
                  String oldFeatures=getSharedPreferences("studio_custom_features",MODE_PRIVATE).getString(oldId,"[]");
                  if(!"[]".equals(oldFeatures))throw new IllegalArgumentException("Сначала удалите функции блока перед сменой ID");
                  getSharedPreferences("studio_custom_rows",MODE_PRIVATE).edit().remove(oldId).apply();
                  resetCustomFeatureState(oldId);
                }
                item.put("id",newId);item.put("address",newAddress);
              }
              item.put("name",newName);
              item.put("version",inputs[3].getText().toString().trim());
              getSharedPreferences("studio_custom_modules",MODE_PRIVATE).edit().putString("catalog",catalog.toString()).apply();
              view.invalidate();
              Toast.makeText(this,"Блок "+item.optString("id")+" сохранён",Toast.LENGTH_SHORT).show();
            }catch(Exception ex){Toast.makeText(this,"Ошибка сохранения: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
            return;
          }
          StudioSettings.saveModule(this,modules[m],newName,inputs[3].getText().toString().trim());
          view.invalidate();Toast.makeText(this,"Блок "+modules[m]+" сохранён",Toast.LENGTH_SHORT).show();
        });
      }

      // Custom module catalog: persistent admin CRUD, separate from the protected factory modules.
      if(selected[0]==2){
        LinearLayout customActions=new LinearLayout(this);customActions.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(customActions,new LinearLayout.LayoutParams(-1,dp(48)));
        Button addCustom=new Button(this);addCustom.setAllCaps(false);addCustom.setText("Добавить блок");addCustom.setTextSize(12);
        customActions.addView(addCustom,new LinearLayout.LayoutParams(0,-1,1));
        Button removeCustom=new Button(this);removeCustom.setAllCaps(false);removeCustom.setText("Удалить блок");removeCustom.setTextSize(12);
        customActions.addView(removeCustom,new LinearLayout.LayoutParams(0,-1,1));
        final android.content.SharedPreferences customPrefs=getSharedPreferences("studio_custom_modules",MODE_PRIVATE);
        // Keep factory entries and render every custom module as a real scrollable list item.
        final int factoryEntryCount=choices.getChildCount();
        final Runnable[] updateCatalog={null};
        updateCatalog[0]=()->{
          while(choices.getChildCount()>factoryEntryCount)
            choices.removeViewAt(choices.getChildCount()-1);
          org.json.JSONArray catalog=customModuleCatalog();
          for(int i=0;i<catalog.length();i++){
            org.json.JSONObject item=catalog.optJSONObject(i);
            if(item==null)continue;
            String id=item.optString("id"), name=item.optString("name");
            Button entry=new Button(this);entry.setAllCaps(false);entry.setTextSize(12);
            entry.setText((factoryEntryCount+i+1)+" · "+id+" · "+name);
            entry.setMinWidth(0);entry.setPadding(dp(5),0,dp(5),0);
            choices.addView(entry,new LinearLayout.LayoutParams(-1,dp(42)));
            final int customIndex=i;
            entry.setOnClickListener(v->{
              selectedModuleIndex[0]=modules.length+customIndex;
              if(refreshModuleFields[0]!=null)refreshModuleFields[0].run();
            });

          }
        };
        updateCatalog[0].run();
        addCustom.setOnClickListener(v->{
          LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(16),dp(8),dp(16),dp(8));
          final EditText[] edits=new EditText[4];
          String[] hints={"ID блока (например AFS)","Название блока","Адрес (3 HEX-символа)","Версия (необязательно)"};
          for(int i=0;i<edits.length;i++){
            edits[i]=new EditText(this);edits[i].setSingleLine(true);edits[i].setHint(hints[i]);form.addView(edits[i]);
          }
          new AlertDialog.Builder(this).setTitle("Добавить блок").setView(form)
            .setNegativeButton("Отмена",null).setPositiveButton("Добавить",(d,w)->{
              String id=edits[0].getText().toString().trim().toUpperCase(Locale.US);
              String name=edits[1].getText().toString().trim();
              String address=edits[2].getText().toString().trim().toUpperCase(Locale.US);
              String version=edits[3].getText().toString().trim();
              if(!id.matches("[A-Z0-9_]{2,12}")||name.isEmpty()||!address.matches("[0-9A-F]{3}")){
                Toast.makeText(this,"Укажите ID, название и трёхзначный HEX-адрес",Toast.LENGTH_LONG).show();return;
              }
              try{
                org.json.JSONArray catalog=new org.json.JSONArray(customPrefs.getString("catalog","[]"));
                for(String factory:modules)if(factory.equalsIgnoreCase(id)){Toast.makeText(this,"ID уже занят",Toast.LENGTH_SHORT).show();return;}
                for(String factoryAddress:ids)if(factoryAddress.equalsIgnoreCase(address)){Toast.makeText(this,"Адрес уже занят",Toast.LENGTH_SHORT).show();return;}
                for(int i=0;i<catalog.length();i++){
                  org.json.JSONObject existing=catalog.getJSONObject(i);
                  if(id.equalsIgnoreCase(existing.optString("id"))||address.equalsIgnoreCase(existing.optString("address"))){
                    Toast.makeText(this,"ID или адрес уже существует",Toast.LENGTH_SHORT).show();return;
                  }
                }
                org.json.JSONObject item=new org.json.JSONObject();
                item.put("id",id);item.put("name",name);item.put("address",address);item.put("version",version);
                catalog.put(item);customPrefs.edit().putString("catalog",catalog.toString()).apply();
                // Select and reveal the newly created module immediately.
                active=4+catalog.length()-1;
                saveSelectedModule();
                view.revealActiveTab();
                updateCatalog[0].run();view.invalidate();
                Toast.makeText(this,"Блок "+id+" добавлен. Его вкладка доступна сверху.",Toast.LENGTH_LONG).show();
              }catch(Exception ex){Toast.makeText(this,"Ошибка сохранения блока",Toast.LENGTH_SHORT).show();}
            }).show();
        });
        removeCustom.setOnClickListener(v->{
          try{
            org.json.JSONArray catalog=customModuleCatalog();
            int index=selectedModuleIndex[0]-modules.length;
            if(index<0){
              Toast.makeText(this,"Заводской блок нельзя удалить без переноса его ABT-строк и функций",Toast.LENGTH_LONG).show();
              return;
            }
            org.json.JSONObject selectedItem=catalog.optJSONObject(index);
            if(selectedItem==null){Toast.makeText(this,"Выберите блок для удаления",Toast.LENGTH_SHORT).show();return;}
            final String removedId=selectedItem.optString("id");
            new AlertDialog.Builder(this).setTitle("Удалить блок "+removedId+"?")
              .setMessage("Блок исчезнет из вкладок и каталога. Его сохранённые HEX-строки останутся в памяти приложения.")
              .setNegativeButton("Отмена",null)
              .setPositiveButton("Удалить",(d,w)->{
                try{
                  org.json.JSONArray current=customModuleCatalog();
                  int removeIndex=-1;
                  for(int j=0;j<current.length();j++)if(removedId.equals(current.optJSONObject(j).optString("id"))){removeIndex=j;break;}
                  if(removeIndex<0)throw new IllegalStateException("Блок не найден");
                  org.json.JSONArray updated=new org.json.JSONArray();
                  for(int j=0;j<current.length();j++)if(j!=removeIndex)updated.put(current.get(j));
                  String activeId="";
                  if(active>=modules.length){
                    org.json.JSONObject activeItem=current.optJSONObject(active-modules.length);
                    if(activeItem!=null)activeId=activeItem.optString("id");
                  }
                  if(!customPrefs.edit().putString("catalog",updated.toString()).commit())throw new IllegalStateException("Не удалось сохранить каталог");
                  if(active>=modules.length){
                    active=0;
                    for(int j=0;j<updated.length();j++)if(activeId.equals(updated.optJSONObject(j).optString("id")))active=modules.length+j;
                    saveSelectedModule();
                  }
                  selectedModuleIndex[0]=0;
                  if(refreshModuleFields[0]!=null)refreshModuleFields[0].run();
                  customScroll.remove(removedId);customFeaturePage.remove(removedId);resetCustomFeatureState(removedId);
                  view.moduleTabOffset=Math.min(view.moduleTabOffset,Math.max(0f,(modules.length+updated.length())*90f-370f));
                  view.revealActiveTab();updateCatalog[0].run();view.invalidate();
                  Toast.makeText(this,"Блок "+removedId+" удалён из списка",Toast.LENGTH_SHORT).show();
                }catch(Exception ex){Toast.makeText(this,"Ошибка удаления: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
              }).show();
          }catch(Exception ex){Toast.makeText(this,"Ошибка каталога: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
        });
      }
      if(selected[0]==3){
        final String[] keys={"title","feature_card_width","feature_card_height","open_text","save_text","about_description","warning_title","warning_body","creator1_role","creator1_name","creator1_url","creator2_role","creator2_name","creator2_url"};
        final String[] labels={"Название окна","Ширина кнопок функций (100–180)","Высота кнопок функций (32–63)","Кнопка открытия","Кнопка сохранения","Описание программы","Заголовок предупреждения","Текст предупреждения","Автор 1 — подпись","Автор 1 — имя","Автор 1 — ссылка","Автор 2 — подпись","Автор 2 — имя","Автор 2 — ссылка"};
        final String[] defaults={"M6GH AS-BUILT CONFIG","177","57","Открыть ABT","Сохранить ABT","Редактор As-Built для Mazda 6 GH","⚠️WARNING⚠️","НЕ ЗАБЫВАЙТЕ ДЕЛАТЬ КОПИЮ СКАЧЕННОГО ФАЙЛА НАСТРОЕК ИЗ FORSCAN.","Разработчик","Dim304","https://www.drive2.ru/users/dim304/","Помощник","Wolis11","https://www.drive2.ru/users/wolis11/"};
        android.widget.ScrollView appearanceScroll=new android.widget.ScrollView(this);
        LinearLayout appearanceFields=new LinearLayout(this);appearanceFields.setOrientation(LinearLayout.VERTICAL);
        appearanceScroll.addView(appearanceFields);panel.addView(appearanceScroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView splashLabel=new TextView(this);
        splashLabel.setText("Заставка при запуске (1,5 секунды)");
        splashLabel.setTextColor(Color.WHITE);splashLabel.setTextSize(13);
        appearanceFields.addView(splashLabel);
        Button chooseSplash=new Button(this);
        chooseSplash.setText("Выбрать изображение PNG/JPG");
        chooseSplash.setAllCaps(false);
        chooseSplash.setOnClickListener(v->{
          Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);
          pick.setType("image/*");
          pick.addCategory(Intent.CATEGORY_OPENABLE);
          startActivityForResult(pick,SPLASH_PICK_REQUEST);
        });
        appearanceFields.addView(chooseSplash);
        TextView splashStatus=new TextView(this);
        splashStatus.setText(splashFile().isFile()?"Пользовательская заставка установлена ("+(splashFile().length()/1024)+" КБ)":"Используется стандартная заставка");
        splashStatus.setTextColor(Color.LTGRAY);
        appearanceFields.addView(splashStatus);
        final EditText[] appearanceInputs=new EditText[keys.length];
        for(int k=0;k<keys.length;k++){
          TextView label=new TextView(this);label.setText(labels[k]);label.setTextColor(Color.WHITE);label.setTextSize(13);
          label.setPadding(dp(5),dp(7),dp(5),dp(3));appearanceFields.addView(label);
          EditText input=new EditText(this);
          boolean isDescription="about_description".equals(keys[k]) || "warning_body".equals(keys[k]);
          input.setSingleLine(!isDescription);
          if(isDescription){
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            input.setGravity(Gravity.TOP|Gravity.START);
            input.setMinLines(4);
          }
          input.setTextSize(14);
          input.setText(StudioSettings.appearance(this,keys[k],defaults[k]));
          input.setBackgroundColor(Color.WHITE);input.setTextColor(Color.BLACK);input.setPadding(dp(8),0,dp(6),0);
          appearanceFields.addView(input,new LinearLayout.LayoutParams(-1,isDescription?dp(112):dp(42)));appearanceInputs[k]=input;
        }
        TextView note=new TextView(this);note.setText("Цвета: #RRGGBB, пустое поле — стандартный цвет. Количество колонок функций: 1–10. Название, кнопки и данные авторов применяются после сохранения.");
        note.setText("Цвета: #RRGGBB; размеры кнопок по умолчанию 177 × 57. Две колонки сохраняются. Заставка сохраняется сразу после выбора файла.");
        note.setTextColor(Color.LTGRAY);appearanceFields.addView(note);
        Button saveAppearance=new Button(this);saveAppearance.setText("Сохранить оформление");saveAppearance.setAllCaps(false);
        saveAppearance.setTextSize(12);saveAppearance.setMinWidth(0);saveAppearance.setPadding(dp(4),0,dp(4),0);
        commitCurrent[0]=()->saveAppearance.performClick();
        saveAppearance.setOnClickListener(v->{
          try{
            java.util.HashMap<String,String> changes=new java.util.HashMap<>();
            for(int k=0;k<keys.length;k++)changes.put(keys[k],appearanceInputs[k].getText().toString());
            int cardWidth=Integer.parseInt(changes.get("feature_card_width").trim());
            int cardHeight=Integer.parseInt(changes.get("feature_card_height").trim());
            if(cardWidth<100||cardWidth>180)throw new IllegalArgumentException("Ширина: 100–180");
            if(cardHeight<32||cardHeight>63)throw new IllegalArgumentException("Высота: 32–63");
            final java.util.HashMap<String,String> confirmedChanges=new java.util.HashMap<>(changes);
            final EditText pinInput=new EditText(this);
            pinInput.setSingleLine(true);
            pinInput.setHint("4 цифры");
            pinInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            pinInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
            final LinearLayout pinContainer=new LinearLayout(this);
            pinContainer.setOrientation(LinearLayout.VERTICAL);
            pinContainer.setPadding(dp(22),dp(8),dp(22),dp(4));
            pinContainer.addView(pinInput,new LinearLayout.LayoutParams(-1,dp(52)));
            final AlertDialog confirmDialog=new AlertDialog.Builder(this)
              .setTitle("Подтверждение сохранения")
              .setMessage("Введите PIN-код для сохранения оформления")
              .setView(pinContainer)
              .setNegativeButton("Отмена",null)
              .setPositiveButton("Подтвердить",null)
              .create();
            confirmDialog.setOnShowListener(ignored->{
              confirmDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
                if(!"0898".equals(pinInput.getText().toString())){
                  pinInput.setError("Неверный PIN-код");
                  return;
                }
                try{
                  StudioSettings.saveAppearance(this,confirmedChanges);
                  setTitle(StudioSettings.appearance(this,"title","M6GH AS-BUILT CONFIG"));
                  view.invalidate();
                  Toast.makeText(this,"Оформление сохранено",Toast.LENGTH_SHORT).show();
                  confirmDialog.dismiss();
                }catch(Exception saveError){
                  Toast.makeText(this,"Ошибка оформления: "+saveError.getMessage(),Toast.LENGTH_LONG).show();
                }
              });
            });
            confirmDialog.show();
          }catch(Exception ex){Toast.makeText(this,"Ошибка оформления: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
        });
      }
      for(int i=0;i<buttons.length;i++){buttons[i].setAlpha(i==selected[0]?1f:.72f);if(underlines[i]!=null)underlines[i].setVisibility(i==selected[0]?View.VISIBLE:View.INVISIBLE);}
    };
    redrawRef[0]=redraw;
    for(int i=0;i<names.length;i++){
      final int tab=i;Button b=new Button(this);b.setAllCaps(false);b.setText(names[i]);
      b.setTextSize(10);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(2),0,dp(2),0);
      LinearLayout tabBox=new LinearLayout(this);tabBox.setOrientation(LinearLayout.VERTICAL);
      tabBox.addView(b,new LinearLayout.LayoutParams(-1,dp(45)));
      View underline=new View(this);underline.setBackgroundColor(Color.rgb(40,170,210));underline.setVisibility(i==0?View.VISIBLE:View.INVISIBLE);
      tabBox.addView(underline,new LinearLayout.LayoutParams(-1,dp(3)));
      int tabWidth=Math.max(dp(72),(getResources().getDisplayMetrics().widthPixels-dp(24))/4);
      tabs.addView(tabBox,new LinearLayout.LayoutParams(tabWidth,dp(49)));
      buttons[i]=b;underlines[i]=underline;b.setOnClickListener(v->{selected[0]=tab;redrawRef[0].run();});
    }
    Button faqTab=new Button(this);
    faqTab.setText("FAQ");faqTab.setAllCaps(false);faqTab.setTextSize(11);
    faqTab.setOnClickListener(v->FaqEditor.open(this));
    tabs.addView(faqTab,new LinearLayout.LayoutParams(dp(72),dp(49)));
    LinearLayout footer=new LinearLayout(this);
    footerCloseLayout[0]=footer;
    Button save=new Button(this);save.setText("Сохранить изменения");save.setAllCaps(false);
    footer.addView(save,new LinearLayout.LayoutParams(0,dp(52),1));
    footerSave[0]=save;
    save.setOnClickListener(v->{if(commitCurrent[0]!=null)commitCurrent[0].run();else Toast.makeText(this,"Для сохранения используйте кнопку в выбранной вкладке",Toast.LENGTH_LONG).show();});
    Button close=new Button(this);close.setText("Закрыть");close.setAllCaps(false);
    footer.addView(close,new LinearLayout.LayoutParams(0,dp(52),1));close.setOnClickListener(v->dialog.dismiss());
    root.addView(footer);
    save.setVisibility(View.GONE);
    dialog.setContentView(root);
    android.view.Window window=dialog.getWindow();
    if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);window.setLayout(-1,-1);}
    redrawRef[0].run();dialog.show();
    window=dialog.getWindow();if(window!=null)window.setLayout((int)(getResources().getDisplayMetrics().widthPixels*.97f),(int)(getResources().getDisplayMetrics().heightPixels*.87f));
  }
  void showAppearanceAdmin(){
    new AlertDialog.Builder(this).setTitle("Оформление")
      .setMessage("Раздел оформления из Run #89 переносится отдельно. Действующие настройки функций и ABT остаются без изменений.")
      .setPositiveButton("Назад к вкладкам",(d,w)->showAdminTabs()).show();
  }
  void admin(){if(adminSession)showAdminTabs();else modal(true);}
  void about(){modal(false);}
}
