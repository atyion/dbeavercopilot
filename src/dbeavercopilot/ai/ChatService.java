package dbeavercopilot.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.IProgressMonitor;
import org.jkiss.dbeaver.model.ai.AIAssistant;
import org.jkiss.dbeaver.model.ai.AIAssistantResponse;
import org.jkiss.dbeaver.model.ai.AIMessage;
import org.jkiss.dbeaver.model.ai.AIPromptGenerator;
import org.jkiss.dbeaver.model.ai.engine.AIDatabaseContext;
import org.jkiss.dbeaver.model.ai.registry.AIAssistantRegistry;
import org.jkiss.dbeaver.model.app.DBPWorkspace;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCResultSetMetaData;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.runtime.DefaultProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;

public class ChatService {

    public interface ExploreCallback {
        void onExplore(String sql, String result);
    }

    private static final Pattern SQL_BLOCK =
        Pattern.compile("```sql\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private static final Pattern EXPLORE_BLOCK =
        Pattern.compile("<explore>([\\s\\S]*?)</explore>", Pattern.CASE_INSENSITIVE);

    private static final int MAX_EXPLORE_ITERATIONS = 6;
    private static final int MAX_RESULT_ROWS = 200;

    private static String buildSystemPrompt(String dbType, String connectionName) {
        return """
            You are a helpful database assistant integrated in DBeaver.
            Database type: """ + dbType + """

            Current connection/database: """ + connectionName + """

            IMPORTANT — object naming convention:
            - When the user writes schema.table (e.g. myschema.orders), the first part is the SCHEMA name, the second is the TABLE name.
            - When the user writes db.schema.table, the parts are DATABASE, SCHEMA, TABLE.
            - Never confuse a schema name for a table name.
            - In information_schema queries always filter by BOTH table_schema and table_name when the user gives dot notation.

            Example: user says "newfinv.richieste_massive" → table_schema = 'newfinv', table_name = 'richieste_massive'.

            You do NOT have the schema pre-loaded. Explore it yourself by writing SELECT queries
            on information_schema tables, wrapped in <explore> tags:

            <explore>SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'newfinv' AND table_name = 'richieste_massive'</explore>

            You will receive the query result and can continue exploring or give the final answer.

            Rules:
            - Only use <explore> for schema discovery (SELECT only, system/information_schema tables only).
            - When you have enough information, generate the final SQL wrapped in ```sql ... ```.
            - NEVER invent column names. Always verify via exploration first.
            - If the user mentions a table without specifying the schema, explore information_schema to find it yourself before asking.
            - If unsure about the user's intent, ask before exploring.
            - Be concise.
            """;
    }

    public AIAssistantResponse send(
            IProgressMonitor monitor,
            List<AIMessage> messages,
            DBCExecutionContext execCtx,
            String dbType,
            String connectionName,
            ExploreCallback exploreCallback) throws Exception {

        DBPWorkspace workspace = DBWorkbench.getPlatform().getWorkspace();
        AIAssistant assistant = AIAssistantRegistry.getInstance().createAssistant(workspace);

        final String systemPrompt = buildSystemPrompt(dbType, connectionName);
        AIPromptGenerator promptGenerator = new AIPromptGenerator() {
            @Override
            public String generatorId() { return "dbeavercopilot.chat"; }
            @Override
            public String build(AIDatabaseContext context) { return systemPrompt; }
        };

        List<AIMessage> workingMessages = new ArrayList<>(messages);

        for (int i = 0; i < MAX_EXPLORE_ITERATIONS; i++) {
            AIAssistantResponse response = assistant.generateText(
                new DefaultProgressMonitor(monitor),
                null,
                promptGenerator,
                workingMessages
            );

            String text = response.getText();
            String exploreSql = extractExploreSql(text);

            if (exploreSql == null) {
                return response;
            }

            String result = executeSql(exploreSql, execCtx, monitor);
            if (exploreCallback != null) {
                exploreCallback.onExplore(exploreSql, result);
            }
            workingMessages.add(AIMessage.assistantMessage(text, null));
            workingMessages.add(AIMessage.userMessage("[Explore result]\n" + result));
        }

        throw new Exception("Max schema exploration iterations reached without a final answer.");
    }

    private String extractExploreSql(String text) {
        Matcher m = EXPLORE_BLOCK.matcher(text);
        if (!m.find()) return null;
        String sql = m.group(1).trim();
        if (!sql.toLowerCase().startsWith("select")) return null;
        return sql;
    }

    private String executeSql(String sql, DBCExecutionContext execCtx, IProgressMonitor monitor)
            throws Exception {
        DefaultProgressMonitor pm = new DefaultProgressMonitor(monitor);
        try (DBCSession session = execCtx.openSession(pm, DBCExecutionPurpose.USER_SCRIPT, "Schema exploration")) {
            try (DBCStatement stmt = session.prepareStatement(DBCStatementType.QUERY, sql, false, false, false)) {
                stmt.executeStatement();
                try (DBCResultSet rs = stmt.openResultSet()) {
                    if (rs == null) return "(no results)";
                    DBCResultSetMetaData meta = rs.getMeta();
                    List<? extends org.jkiss.dbeaver.model.exec.DBCAttributeMetaData> attrs = meta.getAttributes();

                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < attrs.size(); i++) {
                        if (i > 0) sb.append(" | ");
                        sb.append(attrs.get(i).getName());
                    }
                    sb.append("\n");

                    int rowCount = 0;
                    while (rs.nextRow() && rowCount < MAX_RESULT_ROWS) {
                        for (int i = 0; i < attrs.size(); i++) {
                            if (i > 0) sb.append(" | ");
                            Object val = rs.getAttributeValue(i);
                            sb.append(val != null ? val.toString() : "NULL");
                        }
                        sb.append("\n");
                        rowCount++;
                    }
                    if (rowCount == MAX_RESULT_ROWS) {
                        sb.append("(truncated at ").append(MAX_RESULT_ROWS).append(" rows)");
                    }
                    return sb.toString();
                }
            }
        }
    }

    public String extractSql(String text) {
        Matcher m = SQL_BLOCK.matcher(text);
        if (m.find()) return m.group(1).trim();
        return null;
    }
}
