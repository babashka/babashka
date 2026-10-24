package babashka.impl;

import java.security.cert.X509Certificate;
import java.util.Properties;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/** Extends the JDK blocked.certs check with the keys in BlockedKeys. */
@TargetClass(className = "sun.security.util.UntrustedCertificates")
final class Target_sun_security_util_UntrustedCertificates {

    @Alias
    private static Properties props;

    @Substitute
    public static boolean isUntrusted(X509Certificate cert) {
        return BlockedKeys.isUntrusted(cert, props);
    }
}
