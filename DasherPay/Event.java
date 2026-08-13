import java.time.LocalDateTime;
import java.util.Objects;

public final class Event {
    private final String orderId;
    private final String dasherId;
    private final LocalDateTime timestamp;
    private final Status status;

    public Event(String orderId, String dasherId, LocalDateTime timestamp, Status status) {
        this.orderId   = Objects.requireNonNull(orderId, "orderId");
        this.dasherId  = Objects.requireNonNull(dasherId, "dasherId");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.status    = Objects.requireNonNull(status, "status");
    }

    public String getOrderId()          { return orderId; }
    public String getDasherId()         { return dasherId; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public Status getStatus()           { return status; }

    @Override public String toString() {
        return status + "(" + orderId + " @" + timestamp + ")";
    }
}