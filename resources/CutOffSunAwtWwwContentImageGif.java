import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.annotate.Delete;

public final class CutOffSunAwtWwwContentImageGif {
}

@Platforms(Platform.DARWIN.class)
@TargetClass(className = "sun.awt.www.content.image.gif")
@Delete
final class Target_sun_awt_www_content_image_gif {
}
