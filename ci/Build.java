import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;

// A tiny source-file launcher keeps this lab independent of Maven, Gradle, and Python.
public final class Build {
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path BUILD = ROOT.resolve("build");
    private static final Path MAIN = BUILD.resolve("classes/main");
    private static final Path TEST = BUILD.resolve("classes/test");

    public static void main(String[] args) throws Exception {
        if (!Files.isRegularFile(ROOT.resolve("src/main/java/FestPass.java"))) {
            throw new IllegalStateException("Run this command from the festpass-java project directory.");
        }
        String task = args.length == 0 ? "verify" : args[0];
        if (!List.of("test", "package", "verify").contains(task) || args.length > 1) {
            throw new IllegalArgumentException("Usage: java ci/Build.java [test|package|verify]");
        }
        if (Runtime.version().feature() < 21 || ToolProvider.getSystemJavaCompiler() == null) {
            throw new IllegalStateException("Install a full JDK 21 or newer, not just a JRE.");
        }
        Files.createDirectories(BUILD);
        // A failed build must not leave an old JAR looking like this build's output.
        Files.deleteIfExists(BUILD.resolve("festpass.jar"));
        Files.deleteIfExists(BUILD.resolve("build-info.txt"));
        Files.deleteIfExists(BUILD.resolve("smoke-test.txt"));
        if (!task.equals("package")) {
            Files.deleteIfExists(BUILD.resolve("test-results/TEST-FestPass.xml"));
        }
        compileMain();
        if (!task.equals("package")) {
            runTests();
        }
        if (!task.equals("test")) {
            packageJar();
        }
    }

    private static void compileMain() throws IOException {
        resetClasses(MAIN);
        compileSources(ROOT.resolve("src/main/java"),
                List.of("--release", "21", "-Xlint:all", "-Werror", "-d", MAIN.toString()));
        System.out.println("Compiled FestPass with Java " + Runtime.version().feature() + ".");
    }

    private static void runTests() throws Exception {
        resetClasses(TEST);
        Files.createDirectories(BUILD.resolve("test-results"));
        Files.deleteIfExists(BUILD.resolve("test-results/TEST-FestPass.xml"));
        compileSources(ROOT.resolve("src/test/java"),
                List.of("--release", "21", "-Xlint:all", "-Werror", "-cp",
                        MAIN.toString(), "-d", TEST.toString()));
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        int result = new ProcessBuilder(java, "-cp", MAIN + File.pathSeparator + TEST,
                "FestPassTest").directory(ROOT.toFile()).inheritIO().start().waitFor();
        if (result != 0) {
            throw new IllegalStateException("Tests failed (exit " + result + "). JAR creation is blocked.");
        }
    }

    private static void packageJar() throws IOException {
        java.util.spi.ToolProvider jar = java.util.spi.ToolProvider.findFirst("jar")
                .orElseThrow(() -> new IllegalStateException("The JDK jar tool is missing."));
        int result = jar.run(System.out, System.err, "--create", "--file",
                BUILD.resolve("festpass.jar").toString(), "--main-class", "FestPass",
                "-C", MAIN.toString(), ".");
        if (result != 0) {
            throw new IllegalStateException("JAR creation failed (exit " + result + ").");
        }
        Files.writeString(BUILD.resolve("build-info.txt"),
                "Application: FestPass\nBuild Java: " + Runtime.version() + "\n",
                StandardCharsets.UTF_8);
        System.out.println("BUILD SUCCESS: build/festpass.jar");
    }

    private static void compile(List<String> options) {
        int result = ToolProvider.getSystemJavaCompiler().run(null, System.out, System.err,
                options.toArray(String[]::new));
        if (result != 0) {
            throw new IllegalStateException("Compilation failed (exit " + result + ").");
        }

    }

    private static void compileSources(Path source, List<String> options) throws IOException {
        List<String> arguments = new ArrayList<>(options);
        try (var files = Files.walk(source)) {
            arguments.addAll(files.filter(path -> path.toString().endsWith(".java"))
                    .sorted().map(Path::toString).toList());
        }
        compile(arguments);
    }

    private static void resetClasses(Path directory) throws IOException {
        if (!directory.equals(MAIN) && !directory.equals(TEST)) {
            throw new IllegalArgumentException("Refusing to clean an unexpected directory.");
        }
        if (Files.isSymbolicLink(BUILD) || Files.isSymbolicLink(BUILD.resolve("classes"))
                || Files.isSymbolicLink(directory)) {
            throw new IOException("Build output must not be a symbolic link.");
        }
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(directory);
    }
}
