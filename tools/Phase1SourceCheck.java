import com.sun.source.util.JavacTask;

import org.w3c.dom.Document;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;

/** Lightweight validation that deliberately needs neither Gradle nor an Android SDK. */
public final class Phase1SourceCheck {
    private Phase1SourceCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path root = args.length == 0
                ? Path.of(".").toAbsolutePath().normalize()
                : Path.of(args[0]).toAbsolutePath().normalize();

        requireFile(root.resolve("settings.gradle.kts"));
        requireFile(root.resolve("build.gradle.kts"));
        requireFile(root.resolve("app/build.gradle.kts"));
        requireFile(root.resolve("app/src/main/AndroidManifest.xml"));
        requireFile(root.resolve("docs/PHASE_1.md"));

        List<Path> javaSources = filesWithSuffix(root.resolve("app/src"), ".java");
        parseJava(javaSources);

        List<Path> xmlFiles = filesWithSuffix(root.resolve("app/src/main"), ".xml");
        parseXml(xmlFiles);

        String manifest = Files.readString(
                root.resolve("app/src/main/AndroidManifest.xml"),
                StandardCharsets.UTF_8
        );
        requireContains(manifest, "android.permission.BLUETOOTH_SCAN", "scan permission");
        requireContains(manifest, "android.permission.BLUETOOTH_CONNECT", "connect permission");
        requireContains(manifest, "neverForLocation", "non-location BLE declaration");

        String protocol = Files.readString(
                root.resolve("app/src/main/java/dev/joycon2/bridge/protocol/JoyCon2Protocol.java"),
                StandardCharsets.UTF_8
        );
        requireContains(protocol, "ab7de9be-89fe-49ad-828f-118f09df7fd0", "Nintendo service UUID");
        requireContains(protocol, "ab7de9be-89fe-49ad-828f-118f09df7fd2", "input UUID");
        requireContains(protocol, "649d4ac9-8eb7-4e6c-af44-1ea54fe5f005", "write UUID");

        System.out.printf(
                "Phase 1 source check: PASS (%d Java files, %d XML files)%n",
                javaSources.size(),
                xmlFiles.size()
        );
    }

    private static List<Path> filesWithSuffix(Path directory, String suffix) throws Exception {
        List<Path> result = new ArrayList<>();
        try (var paths = Files.walk(directory)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(result::add);
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("No " + suffix + " files found below " + directory);
        }
        return result;
    }

    private static void parseJava(List<Path> sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("The jdk.compiler module is unavailable");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                diagnostics,
                null,
                StandardCharsets.UTF_8
        )) {
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjectsFromPaths(sources);
            JavacTask task = (JavacTask) compiler.getTask(
                    null,
                    files,
                    diagnostics,
                    List.of("-proc:none", "-source", "17"),
                    null,
                    units
            );
            task.parse();
        }

        List<String> errors = diagnostics.getDiagnostics().stream()
                .filter(item -> item.getKind() == Diagnostic.Kind.ERROR)
                .map(Phase1SourceCheck::formatDiagnostic)
                .toList();
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Java parse failed:\n" + String.join("\n", errors));
        }
    }

    private static void parseXml(List<Path> files) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        for (Path file : files) {
            Document document = factory.newDocumentBuilder().parse(file.toFile());
            if (document.getDocumentElement() == null) {
                throw new IllegalStateException("XML has no document element: " + file);
            }
        }
    }

    private static String formatDiagnostic(Diagnostic<? extends JavaFileObject> item) {
        String source = item.getSource() == null ? "unknown" : item.getSource().getName();
        return source + ":" + item.getLineNumber() + ": " + item.getMessage(null);
    }

    private static void requireFile(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Required file missing: " + path);
        }
    }

    private static void requireContains(String value, String needle, String label) {
        if (!value.contains(needle)) {
            throw new IllegalStateException("Missing " + label + ": " + needle);
        }
    }
}
