package babashka.impl;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Set;

/** Writes the JDK trust store without the eMudhra compromise roots (RHSB-2026-012) to the path in args[0]. */
public class TrustStore {

    /** SHA-256 of "AAA Certificate Services" and "emSign ECC Root CA - G3" */
    private static final Set<String> DISTRUSTED = Set.of(
        "d7a7a0fb5d7e2731d771e9484ebcdef71d5f0c3e0a2948782bc83ee0ea699ef4",
        "86a1ecba089c4a8d3bbe2734c612ba341d813e043cf9e8a862cd5c57a36bbe6b");

    private static final char[] PASSWORD = "changeit".toCharArray();

    public static void main(String[] args) throws IOException, GeneralSecurityException {
        Path source = Path.of(System.getProperty("java.home"), "lib", "security", "cacerts");
        KeyStore store = KeyStore.getInstance(source.toFile(), PASSWORD);
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        for (String alias : Collections.list(store.aliases())) {
            Certificate cert = store.getCertificate(alias);
            if (cert != null
                && DISTRUSTED.contains(HexFormat.of().formatHex(sha256.digest(cert.getEncoded())))) {
                store.deleteEntry(alias);
                System.out.println("Left " + alias + " out of the trust store");
            }
        }
        Path target = Path.of(args[0]);
        Files.createDirectories(target.toAbsolutePath().getParent());
        try (OutputStream out = Files.newOutputStream(target)) {
            store.store(out, PASSWORD);
        }
    }
}
