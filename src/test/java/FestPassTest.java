import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class FestPassTest {
    @FunctionalInterface
    private interface Check {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface FileCheck {
        void run(Path file) throws Exception;
    }

    private record TestCase(String name, Check check) {}
    private record Result(int code, String output, String error) {}

    public static void main(String[] args) throws IOException {
        List<TestCase> cases = List.of(
                new TestCase("emptyEventAcceptsBooking", () -> expect(true, 0, 100)),
                new TestCase("lastAvailableSeatIsAccepted", () -> expect(true, 99, 100)),
                new TestCase("fullEventRejectsBooking", () -> expect(false, 100, 100)),
                new TestCase("overCapacityRejectsBooking", () -> expect(false, 101, 100)),
                new TestCase("zeroCapacityRejectsBooking", () -> expect(false, 0, 0)),
                new TestCase("oneSeatEventAcceptsBooking", () -> expect(true, 0, 1)),
                new TestCase("negativeBookedIsRejected", () -> expectInvalid(-1, 100)),
                new TestCase("negativeCapacityIsRejected", () -> expectInvalid(0, -1)),
                new TestCase("eventsTrackIndependentCapacity", FestPassTest::independentEvents),
                new TestCase("duplicateEventDoesNotReplaceBookings", FestPassTest::duplicateEvent),
                new TestCase("invalidEventsLeaveStateUnchanged", FestPassTest::invalidEvents),
                new TestCase("duplicateBookingDoesNotConsumeSeat", FestPassTest::duplicateBooking),
                new TestCase("soldOutBookingLeavesRosterUnchanged", FestPassTest::soldOut),
                new TestCase("cancellationFreesSeat", FestPassTest::cancellation),
                new TestCase("missingBookingCannotBeCancelled", FestPassTest::missingCancellation),
                new TestCase("unknownEventsAreRejected", FestPassTest::unknownEvents),
                new TestCase("invalidStudentIdsAreRejected", FestPassTest::invalidStudents),
                new TestCase("snapshotsCannotMutateService", FestPassTest::immutableSnapshots),
                new TestCase("bookingsSurviveSaveAndReload", FestPassTest::roundTrip),
                new TestCase("cancellationsSurviveReload", FestPassTest::persistCancellation),
                new TestCase("corruptDataIsRejected", FestPassTest::corruptData),
                new TestCase("missingDataStartsEmpty", () -> withFile(file ->
                        check(BookingStore.load(file).events().isEmpty(), "Expected an empty store"))),
                new TestCase("cliWorkflowPersistsAcrossCommands", FestPassTest::cliWorkflow),
                new TestCase("cliReportsInvalidRequests", FestPassTest::invalidCommands),
                new TestCase("cliDoesNotOverwriteCorruptFile", FestPassTest::preserveCorruptFile),
                new TestCase("cliReportsReadAndSaveFailures", FestPassTest::storageFailures),
                new TestCase("cliRejectsLockedStore", FestPassTest::lockedStore),
                new TestCase("helpDoesNotNeedProjectData", () -> {
                    Result result = invoke("help");
                    check(result.code() == 0 && result.output().contains("add-event"), result.toString());
                    withFile(file -> {
                        Result withData = cli(file, "help");
                        check(withData.code() == 0 && withData.output().contains("add-event"),
                                withData.toString());
                        check(Files.notExists(file), "Help created a data store");
                    });
                }));
        List<String> results = new ArrayList<>();
        int failures = 0;
        for (TestCase test : cases) {
            try {
                test.check().run();
                System.out.println("PASS " + test.name());
                results.add("  <testcase classname=\"FestPassTest\" name=\"" + test.name() + "\"/>");
            } catch (AssertionError | Exception error) {
                failures++;
                System.err.println("FAIL " + test.name() + ": " + error.getMessage());
                error.printStackTrace(System.err);
                results.add("  <testcase classname=\"FestPassTest\" name=\"" + test.name()
                        + "\"><failure message=\"" + xml(error.toString()) + "\"/></testcase>");
            }
        }
        Path output = Path.of("build", "test-results", "TEST-FestPass.xml");
        Files.createDirectories(output.getParent());
        String report = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<testsuite name=\"FestPassTest\" tests=\"" + cases.size()
                + "\" failures=\"" + failures + "\" errors=\"0\" skipped=\"0\">\n"
                + String.join("\n", results) + "\n</testsuite>\n";
        Files.writeString(output, report, StandardCharsets.UTF_8);
        System.out.printf("%d tests, %d failures. Report: %s%n", cases.size(), failures, output);
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static BookingService sample() {
        BookingService service = new BookingService();
        service.addEvent("java", "Java Workshop", 2);
        service.addEvent("music", "Campus Music", 1);
        return service;
    }

    private static void independentEvents() {
        BookingService service = sample();
        service.book("java", "S1");
        service.book("music", "S1");
        check(service.event("java").available() == 1, "Java capacity must be independent");
        check(service.event("music").available() == 0, "Same student can attend another event");
    }

    private static void duplicateEvent() {
        BookingService service = sample();
        service.book("java", "S1");
        rejects(() -> service.addEvent("java", "Replacement", 10));
        check(service.event("java").students().equals(List.of("S1")), "Existing bookings lost");
        check(service.event("java").capacity() == 2, "Existing event replaced");
    }

    private static void invalidEvents() {
        BookingService service = new BookingService();
        rejects(() -> service.addEvent("bad id", "Title", 1));
        rejects(() -> service.addEvent("id", " \t", 1));
        rejects(() -> service.addEvent("id", "Title\nInjected", 1));
        rejects(() -> service.addEvent("id", "x".repeat(81), 1));
        rejects(() -> service.addEvent("id", "Title", 0));
        rejects(() -> service.addEvent("id", "Title", 10001));
        check(service.events().isEmpty(), "Invalid input added an event");
    }

    private static void duplicateBooking() {
        BookingService service = sample();
        service.book("java", "S1");
        rejects(() -> service.book("java", "S1"));
        check(service.event("java").available() == 1, "Duplicate consumed a seat");
    }

    private static void soldOut() {
        BookingService service = sample();
        service.book("music", "S1");
        rejects(() -> service.book("music", "S2"));
        check(service.event("music").students().equals(List.of("S1")), "Overbooking occurred");
    }

    private static void cancellation() {
        BookingService service = sample();
        service.book("music", "S1");
        service.cancel("music", "S1");
        service.book("music", "S2");
        check(service.event("music").students().equals(List.of("S2")), "Cancelled seat was not reused");
    }

    private static void missingCancellation() {
        BookingService service = sample();
        service.book("music", "S1");
        rejects(() -> service.cancel("music", "S2"));
        check(service.event("music").available() == 0, "Invalid cancellation freed a seat");
    }

    private static void unknownEvents() {
        BookingService service = sample();
        rejects(() -> service.book("missing", "S1"));
        rejects(() -> service.cancel("missing", "S1"));
        rejects(() -> service.event("missing"));
    }

    private static void invalidStudents() {
        BookingService service = sample();
        for (String id : new String[] {"", "S 1", "S\t1", "../S1", "x".repeat(33)}) {
            rejects(() -> service.book("java", id));
        }
        check(service.event("java").students().isEmpty(), "Invalid student was booked");
    }

    private static void immutableSnapshots() {
        BookingService service = sample();
        BookingService.Event before = service.event("java");
        service.book("java", "S1");
        check(before.students().isEmpty(), "Snapshot changed with service");
        try {
            service.event("java").students().add("INTRUDER");
            throw new AssertionError("Roster is mutable");
        } catch (UnsupportedOperationException expected) {
            check(service.event("java").students().equals(List.of("S1")), "Service roster changed");
        }
    }

    private static void roundTrip() throws Exception {
        withFile(file -> {
            BookingService service = sample();
            service.addEvent("cafe", "Caf\u00e9 & Code", 4);
            service.book("java", "S1");
            service.book("java", "S2");
            service.book("cafe", "S1");
            BookingStore.save(file, service);
            check(BookingStore.load(file).events().equals(service.events()), "Round-trip lost state");
            check(Files.readString(file).startsWith("FESTPASS\t1"), "Version header missing");
        });
    }

    private static void persistCancellation() throws Exception {
        withFile(file -> {
            BookingService service = sample();
            service.book("java", "S1");
            BookingStore.save(file, service);
            service = BookingStore.load(file);
            service.cancel("java", "S1");
            BookingStore.save(file, service);
            check(BookingStore.load(file).event("java").students().isEmpty(), "Cancellation was lost");
        });
    }

    private static void corruptData() throws Exception {
        withFile(file -> {
            for (String data : List.of("", "FESTPASS\t99\n", "FESTPASS\t1\nUNKNOWN\n",
                    "FESTPASS\t1\nEVENT\tx\tTitle\tnope\n",
                    "FESTPASS\t1\nBOOKING\tmissing\tS1\n",
                    "FESTPASS\t1\nEVENT\tx\tTitle\t1\nBOOKING\tx\tS1\nBOOKING\tx\tS1\n",
                    "FESTPASS\t1\nEVENT\tx\tTitle\t1\nBOOKING\tx\tS1\nBOOKING\tx\tS2\n")) {
                Files.writeString(file, data);
                try {
                    BookingStore.load(file);
                    throw new AssertionError("Corrupt file was accepted: " + data);
                } catch (IOException expected) {
                    check(!expected.getMessage().isBlank(), "Missing corruption details");
                }
            }
        });
    }

    private static void cliWorkflow() throws Exception {
        withFile(file -> {
            succeeds(file, "add-event", "java", "Java Workshop", "2");
            succeeds(file, "book", "java", "S1");
            succeeds(file, "book", "java", "S2");
            check(cli(file, "book", "java", "S3").code() == 2, "Sold-out request should fail");
            check(cli(file, "roster", "java").output().contains("2 student(s)"), "Roster incorrect");
            succeeds(file, "cancel", "java", "S1");
            check(cli(file, "list").output().contains("java | Java Workshop | 1/2 | 1"),
                    "List does not reflect persisted cancellation");
            check(cli(file, "summary").output().contains("Events: 1 | Booked: 1 | Available: 1"),
                    "Summary incorrect");
        });
    }

    private static void invalidCommands() throws Exception {
        withFile(file -> {
            for (String[] args : List.of(new String[] {"unknown"},
                    new String[] {"book", "java"}, new String[] {"list", "extra"},
                    new String[] {"add-event", "java", "Java", "NaN"})) {
                Result result = cli(file, args);
                check(result.code() == 2 && !result.error().isBlank(), result.toString());
            }
            check(invoke("--data").code() == 2, "Missing --data value should fail");
            check(Files.notExists(file), "Invalid command created data");
        });
    }

    private static void preserveCorruptFile() throws Exception {
        withFile(file -> {
            String corrupt = "FESTPASS\t1\nBROKEN\n";
            Files.writeString(file, corrupt);
            Result result = cli(file, "add-event", "java", "Java", "2");
            check(result.code() == 3 && result.error().contains("line 2"), result.toString());
            check(Files.readString(file).equals(corrupt), "Corrupt store was overwritten");
            check(!result.output().contains("CREATED"), "False success reported");
        });
    }

    private static void storageFailures() throws Exception {
        withFile(file -> {
            Files.createDirectory(file);
            Result result = cli(file, "list");
            check(result.code() == 3 && !result.error().isBlank(), result.toString());
            try {
                Files.writeString(file.resolve("keep.txt"), "keep");
                BookingStore.save(file, sample());
                throw new AssertionError("Save over non-empty directory should fail");
            } catch (IOException expected) {
                check(Files.readString(file.resolve("keep.txt")).equals("keep"), "Data was damaged");
            }
        });
    }

    private static void lockedStore() throws Exception {
        withFile(file -> {
            Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
            try (var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE); var lock = channel.lock()) {
                check(lock.isValid(), "Could not hold fixture lock");
                Result result = cli(file, "add-event", "java", "Java", "2");
                check(result.code() == 3, "Concurrent access should fail explicitly");
                check(Files.notExists(file), "Locked data was modified");
            }
        });
    }

    private static Result cli(Path file, String... command) {
        List<String> args = new ArrayList<>(List.of("--data", file.toString()));
        args.addAll(List.of(command));
        return invoke(args.toArray(String[]::new));
    }

    private static Result invoke(String... args) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int code;
        try (var out = new PrintStream(output, true, StandardCharsets.UTF_8);
                var err = new PrintStream(error, true, StandardCharsets.UTF_8)) {
            code = FestPass.run(args, out, err);
        }
        return new Result(code, output.toString(StandardCharsets.UTF_8),
                error.toString(StandardCharsets.UTF_8));
    }

    private static void succeeds(Path file, String... command) {
        Result result = cli(file, command);
        check(result.code() == 0 && result.error().isEmpty(), result.toString());
    }

    private static void withFile(FileCheck test) throws Exception {
        Path directory = Files.createTempDirectory("festpass-test-");
        try {
            test.run(directory.resolve("registrations.tsv"));
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expect(boolean expected, int booked, int capacity) {
        boolean actual = FestPass.canBook(booked, capacity);
        if (actual != expected) {
            throw new AssertionError("canBook(" + booked + ", " + capacity + "): expected "
                    + expected + ", got " + actual);
        }
    }

    private static void expectInvalid(int booked, int capacity) {
        try {
            FestPass.canBook(booked, capacity);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Negative input must throw IllegalArgumentException.");
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
