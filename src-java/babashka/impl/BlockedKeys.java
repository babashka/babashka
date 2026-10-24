package babashka.impl;

import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;

/** Rejects certificates with the public key of a compromised eMudhra intermediate. */
public final class BlockedKeys {

    /** SHA-256 of each SubjectPublicKeyInfo in emudhra.we-have-a-problem.com/impacted-intermediates.json */
    private static final Set<String> BLOCKED = Set.of(
        "EBED6475C2C2D80FCB199F3FE8DD6E6B3CE60B77EB15D4E42C3DEC0C6F5B5105",
        "43BFFA19D6EF651451F6841E019931E96F6F2EAEFF28AB4B6F1CE4576DE2717B",
        "E0254C7FCDC265E2F0F8A428DABB75AB4AE039B4872D62F5EE57F0877D5A8A41",
        "49DD353DC2B919A3770645117D0E60C16115E7D1BC259EEFE734B00EA434FE5F",
        "63ECEFEE431D49523BC1136C4E72D0206A477086CAF606CD5CF025686C109CF8",
        "5C0660A2B164910D8FF589C18E14552E9E0EC237DCEFE2CB736D41A4811F81A0",
        "EC4FFA1AD0AC88081314B7AAFFEF6A91F97CD57291BB5BA686C5415F5EB4F6B6",
        "92C063D67E8F14DB71D288E11266FFDAF69CBF115F7BAB14F3BC2F68D25E0E30");

    private BlockedKeys() {}

    public static boolean isUntrusted(X509Certificate cert, Properties blockedCerts) {
        try {
            HexFormat hex = HexFormat.of().withUpperCase();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return blockedCerts.containsKey(hex.formatHex(md.digest(cert.getEncoded())))
                || BLOCKED.contains(hex.formatHex(md.digest(cert.getPublicKey().getEncoded())));
        } catch (Exception e) {
            return true;
        }
    }
}
