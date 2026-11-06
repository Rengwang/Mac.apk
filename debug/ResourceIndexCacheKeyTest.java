import android.content.res.Configuration;
import android.os.LocaleList;
import java.lang.reflect.*;
import java.util.Locale;
public class ResourceIndexCacheKeyTest {
 public static void main(String[] a) throws Exception {
  Class<?> b=Class.forName("com.macapk.loader.Boot"); Field f=b.getDeclaredField("sApkPath");f.setAccessible(true);f.set(null,"/fixture.apk");
  Method m=b.getDeclaredMethod("macapkResourceIndexCacheKey",Configuration.class);m.setAccessible(true);
  Configuration c=Configuration.macapkPhoneDefaults();c.screenWidthDp=360;c.setLocales(new LocaleList(Locale.ENGLISH,Locale.FRENCH));
  Object k=m.invoke(null,c); Configuration equal=new Configuration(c);Object k2=m.invoke(null,equal);
  if(!k.equals(k2)||k.hashCode()!=k2.hashCode())throw new AssertionError("equal configs must hit");
  c.screenWidthDp=720; if(k.equals(m.invoke(null,c)))throw new AssertionError("mutation must miss");
  equal.setLocales(new LocaleList(Locale.ENGLISH,Locale.GERMAN));if(k.equals(m.invoke(null,equal)))throw new AssertionError("secondary locale must miss");
  System.out.println("PASS: equal configuration hits, mutable size and secondary locale miss");
 }
}
