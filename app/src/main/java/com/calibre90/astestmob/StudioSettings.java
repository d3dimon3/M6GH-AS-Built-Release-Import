package com.calibre90.astestmob;
import android.content.Context;
import org.json.*;
import java.util.*;
/** Local Run89-style editable feature settings. */
public final class StudioSettings {
  private static final String PREF="run35_settings";
  public static ArrayList<FeatureEngine.Feature> load(Context ctx){
    // Customer Studio always uses the latest verified server definitions when available.
    // Keep local administrator drafts authoritative only in the standalone Admin app.
    if(!"com.calibre90.catalogadmin".equals(ctx.getPackageName()) && PublishedFullSettings.features(ctx)!=null)
      return published(ctx);
    String data=ctx.getSharedPreferences(PREF,0).getString("features","");
    if(data.isEmpty())return published(ctx);
    try{
      JSONArray a=new JSONArray(data);ArrayList<FeatureEngine.Feature> out=new ArrayList<>();
      for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);
        out.add(new FeatureEngine.Feature(o.getString("id"),o.getString("module"),o.getString("row"),o.getString("mode"),o.getString("indices"),o.getString("on"),o.optString("off",""),o.optInt("byte",0),o.optString("label",o.getString("id"))));
      }return out;
    }catch(Exception e){return published(ctx);}
  }
  /** Read-only published catalog cached from a trusted sync. Local admin edits still take precedence. */
  public static ArrayList<FeatureEngine.Feature> published(Context ctx){
    JSONArray full=PublishedFullSettings.features(ctx);
    String data=full==null?ctx.getSharedPreferences("studio_published_catalog",0).getString("features",""):full.toString();
    if(data.isEmpty())return defaults();
    try{
      JSONArray a=new JSONArray(data);ArrayList<FeatureEngine.Feature> out=new ArrayList<>();
      for(int i=0;i<a.length();i++){
        JSONObject o=a.getJSONObject(i);
        out.add(new FeatureEngine.Feature(o.getString("id"),o.getString("module"),o.getString("row"),
          o.getString("mode"),o.getString("indices"),o.getString("on"),
          o.optString("off",""),o.optInt("byte",0),o.optString("label",o.getString("id"))));
      }
      return out;
    }catch(Exception ex){return defaults();}
  }
  /** Explicit administrator draft stays local until the separate publisher is ready. */
  public static boolean hasLocalDraft(Context ctx){
    return !ctx.getSharedPreferences(PREF,0).getString("features","").isEmpty();
  }
  public static ArrayList<FeatureEngine.Feature> defaults(){
    ArrayList<FeatureEngine.Feature> out=new ArrayList<>();
    out.add(new FeatureEngine.Feature("rvm","IC","720-01-02","HEX","0","8","0",0));
    out.add(new FeatureEngine.Feature("new_feature","IC","720-01-01","HEX","2,3","40","00",0));
    out.add(new FeatureEngine.Feature("keyless","IC","720-01-01","HEX","0,1","2B","1F",0));
    out.add(new FeatureEngine.Feature("lights","BCM","726-01-01","HEX","4,5","80","00",0));
    out.add(new FeatureEngine.Feature("turn","BCM","726-02-01","HEX","1","8","0",0));
    out.add(new FeatureEngine.Feature("freq","RKE","731-01-01","HEX","2,3","40","00",0));
    out.add(new FeatureEngine.Feature("transmission","RKE","731-01-01","HEX","4,5","38","00",0));
    out.add(new FeatureEngine.Feature("abs_keyless","ABS","760-01-01","HEX","2","8","0",0));
    return out;
  }
  public static String moduleName(Context ctx,String id,String fallback){
    String local=ctx.getSharedPreferences(PREF,0).getString("module_name_"+id,null);
    String published=PublishedFullSettings.moduleSetting(ctx,"module_name_"+id);
    if(!"com.calibre90.catalogadmin".equals(ctx.getPackageName()) && published!=null)return published;
    return local==null?(published==null?fallback:published):local;
  }
  public static String moduleVersion(Context ctx,String id){
    String local=ctx.getSharedPreferences(PREF,0).getString("module_version_"+id,null);
    String published=PublishedFullSettings.moduleSetting(ctx,"module_version_"+id);
    if(!"com.calibre90.catalogadmin".equals(ctx.getPackageName()) && published!=null)return published;
    return local==null?(published==null?"":published):local;
  }
  public static void saveModule(Context ctx,String id,String name,String version){
    if(!ctx.getSharedPreferences(PREF,0).edit()
      .putString("module_name_"+id,name).putString("module_version_"+id,version).commit())
      throw new IllegalStateException("Module settings not saved");
  }
  public static String appearance(Context ctx,String key,String fallback){
    String local=ctx.getSharedPreferences(PREF,0).getString("appearance_"+key,null);
    // In Studio, signed server appearance overrides stale local values left by older builds.
    // In the separate Admin app, locally edited draft values must remain editable.
    if(!"com.calibre90.catalogadmin".equals(ctx.getPackageName())){
      String published=PublishedFullSettings.moduleSetting(ctx,"appearance_"+key);
      if(published!=null)return published;
    }
    return local==null?fallback:local;
  }
  public static void saveAppearance(Context ctx,Map<String,String> values){
    android.content.SharedPreferences.Editor edit=ctx.getSharedPreferences(PREF,0).edit();
    for(Map.Entry<String,String> entry:values.entrySet())edit.putString("appearance_"+entry.getKey(),entry.getValue());
    if(!edit.commit())throw new IllegalStateException("Appearance settings not saved");
  }
  public static void save(Context ctx,List<FeatureEngine.Feature> list){
    JSONArray a=new JSONArray();
    try{
      for(FeatureEngine.Feature f:list){
        JSONObject o=new JSONObject();o.put("id",f.id);o.put("module",f.module);o.put("row",f.address);
        o.put("mode",f.mode);o.put("indices",f.indices);o.put("on",f.on);o.put("off",f.off);o.put("byte",f.byteIndex);o.put("label",f.label);a.put(o);
      }
    }catch(JSONException e){throw new IllegalStateException(e);}
    if(!ctx.getSharedPreferences(PREF,0).edit().putString("features",a.toString()).commit())throw new IllegalStateException("Settings not saved");
  }
}
