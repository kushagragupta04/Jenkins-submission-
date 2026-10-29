import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class BookingStore {
    private BookingStore() {}

    public static BookingService load(Path file) throws IOException {
        if (Files.notExists(file)) {
            return new BookingService();
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals("FESTPASS\t1")) {
            throw new IOException("Unrecognized data file header: " + file);
        }
        BookingService service = new BookingService();
        for (int i = 1; i < lines.size(); i++) {
            String[] fields = lines.get(i).split("\t", -1);
            try {
                if (fields.length == 4 && fields[0].equals("EVENT")) {
                    service.addEvent(fields[1], fields[2], Integer.parseInt(fields[3]));
                } else if (fields.length == 3 && fields[0].equals("BOOKING")) {
                    service.book(fields[1], fields[2]);
                } else {
                    throw new IllegalArgumentException("Expected an EVENT or BOOKING record.");
                }
            } catch (IllegalArgumentException error) {
                throw new IOException("Invalid data at line " + (i + 1) + ": " + error.getMessage(), error);
            }
        }
        return service;
    }

    // The caller holds the lock across load, mutation, and save to avoid lost updates.
    public static void save(Path file, BookingService service) throws IOException {
        Path target = file.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        List<String> lines = new ArrayList<>();
        lines.add("FESTPASS\t1");
        for (BookingService.Event event : service.events()) {
            lines.add("EVENT\t" + event.id() + "\t" + event.title() + "\t" + event.capacity());
            for (String student : event.students()) {
                lines.add("BOOKING\t" + event.id() + "\t" + student);
            }
        }
        Path temporary = Files.createTempFile(target.getParent(), "festpass-", ".tmp");
        try {
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
