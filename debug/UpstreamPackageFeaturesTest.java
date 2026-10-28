import android.content.pm.*;
import com.macapk.loader.*;
import java.io.File;
/** Compare archive metadata to the parsed real manifest with and without GET_CONFIGURATIONS. */
public class UpstreamPackageFeaturesTest {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("APK path required");
    Manifest m = Manifest.parse(new File(args[0]), args[0]);
    if (m.reqFeatures.isEmpty()) throw new AssertionError("fixture has no features");
    PackageManager pm = new MacApkPackageManager("example.test");
    PackageInfo without = pm.getPackageArchiveInfo(args[0], 0);
    PackageInfo with = pm.getPackageArchiveInfo(args[0], PackageManager.GET_CONFIGURATIONS);
    if (without == null || with == null || without.reqFeatures != null || with.reqFeatures == null || with.reqFeatures.length != m.reqFeatures.size()) throw new AssertionError("feature flags or count differ");
    for (int i=0; i<with.reqFeatures.length; i++) {
      Manifest.FeatureEntry e=m.reqFeatures.get(i); FeatureInfo f=with.reqFeatures[i];
      if (!java.util.Objects.equals(e.name,f.name) || e.version!=f.version || e.reqGlEsVersion!=f.reqGlEsVersion || e.required != ((f.flags & FeatureInfo.FLAG_REQUIRED)!=0)) throw new AssertionError("feature mismatch at " + i);
    }
    System.out.println("PASS: manifest feature metadata and GET_CONFIGURATIONS, count="+with.reqFeatures.length);
  }
}
