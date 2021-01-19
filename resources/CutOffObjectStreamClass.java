import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.annotate.Delete;

public final class CutOffObjectStreamClass {
}

@TargetClass(className = "java.io.ObjectStreamClass")
@Delete
final class Target_java_io_ObjectStreamClass {
}

@TargetClass(className = "java.io.ObjectInputFilter")
@Delete
final class Target_java_io_ObjectInputFilter {
}
