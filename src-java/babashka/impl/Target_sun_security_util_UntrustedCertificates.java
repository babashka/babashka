package babashka.impl;

import java.security.cert.X509Certificate;
import java.util.Properties;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

@TargetClass(className = "sun.security.util.Debug")
final class Target_sun_security_util_Debug {
}

@TargetClass(className = "sun.security.x509.X509CertImpl")
final class Target_sun_security_x509_X509CertImpl {

    @Alias
    public static native String getFingerprint(String algorithm, X509Certificate cert,
                                               Target_sun_security_util_Debug debug);
}

/** Runs the JDK blocked.certs check after the key check in BlockedKeys. */
@TargetClass(className = "sun.security.util.UntrustedCertificates")
final class Target_sun_security_util_UntrustedCertificates {

    @Alias
    private static Target_sun_security_util_Debug debug;

    @Alias
    private static Properties props;

    @Alias
    private static String algorithm;

    @Substitute
    public static boolean isUntrusted(X509Certificate cert) {
        if (BlockedKeys.isBlocked(cert)) {
            return true;
        }
        if (algorithm == null) {
            return false;
        }
        String key = Target_sun_security_x509_X509CertImpl.getFingerprint(algorithm, cert, debug);
        return (key == null || props.containsKey(key));
    }
}
