import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SmokeTest {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of("build", "festpass.jar").toAbsolutePath();
        Path report = Path.of("build", "smoke-test.txt");
        Files.deleteIfExists(report);
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("Build the JAR first, from the project directory.");
        }
        Path directory = Files.createTempDirectory("festpass-smoke-");
        try {
            Path data = directory.resolve("registrations.tsv");
            run(jar, data, 0, "EVENT CREATED", "add-event", "java", "Java Workshop", "1");
            run(jar, data, 0, "BOOKING ACCEPTED", "book", "java", "S1");
            run(jar, data, 2, "SOLD OUT", "book", "java", "S2");
            run(jar, data, 0, "1 student(s)", "roster", "java");
            run(jar, data, 0, "BOOKING CANCELLED", "cancel", "java", "S1");
            run(jar, data, 0, "BOOKING ACCEPTED", "book", "java", "S2");
            run(jar, data, 0, "Booked: 1 | Available: 0", "summary");
            String result = "PASS: seven real-JAR commands; persistence, sold-out rejection, "
                    + "cancellation, and seat reuse verified.\n";
            Files.writeString(report, result, StandardCharsets.UTF_8);
            System.out.print(result);
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void run(Path jar, Path data, int expectedCode, String expectedText,
            String... args) throws IOException, InterruptedException {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
        List<String> command = new ArrayList<>(List.of(java, "-jar", jar.toString(),
                "--data", data.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        if (code != expectedCode || !output.contains(expectedText)) {
            throw new IllegalStateException("Smoke command " + args[0] + ": expected exit "
                    + expectedCode + " and '" + expectedText + "', got exit " + code + ": " + output);
        }
        System.out.println("PASS JAR: " + args[0] + " (exit " + code + ")");
    }
}
