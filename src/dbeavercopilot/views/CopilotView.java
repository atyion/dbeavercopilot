package dbeavercopilot.views;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.ai.AIAssistantResponse;
import org.jkiss.dbeaver.model.ai.AIMessage;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;

import dbeavercopilot.ai.ChatService;
import dbeavercopilot.chat.ChatMessage;
import dbeavercopilot.chat.ChatSession;
import dbeavercopilot.chat.ChatStore;

public class CopilotView extends ViewPart {

    public static final String ID = "dbeavercopilot.views.CopilotView";

    private final ChatService chatService = new ChatService();
    private final ChatStore   chatStore   = new ChatStore();

    private final List<AIMessage>   chatHistory     = new ArrayList<>();
    private final List<ChatSession> sessionHeaders  = new ArrayList<>();

    private ChatSession currentSession = null;

    private Composite parent;
    private org.eclipse.swt.widgets.List chatList;
    private StyledText chatDisplay;
    private Text input;
    private Button send;
    private Button insertSql;
    private String lastSql = null;
    private Font chatFont;

    @Override
    public void createPartControl(Composite parent) {
        this.parent = parent;
        parent.setLayout(new GridLayout(1, false));

        SashForm sash = new SashForm(parent, SWT.HORIZONTAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // --- Left panel: chat history list ---
        Composite leftPanel = new Composite(sash, SWT.NONE);
        leftPanel.setLayout(new GridLayout(1, false));

        chatList = new org.eclipse.swt.widgets.List(leftPanel, SWT.BORDER | SWT.V_SCROLL | SWT.SINGLE);
        chatList.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        chatList.addListener(SWT.Selection, e -> {
            int idx = chatList.getSelectionIndex();
            if (idx >= 0 && idx < sessionHeaders.size()) {
                ChatSession full = chatStore.load(sessionHeaders.get(idx).id);
                if (full != null) loadSession(full);
            }
        });

        Menu contextMenu = new Menu(chatList);
        chatList.setMenu(contextMenu);
        contextMenu.addListener(SWT.Show, e -> {
            for (MenuItem item : contextMenu.getItems()) item.dispose();
            int idx = chatList.getSelectionIndex();
            if (idx < 0 || idx >= sessionHeaders.size()) return;

            MenuItem deleteItem = new MenuItem(contextMenu, SWT.PUSH);
            deleteItem.setText("Delete Session");
            deleteItem.addListener(SWT.Selection, ev -> {
                MessageBox confirm = new MessageBox(chatList.getShell(),
                    SWT.ICON_QUESTION | SWT.YES | SWT.NO);
                confirm.setText("Delete Session");
                confirm.setMessage("Delete this chat session?\nThis cannot be undone.");
                if (confirm.open() != SWT.YES) return;

                ChatSession toDelete = sessionHeaders.get(idx);
                chatStore.delete(toDelete.id);
                if (currentSession != null && currentSession.id.equals(toDelete.id)) {
                    startNewChat();
                }
                refreshChatList();
            });
        });

        // --- Right panel: chat UI ---
        Composite rightPanel = new Composite(sash, SWT.NONE);
        rightPanel.setLayout(new GridLayout(1, false));

        chatDisplay = new StyledText(rightPanel, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
        chatDisplay.setEditable(false);
        chatDisplay.setCaret(null);
        chatDisplay.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        chatDisplay.setLeftMargin(8);
        chatDisplay.setRightMargin(8);
        chatDisplay.setTopMargin(6);
        chatDisplay.setBottomMargin(6);
        chatDisplay.setLineSpacing(3);

        FontData[] fontData = chatDisplay.getFont().getFontData();
        for (FontData fd : fontData) fd.setHeight(fd.getHeight() + 1);
        chatFont = new Font(Display.getDefault(), fontData);
        chatDisplay.setFont(chatFont);

        new Label(rightPanel, SWT.SEPARATOR | SWT.HORIZONTAL)
            .setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        insertSql = new Button(rightPanel, SWT.PUSH);
        insertSql.setText("Insert SQL into editor");
        insertSql.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        insertSql.setVisible(false);
        insertSql.addListener(SWT.Selection, e -> insertSqlIntoEditor());

        Composite inputRow = new Composite(rightPanel, SWT.BORDER);
        inputRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        inputRow.setLayout(new GridLayout(3, false));

        input = new Text(inputRow, SWT.SINGLE);
        input.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        input.setMessage("Ask something about your database...");
        input.addListener(SWT.DefaultSelection, e -> sendMessage());

        send = new Button(inputRow, SWT.PUSH);
        send.setText("Send");
        send.addListener(SWT.Selection, e -> sendMessage());

        Button clear = new Button(inputRow, SWT.PUSH);
        clear.setText("+");
        clear.addListener(SWT.Selection, e -> startNewChat());

        sash.setWeights(new int[]{220, 580});

        refreshChatList();
    }

    // --- Session management ---

    private void startNewChat() {
        currentSession = null;
        chatHistory.clear();
        chatDisplay.setText("");
        insertSql.setVisible(false);
        lastSql = null;
        chatList.deselectAll();
    }

    private void loadSession(ChatSession session) {
        currentSession = session;
        chatHistory.clear();
        chatDisplay.setText("");
        insertSql.setVisible(false);
        lastSql = null;

        for (ChatMessage m : session.messages) {
            if ("user".equals(m.role)) {
                appendUserMessage(m.text);
                chatHistory.add(AIMessage.userMessage(m.text));
            } else {
                appendAssistantMessage(m.text);
                chatHistory.add(AIMessage.assistantMessage(m.text, null));
            }
        }
    }

    private void refreshChatList() {
        sessionHeaders.clear();
        sessionHeaders.addAll(chatStore.loadHeaders());

        chatList.removeAll();
        for (ChatSession s : sessionHeaders) {
            chatList.add(s.displayLabel());
        }

        // Re-select the current session if any
        if (currentSession != null) {
            for (int i = 0; i < sessionHeaders.size(); i++) {
                if (sessionHeaders.get(i).id.equals(currentSession.id)) {
                    chatList.select(i);
                    break;
                }
            }
        }
    }

    // --- Chat display helpers ---

    private void appendUserMessage(String text) {
        String header = "You\n";
        int firstLine = chatDisplay.getLineCount() - 1;
        int start = chatDisplay.getCharCount();
        chatDisplay.append(header + text + "\n\n");
        int addedLines = chatDisplay.getLineCount() - 1 - firstLine;
        if (addedLines > 0) {
            chatDisplay.setLineAlignment(firstLine, addedLines, SWT.RIGHT);
        }

        StyleRange style = new StyleRange(start, header.length() - 1, null, null);
        style.fontStyle = SWT.BOLD;
        style.foreground = Display.getDefault().getSystemColor(SWT.COLOR_LIST_SELECTION);
        chatDisplay.setStyleRange(style);
        scrollToBottom();
    }

    private void appendAssistantMessage(String text) {
        String header = "Assistant\n";
        int start = chatDisplay.getCharCount();
        chatDisplay.append(header + text + "\n\n");

        StyleRange style = new StyleRange(start, header.length() - 1, null, null);
        style.fontStyle = SWT.BOLD;
        style.foreground = Display.getDefault().getSystemColor(SWT.COLOR_LINK_FOREGROUND);
        chatDisplay.setStyleRange(style);
        scrollToBottom();
    }

    private void appendExploreStep(String sql, String result) {
        String truncatedResult = result.length() > 300 ? result.substring(0, 300) + "..." : result;
        String line = "  > " + sql.replaceAll("\\s+", " ") + "\n  " + truncatedResult + "\n";
        int start = chatDisplay.getCharCount();
        chatDisplay.append(line);

        StyleRange style = new StyleRange(start, line.length(), null, null);
        style.fontStyle = SWT.ITALIC;
        style.foreground = Display.getDefault().getSystemColor(SWT.COLOR_DARK_GRAY);
        chatDisplay.setStyleRange(style);
        scrollToBottom();
    }

    private void appendError(String text) {
        int start = chatDisplay.getCharCount();
        String line = text + "\n\n";
        chatDisplay.append(line);

        StyleRange style = new StyleRange(start, line.length(), null, null);
        style.foreground = Display.getDefault().getSystemColor(SWT.COLOR_DARK_RED);
        chatDisplay.setStyleRange(style);
        scrollToBottom();
    }

    private void scrollToBottom() {
        chatDisplay.setTopIndex(chatDisplay.getLineCount() - 1);
    }

    // --- Actions ---

    private void insertSqlIntoEditor() {
        if (lastSql == null) return;
        IEditorPart editor = PlatformUI.getWorkbench()
            .getActiveWorkbenchWindow().getActivePage().getActiveEditor();
        if (!(editor instanceof SQLEditor sqlEditor)) {
            appendError("[No SQL editor active]");
            return;
        }
        var document = sqlEditor.getDocument();
        if (document == null) return;
        try {
            int offset = document.getLength();
            String insertion = (offset > 0 ? "\n" : "") + lastSql;
            document.replace(offset, 0, insertion);
            sqlEditor.getSelectionProvider().setSelection(new TextSelection(offset + insertion.length(), 0));
            insertSql.setVisible(false);
            lastSql = null;
        } catch (Exception e) {
            appendError("[Error inserting SQL: " + e.getMessage() + "]");
        }
    }

    private void sendMessage() {
        String message = input.getText().trim();
        if (message.isEmpty()) return;

        IEditorPart editor = PlatformUI.getWorkbench()
            .getActiveWorkbenchWindow().getActivePage().getActiveEditor();
        if (!(editor instanceof SQLEditor sqlEditor)) {
            appendError("[No active SQL editor]");
            return;
        }
        DBCExecutionContext execCtx = sqlEditor.getExecutionContext();
        DBPDataSourceContainer container = sqlEditor.getDataSourceContainer();
        if (execCtx == null || container == null || !container.isConnected()) {
            appendError("[Connection is not active]");
            return;
        }
        String dbType = container.getDriver().getName();

        // Create session on first message
        if (currentSession == null) {
            String title = message.length() > 40 ? message.substring(0, 40) + "..." : message;
            currentSession = ChatSession.create(title, container.getName());
        }

        currentSession.messages.add(new ChatMessage("user", message));
        chatStore.save(currentSession);
        refreshChatList();

        appendUserMessage(message);
        input.setText("");
        input.setEnabled(false);
        send.setEnabled(false);
        insertSql.setVisible(false);
        lastSql = null;

        chatHistory.add(AIMessage.userMessage(message));
        List<AIMessage> messageSnapshot = new ArrayList<>(chatHistory);
        ChatSession sessionSnapshot = currentSession;

        Job job = new Job("AI Chat") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    AIAssistantResponse response = chatService.send(
                        monitor, messageSnapshot, execCtx, dbType, container.getName(),
                        (sql, result) -> Display.getDefault().asyncExec(() -> {
                            if (!chatDisplay.isDisposed()) appendExploreStep(sql, result);
                        }));

                    String text = response.getText();
                    String sql = chatService.extractSql(text);
                    chatHistory.add(AIMessage.assistantMessage(text, null));

                    Display.getDefault().asyncExec(() -> {
                        if (chatDisplay.isDisposed()) return;
                        appendAssistantMessage(text);

                        // Save assistant reply (only if user hasn't switched session)
                        if (currentSession != null && currentSession.id.equals(sessionSnapshot.id)) {
                            currentSession.messages.add(new ChatMessage("assistant", text));
                            chatStore.save(currentSession);
                            refreshChatList();
                        }

                        if (sql != null) {
                            lastSql = sql;
                            insertSql.setVisible(true);
                            parent.layout();
                        }
                        input.setEnabled(true);
                        send.setEnabled(true);
                        input.setFocus();
                    });
                } catch (Exception e) {
                    Display.getDefault().asyncExec(() -> {
                        if (chatDisplay.isDisposed()) return;
                        appendError("Error: " + e.getMessage());
                        input.setEnabled(true);
                        send.setEnabled(true);
                    });
                }
                return Status.OK_STATUS;
            }
        };
        job.schedule();
    }

    @Override
    public void dispose() {
        if (chatFont != null && !chatFont.isDisposed()) chatFont.dispose();
        super.dispose();
    }

    @Override
    public void setFocus() {
        input.setFocus();
    }
}
