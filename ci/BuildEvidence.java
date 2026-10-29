import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

public final class BuildEvidence {
    private BuildEvidence() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("prepare", "SUCCESS", "FAILURE", "UNSTABLE",
                "ABORTED", "NOT_BUILT").contains(args[0])) {
            throw new IllegalArgumentException("Expected prepare or the Jenkins build result.");
        }
        Properties config = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of("submission.properties"),
                StandardCharsets.UTF_8)) {
            config.load(reader);
        }
        String classCode = setting(config, "class.code");
        String alias = gitAuthorAlias();
        String challenge = setting(config, "challenge.code");
        String installationId = installationId(classCode);
        if (args[0].equals("prepare")) {
            System.out.println("Submission settings ready. Evidence will use a class-scoped "
                    + "hash of the computer name and a local random installation ID.");
            return;
        }
        String host = command("hostname").strip().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            throw new IOException("Cannot identify the local computer label for evidence.");
        }
        String commit = command("git", "rev-parse", "HEAD").strip();
        if (!commit.matches("[0-9a-f]{40,64}")) {
            throw new IOException("Expected a Git commit hash.");
        }
        String[] counts = testCounts(Path.of("build", "test-results", "TEST-FestPass.xml"));
        Path jar = Path.of("build", "festpass.jar");
        if (args[0].equals("SUCCESS") && (!counts[0].equals("AVAILABLE")
                || !counts[2].equals("0") || !counts[3].equals("0") || !counts[4].equals("0")
                || !Files.isRegularFile(jar))) {
            throw new IOException("SUCCESS evidence requires passing tests and a built JAR.");
        }
        List<String> headers = List.of("Schema", "ClassCode", "StudentAlias", "AliasSource", "ChallengeCode",
                "InstallationId", "MachineLabelHash", "JobName", "BuildNumber", "Commit",
                "RecordedAtUtc", "Result", "TestReport", "Tests", "Failures", "Errors",
                "Skipped", "TestSourceSha256", "PipelineSha256", "JarSha256");
        List<String> values = List.of("1", classCode, alias, "git-commit-author", challenge, installationId,
                sha256((classCode + "\0" + host).getBytes(StandardCharsets.UTF_8)),
                environment("JOB_NAME"), environment("BUILD_NUMBER"), commit,
                Instant.now().toString(), args[0], counts[0], counts[1], counts[2], counts[3],
                counts[4], hashFile(Path.of("src", "test", "java", "FestPassTest.java")),
                hashFile(Path.of("Jenkinsfile")),
                args[0].equals("SUCCESS") ? hashFile(jar) : "NOT_PUBLISHED");
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", "submission-evidence.csv"),
                csv(headers) + "\n" + csv(values) + "\n", StandardCharsets.UTF_8);
        System.out.println("Recorded submission-evidence.csv for " + alias + ", build "
                + environment("BUILD_NUMBER") + ", result " + args[0] + ".");
    }

    static String setting(Properties config, String name) {
        String value = config.getProperty(name, "").strip();
        if (!value.matches("[A-Za-z0-9_-]{3,64}") || value.endsWith("_REQUIRED")) {
            throw new IllegalArgumentException("Set " + name
                    + " in submission.properties to the instructor-issued value (3-64 letters/digits/_/-).");
        }
        return value;
    }

    static String gitAuthorAlias() throws IOException, InterruptedException {
        String value = command("git", "-c", "i18n.logOutputEncoding=UTF-8",
                "show", "-s", "--format=%an", "HEAD").strip();
        if (value.isEmpty() || value.length() > 120
                || !Character.isLetterOrDigit(value.codePointAt(0))
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IOException("The checked-out commit must have a valid Git author name "
                    + "(1-120 characters, beginning with a letter or digit). "
                    + "Check git config user.name in your original repository and make a new commit.");
        }
        return value;
    }

    static String installationId(String classCode) throws IOException {
        Path folder = Path.of(System.getProperty("user.home"), ".festpass-assessment", classCode);
        Files.createDirectories(folder);
        Path file = folder.resolve("installation-id.txt");
        try {
            Files.writeString(file, UUID.randomUUID().toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException expected) {
            // Reuse the same per-user installation ID across workspaces and builds.
        }
        String value = Files.readString(file, StandardCharsets.UTF_8).strip();
        if (!value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new IOException("Invalid installation ID. Ask the instructor before replacing " + file);
        }
        return value;
    }

    static String[] testCounts(Path report) throws Exception {
        if (!Files.isRegularFile(report)) {
            return new String[] {"NOT_AVAILABLE", "", "", "", ""};
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var suite = factory.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
        if (!suite.getTagName().equals("testsuite")) {
            throw new IOException("Expected a testsuite report.");
        }
        String[] counts = {"AVAILABLE", "", "", "", ""};
        String[] names = {"tests", "failures", "errors", "skipped"};
        for (int i = 0; i < names.length; i++) {
            counts[i + 1] = suite.getAttribute(names[i]);
            if (!counts[i + 1].matches("\\d+")) {
                throw new IOException("Invalid test count: " + names[i]);
            }
        }
        return counts;
    }

    static String sha256(byte[] input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    }

    private static String hashFile(Path file) throws Exception {
        return sha256(Files.readAllBytes(file));
    }

    static String csv(List<String> values) {
        return values.stream().map(value -> "\"" + value.replace("\"", "\"\"") + "\"")
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Run evidence collection in Jenkins; missing " + name);
        }
        return value;
    }

    private static String command(String... args) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException("Evidence command failed: " + args[0]);
        }
        return output;
    }
}
