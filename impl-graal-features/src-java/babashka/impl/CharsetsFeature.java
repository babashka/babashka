package babashka.impl;

import java.nio.charset.Charset;

import org.graalvm.nativeimage.hosted.Feature;

import com.oracle.svm.hosted.jdk.localization.LocalizationFeature;

/**
 * GraalVM native-image Feature that adds extra charsets to the native image.
 *
 * By default GraalVM only includes 7 standard charsets:
 *   US-ASCII, ISO-8859-1, UTF-8, UTF-16, UTF-16BE, UTF-16LE,
 *   and the system default (typically UTF-8).
 *
 * This feature adds additional charsets as needed, without using
 * AddAllCharsets which includes all 173 charsets and adds ~5MB to the
 * binary due to large CJK encoding tables.
 *
 * To add a new charset, append its name to the EXTRA_CHARSETS array
 * and rebuild the jar with: clojure -T:build install
 */
public class CharsetsFeature implements Feature {

    private static final String[] EXTRA_CHARSETS = {
        "IBM437",  // cp437
    };

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        for (String name : EXTRA_CHARSETS) {
            LocalizationFeature.addCharset(Charset.forName(name));
        }
    }
}
