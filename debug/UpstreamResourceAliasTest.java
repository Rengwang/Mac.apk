import com.macapk.loader.DomXmlResourceParser;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.xmlpull.v1.XmlPullParser;

/** A shrunk XML attribute retains both its document spelling and resource identity. */
public class UpstreamResourceAliasTest {
    public static void main(String[] args) throws Exception {
        String xml = "<Transition xmlns:app='http://schemas.android.com/apk/res-auto' "
            + "xmlns:macapkAlias='http://macapk/axml-alias' xmlns:macapkAttrId='http://macapk/axml-attrid' "
            + "app:constraintSetStart='@123' macapkAlias:APKTOOL_RENAMED_0x7f040123='@123' "
            + "macapkAttrId:constraintSetStart='2130968867'/>";
        DomXmlResourceParser parser = new DomXmlResourceParser(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        while (parser.next() != XmlPullParser.START_TAG) {}
        if (parser.getAttributeCount() != 1) throw new AssertionError("internal metadata leaked into public attrs: " + parser.getAttributeCount());
        if (!"@123".equals(parser.getAttributeValue(null, "constraintSetStart"))) throw new AssertionError("document name lost");
        if (!"@123".equals(parser.getAttributeValue(null, "APKTOOL_RENAMED_0x7f040123"))) throw new AssertionError("resource alias lost");
        if (parser.getAttributeNameResource(0) != 0x7f040123) throw new AssertionError("resource identity lost: " + parser.getAttributeNameResource(0));
        System.out.println("PASS: shrunk XML document spelling, table alias and resource ID");
    }
}
