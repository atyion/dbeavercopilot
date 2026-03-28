package dbeavercopilot.views;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.swt.SWT;
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
import org.jkiss.dbeaver.model.ai.AIAssistant;
import org.jkiss.dbeaver.model.ai.AIAssistantResponse;
import org.jkiss.dbeaver.model.ai.AICompletionSettings;
import org.jkiss.dbeaver.model.ai.AIDatabaseScope;
import org.jkiss.dbeaver.model.ai.AIMessage;
import org.jkiss.dbeaver.model.ai.AIPromptGenerator;
import org.jkiss.dbeaver.model.ai.engine.AIDatabaseContext;
import org.jkiss.dbeaver.model.ai.registry.AIAssistantRegistry;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.app.DBPWorkspace;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.logical.DBSLogicalDataSource;
import org.jkiss.dbeaver.model.runtime.DefaultProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.ai.controls.ScopeSelectorControl;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;

public class SampleView extends ViewPart {

    public static final String ID = "dbeavercopilot.views.SampleView";

    private static final Pattern SQL_BLOCK = Pattern.compile("```sql\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final String SYSTEM_PROMPT = """
        You are a helpful database assistant integrated in DBeaver.
        When you generate SQL queries, always wrap them in a ```sql ... ``` code block.
        For general questions or explanations, answer in plain text.
        Be concise and precise.
        """;

    private Composite parent;
    private Combo connectionCombo;
    private ScopeSelectorControl scopeSelector;
    private List<DBPDataSourceContainer> availableConnections = new ArrayList<>();
    private Text history;
    private Text input;
    private Button send;
    private Button insertSql;
    private String lastSql = null;
    private final List<AIMessage> chatHistory = new ArrayList<>();

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

        history = new Text(parent, SWT.MULTI | SWT.BORDER | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
        history.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        insertSql = new Button(parent, SWT.PUSH);
        insertSql.setText("Insert SQL into editor");
        insertSql.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        insertSql.setVisible(false);
        insertSql.addListener(SWT.Selection, e -> insertSqlIntoEditor());

        Composite bottom = new Composite(parent, SWT.NONE);
        bottom.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        bottom.setLayout(new GridLayout(3, false));

        input = new Text(bottom, SWT.BORDER);
        input.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        send = new Button(bottom, SWT.PUSH);
        send.setText("Send");
        send.addListener(SWT.Selection, e -> sendMessage());
        input.addListener(SWT.DefaultSelection, e -> sendMessage());

        Button clear = new Button(bottom, SWT.PUSH);
        clear.setText("Clear");
        clear.addListener(SWT.Selection, e -> {
            chatHistory.clear();
            history.setText("");
            insertSql.setVisible(false);
            lastSql = null;
        });
    }

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

        // Pre-select active SQL editor's connection
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

        // Get execution context from active SQL editor or default
        DBCExecutionContext executionContext = findEditorContext(container);
        if (executionContext == null) return;

        DBSLogicalDataSource logicalDataSource =
            DBSLogicalDataSource.createLogicalDataSource(container, executionContext);
        AICompletionSettings settings = new AICompletionSettings(container);

        if (scopeSelector != null && !scopeSelector.isDisposed()) {
            scopeSelector.setInput(logicalDataSource, executionContext);
        } else {
            // Create scope selector below connection row, above history
            // Insert before history widget — rebuild layout
            if (scopeSelector != null) scopeSelector.dispose();
            scopeSelector = new ScopeSelectorControl(parent, logicalDataSource, executionContext, settings);
            scopeSelector.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            // Move scope selector above history: it's the second child, history is third
            scopeSelector.moveAbove(history);
            parent.layout(true, true);
        }
    }

    // Must be called from UI thread
    private DBCExecutionContext findEditorContext(DBPDataSourceContainer container) {
        IEditorPart editor = PlatformUI.getWorkbench()
            .getActiveWorkbenchWindow().getActivePage().getActiveEditor();
        if (editor instanceof SQLEditor sqlEditor
                && container.equals(sqlEditor.getDataSourceContainer())) {
            return sqlEditor.getExecutionContext();
        }
        return null;
    }

    private String extractSql(String text) {
        Matcher m = SQL_BLOCK.matcher(text);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    private void insertSqlIntoEditor() {
        if (lastSql == null) return;
        IEditorPart editor = PlatformUI.getWorkbench()
            .getActiveWorkbenchWindow().getActivePage().getActiveEditor();
        if (!(editor instanceof SQLEditor sqlEditor)) {
            history.append("[No SQL editor active]\n\n");
            return;
        }
        var document = sqlEditor.getDocument();
        if (document == null) return;
        try {
            int offset = document.getLength();
            String insertion = (offset > 0 ? "\n" : "") + lastSql;
            document.replace(offset, 0, insertion);
            sqlEditor.getSelectionProvider().setSelection(new TextSelection(offset + insertion.length(), 0));
            history.append("[SQL inserted into editor]\n\n");
            insertSql.setVisible(false);
            lastSql = null;
        } catch (Exception e) {
            history.append("[Error inserting SQL: " + e.getMessage() + "]\n\n");
        }
    }

    private void sendMessage() {
        String message = input.getText().trim();
        if (message.isEmpty()) return;

        history.append("You: " + message + "\n");
        input.setText("");
        input.setEnabled(false);
        send.setEnabled(false);
        insertSql.setVisible(false);
        lastSql = null;

        chatHistory.add(AIMessage.userMessage(message));
        List<AIMessage> messageSnapshot = new ArrayList<>(chatHistory);

        // Capture scope state on UI thread
        AIDatabaseScope scope = scopeSelector != null ? scopeSelector.getScope() : AIDatabaseScope.CURRENT_SCHEMA;
        DBSLogicalDataSource logicalDs = scopeSelector != null ? scopeSelector.getDataSource() : null;
        DBCExecutionContext execCtx = scopeSelector != null ? scopeSelector.getExecutionContext() : null;

        Job job = new Job("AI Chat") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    AIDatabaseContext dbContext = null;
                    if (logicalDs != null && execCtx != null) {
                        AIDatabaseContext.Builder builder = new AIDatabaseContext.Builder(logicalDs)
                            .setScope(scope)
                            .setExecutionContext(execCtx);

                        if (scope == AIDatabaseScope.CUSTOM && scopeSelector != null) {
                            List<DBSObject> entities = scopeSelector.getCustomEntities(new DefaultProgressMonitor(monitor));
                            builder.setCustomEntities(entities);
                        }
                        dbContext = builder.build();
                    }

                    DBPWorkspace workspace = DBWorkbench.getPlatform().getWorkspace();
                    AIAssistant assistant = AIAssistantRegistry.getInstance().createAssistant(workspace);

                    AIPromptGenerator promptGenerator = new AIPromptGenerator() {
                        @Override
                        public String generatorId() { return "dbeavercopilot.chat"; }
                        @Override
                        public String build(AIDatabaseContext context) { return SYSTEM_PROMPT; }
                    };

                    AIAssistantResponse response = assistant.generateText(
                        new DefaultProgressMonitor(monitor),
                        dbContext,
                        promptGenerator,
                        messageSnapshot
                    );

                    String text = response.getText();
                    String sql = extractSql(text);
                    chatHistory.add(AIMessage.assistantMessage(text, null));

                    Display.getDefault().asyncExec(() -> {
                        if (!history.isDisposed()) {
                            history.append("AI: " + text + "\n\n");
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
                        if (!history.isDisposed()) {
                            history.append("Error: " + e.getMessage() + "\n\n");
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
    public void setFocus() {
        input.setFocus();
    }
}
