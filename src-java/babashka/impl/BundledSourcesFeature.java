package babashka.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeResourceAccess;

/** Embeds each file under src/babashka/ gzipped, as NAME.gz, where
 * babashka.main/bundled-source inflates it. The plain files are not
 * included in the image. */
public class BundledSourcesFeature implements Feature {

    private static final String PREFIX = "src/babashka/";

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        Module module = access.getApplicationClassLoader().getUnnamedModule();
        Set<String> seen = new HashSet<>();
        for (Path entry : access.getApplicationClassPath()) {
            try {
                if (Files.isDirectory(entry)) {
                    addDirectory(module, entry, seen);
                } else if (entry.toString().endsWith(".jar")) {
                    addJar(module, entry, seen);
                }
            } catch (IOException e) {
                throw new RuntimeException("Could not embed bundled sources from " + entry, e);
            }
        }
    }

    private static void addJar(Module module, Path jar, Set<String> seen) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!e.isDirectory() && e.getName().startsWith(PREFIX)) {
                    try (InputStream in = zip.getInputStream(e)) {
                        add(module, e.getName(), in.readAllBytes(), seen);
                    }
                }
            }
        }
    }

    private static void addDirectory(Module module, Path root, Set<String> seen) throws IOException {
        Path dir = root.resolve(PREFIX);
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        for (Path f : files) {
            String name = root.relativize(f).toString().replace('\\', '/');
            add(module, name, Files.readAllBytes(f), seen);
        }
    }

    // the first classpath entry with a name wins, as for a plain resource
    private static void add(Module module, String name, byte[] bytes, Set<String> seen) throws IOException {
        if (seen.add(name)) {
            RuntimeResourceAccess.addResource(module, name + ".gz", gzip(bytes));
        }
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length / 3 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(bytes);
        }
        return out.toByteArray();
    }
}
