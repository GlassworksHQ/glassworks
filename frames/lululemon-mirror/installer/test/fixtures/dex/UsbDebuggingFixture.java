// Our own stand-in for the shape of the Glass Panes UsbDebuggingManager check, compiled to
// classes.dex by build.sh so the services.jar patch is tested on real d8 output: const-string, a
// static String value, annotations with String elements, debug locals, switch and array payloads,
// and catch handlers, with strings on both sides of every slot the fingerprint can move to.
package dev.glassworks.fixture;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
@interface Note {
    String value();
    String[] more() default {};
}

@Note(value = "Bravo note", more = {"Echo", "0 zero", "F last", "z last"})
public class UsbDebuggingFixture {
    static final String OWNER = "D1:13:60:89:B6:16:00:75:63:92:56:96:F5:46:42:4A";
    static final String EARLY = "0 early";
    static String mutable = "Alpha";

    @Note("Charlie field")
    public String field = "Delta";

    @Note("Foxtrot method")
    public static boolean trusted(String fingerprints, int mode) {
        String local = "1 local";
        switch (mode) {
            case 0: local = "9 nine"; break;
            case 1: local = "A first"; break;
            case 2: local = "D"; break;
            case 3: local = "D: "; break;
            case 4: local = "E later"; break;
            case 5: local = "FF:"; break;
            default: local = "Hotel";
        }
        switch (mode * 1000) {
            case 7000: local = local + "India"; break;
            case 90000: local = local + "Juliet"; break;
            case 1234567: local = local + "Kilo"; break;
        }
        int[] table = {1, 2, 3, 4, 5, 6, 7, 8};
        String tail;
        try {
            tail = mutable.substring(mode);
        } catch (StringIndexOutOfBoundsException e) {
            tail = "Golf " + e.getMessage();
        } catch (RuntimeException e) {
            tail = "Lima";
        } finally {
            local = local + "Mike";
        }
        if (!OWNER.equals(fingerprints)) {
            System.out.println("not the owner: " + local + tail);
            return false;
        }
        System.out.println("Authorizing the Mirror owner's ADB key");
        return table.length > 0;
    }
}
