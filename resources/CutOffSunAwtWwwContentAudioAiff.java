import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.annotate.Delete;

public final class CutOffSunAwtWwwContentAudioAiff {
}

// @Platforms(Platform.DARWIN.class)
// @TargetClass(className = "sun.net.spi.DefaultProxySelector")
// @Delete
// final class Target_sun_net_spi_DefaultProxySelector {
// }

// @Platforms(Platform.DARWIN.class)

// This cuts of access to the javax.sound package via java.net.HttpUrlConnection:
// sun.awt.www.content.audio.aiff.getContent ->
// java.net.ContentHandler.getContent ->
// java.net.URLConnection.getContent()

@Platforms(Platform.DARWIN.class)
@TargetClass(className = "sun.awt.www.content.audio.aiff")
@Delete
final class Target_sun_awt_www_content_audio_aiff {
}
