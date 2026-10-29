import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

public final class FestPass {
    private FestPass() {}

    public static boolean canBook(int booked, int capacity) {
        if (booked < 0 || capacity < 0) {
            throw new IllegalArgumentException("Booked seats and capacity cannot be negative.");
        }
        return booked < capacity;
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || (args.length == 1 && args[0].equals("help"))) {
            usage(out);
            return 0;
        }
        try {
            Path file = Path.of("data", "festpass.tsv");
            String[] command = args;
            if (args[0].equals("--data")) {
                if (args.length < 3) {
                    throw new IllegalArgumentException("--data requires a file and a command.");
                }
                file = Path.of(args[1]);
                command = Arrays.copyOfRange(args, 2, args.length);
            }
            if (command.length == 1 && command[0].equals("help")) {
                usage(out);
                return 0;
            }
            validateCommand(command);
            file = file.toAbsolutePath().normalize();
            Files.createDirectories(file.getParent());
            Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE); var lock = channel.tryLock()) {
                if (lock == null) {
                    throw new IOException("Data is busy. Retry when the other command finishes.");
                }
                BookingService service = BookingStore.load(file);
                String message;
                switch (command[0]) {
                    case "add-event" -> {
                        service.addEvent(command[1], command[2], Integer.parseInt(command[3]));
                        message = "EVENT CREATED: " + command[1];
                    }
                    case "book" -> {
                        service.book(command[1], command[2]);
                        message = "BOOKING ACCEPTED: " + command[2] + " -> " + command[1];
                    }
                    case "cancel" -> {
                        service.cancel(command[1], command[2]);
                        message = "BOOKING CANCELLED: " + command[2] + " -> " + command[1];
                    }
                    case "list" -> {
                        printEvents(service, out);
                        return 0;
                    }
                    case "roster" -> {
                        var event = service.event(command[1]);
                        out.println("ROSTER: " + event.id());
                        event.students().forEach(out::println);
                        out.println(event.students().size() + " student(s)");
                        return 0;
                    }
                    case "summary" -> {
                        int booked = service.events().stream().mapToInt(e -> e.students().size()).sum();
                        int capacity = service.events().stream().mapToInt(BookingService.Event::capacity).sum();
                        out.printf("Events: %d | Booked: %d | Available: %d%n",
                                service.events().size(), booked, capacity - booked);
                        return 0;
                    }
                    default -> throw new IllegalArgumentException("Unknown command: " + command[0]);
                }
                BookingStore.save(file, service);
                out.println(message);
                return 0;
            }
        } catch (InvalidPathException error) {
            err.println("Invalid file path: " + error.getMessage());
            return 2;
        } catch (IllegalArgumentException error) {
            err.println("Invalid input: " + error.getMessage());
            return 2;
        } catch (OverlappingFileLockException error) {
            err.println("Storage error: data is busy. Retry when the other command finishes.");
            return 3;
        } catch (IOException error) {
            err.println("Storage error: " + error.getMessage());
            return 3;
        }
    }

    private static void validateCommand(String[] command) {
        int expected = switch (command[0]) {
            case "add-event" -> 4;
            case "book", "cancel" -> 3;
            case "roster" -> 2;
            case "list", "summary" -> 1;
            default -> throw new IllegalArgumentException("Unknown command. Run with 'help' for usage.");
        };
        if (command.length != expected) {
            throw new IllegalArgumentException("Wrong number of arguments for " + command[0] + ".");
        }
    }

    private static void printEvents(BookingService service, PrintStream out) {
        out.println("ID | TITLE | BOOKED/CAPACITY | AVAILABLE");
        for (var event : service.events()) {
            out.printf("%s | %s | %d/%d | %d%n", event.id(), event.title(),
                    event.students().size(), event.capacity(), event.available());
        }
        if (service.events().isEmpty()) {
            out.println("No events yet. Create one with add-event.");
        }
    }

    private static void usage(PrintStream out) {
        out.println("""
                FestPass - campus event registration
                Usage: java -jar build/festpass.jar [--data FILE] COMMAND
                  add-event ID "TITLE" CAPACITY
                  list
                  book EVENT_ID STUDENT_ID
                  cancel EVENT_ID STUDENT_ID
                  roster EVENT_ID
                  summary
                  help
                Default data file: data/festpass.tsv
                IDs are case-sensitive. Use synthetic student IDs, not personal data.
                Exit codes: 0 success, 2 invalid request, 3 storage failure.
                """);
    }
}
