package dbeavercopilot.views;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.ai.AIAssistantResponse;
import org.jkiss.dbeaver.model.ai.AICompletionSettings;
import org.jkiss.dbeaver.model.ai.AIDatabaseScope;
import org.jkiss.dbeaver.model.ai.AIMessage;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.app.DBPWorkspace;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.logical.DBSLogicalDataSource;
import org.jkiss.dbeaver.model.runtime.DefaultProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.ai.controls.ScopeSelectorControl;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;

import dbeavercopilot.ai.ChatService;

public class CopilotView extends ViewPart {

    public static final String ID = "dbeavercopilot.views.CopilotView";

    private final ChatService chatService = new ChatService();
    private final List<AIMessage> chatHistory = new ArrayList<>();

    private Composite parent;
    private Combo connectionCombo;
    private ScopeSelectorControl scopeSelector;
    private List<DBPDataSourceContainer> availableConnections = new ArrayList<>();

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

        // Connection row
        Composite connRow = new Composite(parent, SWT.NONE);
        connRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        connRow.setLayout(new GridLayout(3, false));

        new Label(connRow, SWT.NONE).setText("Connection:");
        connectionCombo = new Combo(connRow, SWT.DROP_DOWN | SWT.READ_ONLY);
        connectionCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        connectionCombo.addListener(SWT.Selection, e -> refreshScopeSelector());

        Button refreshBtn = new Button(connRow, SWT.PUSH);
        refreshBtn.setText("Refresh");
        refreshBtn.addListener(SWT.Selection, e -> loadConnections());

        loadConnections();

        // Separator
        new Label(parent, SWT.SEPARATOR | SWT.HORIZONTAL)
            .setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Chat display — StyledText: no cursor, styled speaker names, bigger font
        chatDisplay = new StyledText(parent, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
        chatDisplay.setEditable(false);
        chatDisplay.setCaret(null);
        chatDisplay.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        chatDisplay.setLeftMargin(8);
        chatDisplay.setRightMargin(8);
        chatDisplay.setTopMargin(6);
        chatDisplay.setBottomMargin(6);
        chatDisplay.setLineSpacing(3);

        FontData[] fontData = chatDisplay.getFont().getFontData();
        for (FontData fd : fontData) {
            fd.setHeight(fd.getHeight() + 1);
        }
        chatFont = new Font(Display.getDefault(), fontData);
        chatDisplay.setFont(chatFont);

        // Separator
        new Label(parent, SWT.SEPARATOR | SWT.HORIZONTAL)
            .setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Insert SQL button (hidden until SQL is available)
        insertSql = new Button(parent, SWT.PUSH);
        insertSql.setText("Insert SQL into editor");
        insertSql.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        insertSql.setVisible(false);
        insertSql.addListener(SWT.Selection, e -> insertSqlIntoEditor());

        // Input row — bordered container so l'input sembra un campo "chat"
        Composite inputRow = new Composite(parent, SWT.BORDER);
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
        clear.setText("Clear");
        clear.addListener(SWT.Selection, e -> {
            chatHistory.clear();
            chatDisplay.setText("");
            insertSql.setVisible(false);
            lastSql = null;
        });
    }

    // --- Chat display helpers ---

    private void appendUserMessage(String text) {
        String header = "You\n";
        int start = chatDisplay.getCharCount();
        chatDisplay.append(header + text + "\n\n");

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

    // --- Connection / scope ---

    private void loadConnections() {
        availableConnections.clear();
        connectionCombo.removeAll();

        DBPWorkspace workspace = DBWorkbench.getPlatform().getWorkspace();
        for (DBPProject project : workspace.getProjects()) {
            for (DBPDataSourceContainer ds : project.getDataSourceRegistry().getDataSources()) {
                availableConnections.add(ds);
                connectionCombo.add(ds.getName() + (ds.isConnected() ? "" : " [disconnected]"));
            }
        }

        int selectIdx = 0;
        IEditorPart editor = PlatformUI.getWorkbench()
            .getActiveWorkbenchWindow().getActivePage().getActiveEditor();
        if (editor instanceof SQLEditor sqlEditor && sqlEditor.getDataSourceContainer() != null) {
            String activeName = sqlEditor.getDataSourceContainer().getName();
            for (int i = 0; i < availableConnections.size(); i++) {
                if (availableConnections.get(i).getName().equals(activeName)) {
                    selectIdx = i;
                    break;
                }
            }
        }
        if (!availableConnections.isEmpty()) {
            connectionCombo.select(selectIdx);
            refreshScopeSelector();
        }
    }

    private void refreshScopeSelector() {
        int idx = connectionCombo.getSelectionIndex();
        if (idx < 0 || idx >= availableConnections.size()) return;

        DBPDataSourceContainer container = availableConnections.get(idx);
        if (!container.isConnected()) return;

        DBCExecutionContext executionContext = findEditorContext(container);
        if (executionContext == null) return;

        DBSLogicalDataSource logicalDataSource =
            DBSLogicalDataSource.createLogicalDataSource(container, executionContext);
        AICompletionSettings settings = new AICompletionSettings(container);

        if (scopeSelector != null && !scopeSelector.isDisposed()) {
            scopeSelector.setInput(logicalDataSource, executionContext);
        } else {
            if (scopeSelector != null) scopeSelector.dispose();
            scopeSelector = new ScopeSelectorControl(parent, logicalDataSource, executionContext, settings);
            scopeSelector.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            scopeSelector.moveAbove(chatDisplay);
            parent.layout(true, true);
        }
    }

    private DBCExecutionContext findEditorContext(DBPDataSourceContainer container) {
        IEditorPart editor = PlatformUI.getWorkbench()
            .getActiveWorkbenchWindow().getActivePage().getActiveEditor();
        if (editor instanceof SQLEditor sqlEditor
                && container.equals(sqlEditor.getDataSourceContainer())) {
            return sqlEditor.getExecutionContext();
        }
        return null;
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

        appendUserMessage(message);
        input.setText("");
        input.setEnabled(false);
        send.setEnabled(false);
        insertSql.setVisible(false);
        lastSql = null;

        chatHistory.add(AIMessage.userMessage(message));
        List<AIMessage> messageSnapshot = new ArrayList<>(chatHistory);

        AIDatabaseScope scope = scopeSelector != null ? scopeSelector.getScope() : AIDatabaseScope.CURRENT_SCHEMA;
        DBSLogicalDataSource logicalDs = scopeSelector != null ? scopeSelector.getDataSource() : null;
        DBCExecutionContext execCtx = scopeSelector != null ? scopeSelector.getExecutionContext() : null;

        Job job = new Job("AI Chat") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    List<DBSObject> customEntities = null;
                    if (scope == AIDatabaseScope.CUSTOM && scopeSelector != null) {
                        customEntities = scopeSelector.getCustomEntities(new DefaultProgressMonitor(monitor));
                    }

                    AIAssistantResponse response = chatService.send(
                        monitor, messageSnapshot, scope, logicalDs, execCtx, customEntities);

                    String text = response.getText();
                    String sql = chatService.extractSql(text);
                    chatHistory.add(AIMessage.assistantMessage(text, null));

                    Display.getDefault().asyncExec(() -> {
                        if (!chatDisplay.isDisposed()) {
                            appendAssistantMessage(text);
                            if (sql != null) {
                                lastSql = sql;
                                insertSql.setVisible(true);
                                parent.layout();
                            }
                            input.setEnabled(true);
                            send.setEnabled(true);
                            input.setFocus();
                        }
                    });
                } catch (Exception e) {
                    Display.getDefault().asyncExec(() -> {
                        if (!chatDisplay.isDisposed()) {
                            appendError("Error: " + e.getMessage());
                            input.setEnabled(true);
                            send.setEnabled(true);
                        }
                    });
                }
                return Status.OK_STATUS;
            }
        };
        job.schedule();
    }

    @Override
    public void dispose() {
        if (chatFont != null && !chatFont.isDisposed()) {
            chatFont.dispose();
        }
        super.dispose();
    }

    @Override
    public void setFocus() {
        input.setFocus();
    }
}
