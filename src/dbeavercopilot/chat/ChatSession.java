package dbeavercopilot.chat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class ChatSession {

    private static final DateTimeFormatter ID_FMT  = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter LBL_FMT = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    public final String id;
    public String title;
    public String connection;
    public long timestamp;
    public final List<ChatMessage> messages = new ArrayList<>();

    public ChatSession(String id, String title, String connection, long timestamp) {
        this.id = id;
        this.title = title;
        this.connection = connection;
        this.timestamp = timestamp;
    }

    public static ChatSession create(String title, String connection) {
        String id = LocalDateTime.now().format(ID_FMT) + "_" + (System.currentTimeMillis() % 1000);
        return new ChatSession(id, title, connection, System.currentTimeMillis());
    }

    public String displayLabel() {
        String date = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
                .format(LBL_FMT);
        return title + "  [" + connection + " • " + date + "]";
    }
}
