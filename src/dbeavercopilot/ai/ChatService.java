package dbeavercopilot.ai;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.IProgressMonitor;
import org.jkiss.dbeaver.model.ai.AIAssistant;
import org.jkiss.dbeaver.model.ai.AIAssistantResponse;
import org.jkiss.dbeaver.model.ai.AIDatabaseScope;
import org.jkiss.dbeaver.model.ai.AIMessage;
import org.jkiss.dbeaver.model.ai.AIPromptGenerator;
import org.jkiss.dbeaver.model.ai.engine.AIDatabaseContext;
import org.jkiss.dbeaver.model.ai.registry.AIAssistantRegistry;
import org.jkiss.dbeaver.model.app.DBPWorkspace;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.logical.DBSLogicalDataSource;
import org.jkiss.dbeaver.model.runtime.DefaultProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.runtime.DBWorkbench;

public class ChatService {

    private static final Pattern SQL_BLOCK =
        Pattern.compile("```sql\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private static final String SYSTEM_PROMPT = """
        You are a helpful database assistant integrated in DBeaver.
        When you generate SQL queries, always wrap them in a ```sql ... ``` code block.
        IMPORTANT RULES:
        - NEVER invent or assume column names. Only use columns explicitly provided in the schema context.
        - If you are unsure which column to use, ASK the user before generating SQL.
        - If no suitable column exists for the requested filter, say so clearly.
        - Always verify the schema before writing a query.
        For general questions or explanations, answer in plain text.
        Be concise and precise.
        """;

    public AIAssistantResponse send(
            IProgressMonitor monitor,
            List<AIMessage> messages,
            AIDatabaseScope scope,
            DBSLogicalDataSource logicalDs,
            DBCExecutionContext execCtx,
            List<DBSObject> customEntities) throws Exception {

        AIDatabaseContext dbContext = null;
        if (logicalDs != null && execCtx != null) {
            AIDatabaseContext.Builder builder = new AIDatabaseContext.Builder(logicalDs)
                .setScope(scope)
                .setExecutionContext(execCtx);
            if (customEntities != null) {
                builder.setCustomEntities(customEntities);
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

        return assistant.generateText(
            new DefaultProgressMonitor(monitor),
            dbContext,
            promptGenerator,
            messages
        );
    }

    public String extractSql(String text) {
        Matcher m = SQL_BLOCK.matcher(text);
        if (m.find()) return m.group(1).trim();
        return null;
    }
}
