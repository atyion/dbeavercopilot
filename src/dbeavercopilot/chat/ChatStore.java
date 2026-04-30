package dbeavercopilot.chat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.runtime.Platform;

public class ChatStore {

    private final File chatsDir;

    public ChatStore() {
        File stateDir = Platform.getStateLocation(Platform.getBundle("dbeavercopilot")).toFile();
        chatsDir = new File(stateDir, "chats");
        chatsDir.mkdirs();
    }

    public void save(ChatSession session) {
        File file = new File(chatsDir, session.id + ".chat");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file))) {
            w.write("{\"id\":\"" + esc(session.id) + "\","
                  + "\"title\":\"" + esc(session.title) + "\","
                  + "\"connection\":\"" + esc(session.connection) + "\","
                  + "\"timestamp\":" + session.timestamp + "}");
            w.newLine();
            for (ChatMessage m : session.messages) {
                w.write("{\"role\":\"" + esc(m.role) + "\",\"text\":\"" + esc(m.text) + "\"}");
                w.newLine();
            }
        } catch (IOException e) {
            Platform.getLog(Platform.getBundle("dbeavercopilot")).error("Failed to save chat", e);
        }
    }

    /** Loads only the metadata line of each file (fast, no messages). */
    public List<ChatSession> loadHeaders() {
        File[] files = chatsDir.listFiles((d, n) -> n.endsWith(".chat"));
        if (files == null) return Collections.emptyList();
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        List<ChatSession> headers = new ArrayList<>();
        for (File f : files) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                ChatSession s = parseHeader(r.readLine());
                if (s != null) headers.add(s);
            } catch (IOException ignored) {}
        }
        return headers;
    }

    /** Loads a full session including all messages. */
    public ChatSession load(String id) {
        File file = new File(chatsDir, id + ".chat");
        if (!file.exists()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            ChatSession session = parseHeader(r.readLine());
            if (session == null) return null;
            String line;
            while ((line = r.readLine()) != null) {
                String role = extractString(line, "role");
                String text = extractString(line, "text");
                if (!role.isEmpty()) session.messages.add(new ChatMessage(role, text));
            }
            return session;
        } catch (IOException e) {
            return null;
        }
    }

    public void delete(String id) {
        new File(chatsDir, id + ".chat").delete();
    }

    // --- internals ---

    private static ChatSession parseHeader(String line) {
        if (line == null) return null;
        String id = extractString(line, "id");
        if (id.isEmpty()) return null;
        return new ChatSession(id,
                extractString(line, "title"),
                extractString(line, "connection"),
                extractLong(line, "timestamp"));
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String extractString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return "";
        start += search.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                sb.append(switch (next) {
                    case '"'  -> '"';
                    case 'n'  -> '\n';
                    case 'r'  -> '\r';
                    case 't'  -> '\t';
                    default   -> next;
                });
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static long extractLong(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return 0;
        start += search.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        try { return Long.parseLong(json.substring(start, end)); } catch (NumberFormatException e) { return 0; }
    }
}
