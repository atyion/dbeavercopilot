package dbeavercopilot.chat;

public class ChatMessage {
    public final String role;
    public final String text;

    public ChatMessage(String role, String text) {
        this.role = role;
        this.text = text;
    }
}
