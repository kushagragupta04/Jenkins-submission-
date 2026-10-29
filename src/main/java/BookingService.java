import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BookingService {
    public record Event(String id, String title, int capacity, List<String> students) {
        public Event {
            students = List.copyOf(students);
        }

        public int available() {
            return capacity - students.size();
        }
    }

    private static final class Registration {
        private final String id;
        private final String title;
        private final int capacity;
        private final Set<String> students = new LinkedHashSet<>();

        private Registration(String id, String title, int capacity) {
            this.id = id;
            this.title = title;
            this.capacity = capacity;
        }

        private Event snapshot() {
            return new Event(id, title, capacity, new ArrayList<>(students));
        }
    }

    private final Map<String, Registration> events = new LinkedHashMap<>();

    public void addEvent(String id, String title, int capacity) {
        validateId(id);
        if (title == null || title.isBlank() || title.length() > 80
                || title.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Title must be 1-80 characters without control characters.");
        }
        if (capacity < 1 || capacity > 10000) {
            throw new IllegalArgumentException("Capacity must be between 1 and 10000.");
        }
        if (events.containsKey(id)) {
            throw new IllegalArgumentException("Event already exists: " + id);
        }
        events.put(id, new Registration(id, title.strip(), capacity));
    }

    public void book(String eventId, String studentId) {
        validateId(studentId);
        Registration event = requireEvent(eventId);
        if (event.students.contains(studentId)) {
            throw new IllegalArgumentException("Student already booked this event: " + studentId);
        }
        if (!FestPass.canBook(event.students.size(), event.capacity)) {
            throw new IllegalArgumentException("SOLD OUT. Even the developer needs a ticket.");
        }
        event.students.add(studentId);
    }

    public void cancel(String eventId, String studentId) {
        validateId(studentId);
        Registration event = requireEvent(eventId);
        if (!event.students.remove(studentId)) {
            throw new IllegalArgumentException("No booking for student: " + studentId);
        }
    }

    public Event event(String id) {
        return requireEvent(id).snapshot();
    }

    public List<Event> events() {
        return events.values().stream().map(Registration::snapshot).toList();
    }

    private Registration requireEvent(String id) {
        validateId(id);
        Registration event = events.get(id);
        if (event == null) {
            throw new IllegalArgumentException("Unknown event: " + id);
        }
        return event;
    }

    private static void validateId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("IDs must be 1-32 letters, digits, underscores, or hyphens.");
        }
    }
}
