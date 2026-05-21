package com.miniagent.core;

import com.miniagent.model.StructuredResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic validator for draft outputs.
 *
 * This does not replace the LLM critic.
 * It catches obvious failures cheaply before the critic/repair loop wastes
 * tokens.
 */
public class DraftSanityValidator {

    private static final Pattern TODO_PATTERN = Pattern.compile(
            "\\b(TODO|FIXME|TBD|XXX)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(
            "(your code here|implementation omitted|for brevity|not shown|remaining code|placeholder|insert .* here|replace this|stub only)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern INTERNAL_AGENT_PATTERN = Pattern.compile(
            "(taskclassifier|modelrouter|stoppolicy|agentrunstate|agentrunplan|safethoughtexecutor|repairmemory|critic said|evaluator said|as an ai agent loop)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FAKE_EXECUTION_PATTERN = Pattern.compile(
            "(i ran the tests|tests passed|compiled successfully|i executed|i checked the file|i searched the web|i opened the file)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern APOLOGY_FAILURE_PATTERN = Pattern.compile(
            "(sorry[, ]+i can'?t|i cannot complete|i am unable to complete|as an ai language model)",
            Pattern.CASE_INSENSITIVE);

    public DraftSanityResult validate(
            StructuredResponse response,
            DraftSanityContext context) {
        DraftSanityResult result = new DraftSanityResult();

        DraftSanityContext safeContext = context == null ? DraftSanityContext.empty() : context;

        if (response == null) {
            result.addCritical(
                    "NULL_RESPONSE",
                    "StructuredResponse is null.",
                    "Regenerate the draft using a fallback model.");
            return result.finish();
        }

        response.normalize();

        String summary = response.getSummary();
        String raw = response.getRaw();
        String thought = response.getThought_process();
        String combined = (summary + "\n" + raw + "\n" + thought).trim();
        String lower = combined.toLowerCase(Locale.ROOT);

        if (combined.isBlank()) {
            result.addCritical(
                    "EMPTY_OUTPUT",
                    "Draft is completely empty.",
                    "Regenerate the draft from scratch.");
            return result.finish();
        }

        validateSummary(summary, result);
        validateJsonLeak(summary, result);
        validateMarkdownFences(summary, result);
        validatePlaceholders(combined, result);
        validateInternalLeak(combined, result);
        validateUnsupportedExecutionClaims(combined, safeContext, result);
        validateTaskSpecificRisks(summary, safeContext, result);
        validateCodeOutput(summary, safeContext, result);
        validateMedicalOutput(summary, safeContext, result);
        validateLength(summary, safeContext, result);
        validateSpokenSummary(response, result);

        return result.finish();
    }

    private void validateSummary(String summary, DraftSanityResult result) {
        if (summary == null || summary.isBlank()) {
            result.addCritical(
                    "EMPTY_SUMMARY",
                    "Summary field is empty.",
                    "Populate summary with the main user-facing answer.");
            return;
        }

        String trimmed = summary.trim();

        if (trimmed.equals("{}") ||
                trimmed.equals("[]") ||
                trimmed.equalsIgnoreCase("null") ||
                trimmed.equalsIgnoreCase("none")) {
            result.addCritical(
                    "USELESS_SUMMARY",
                    "Summary contains no useful content.",
                    "Regenerate a meaningful answer.");
        }

        if (trimmed.length() < 2) {
            result.addMajor(
                    "TOO_SHORT",
                    "Summary is too short to be useful.",
                    "Expand the answer enough to satisfy the task.");
        }
    }

    private void validateJsonLeak(String summary, DraftSanityResult result) {
        if (summary == null || summary.isBlank()) {
            return;
        }

        String trimmed = summary.trim();

        boolean looksLikeRawJson = trimmed.startsWith("{") &&
                trimmed.endsWith("}") &&
                trimmed.contains("\"summary\"");

        if (looksLikeRawJson) {
            result.addMajor(
                    "RAW_JSON_LEAK",
                    "Summary appears to contain raw JSON instead of user-facing content.",
                    "Extract the actual answer into summary.");
        }
    }

    private void validateMarkdownFences(String summary, DraftSanityResult result) {
        if (summary == null || summary.isBlank()) {
            return;
        }

        int fenceCount = countOccurrences(summary, "```");
        if (fenceCount % 2 != 0) {
            result.addCritical(
                    "UNCLOSED_CODE_FENCE",
                    "Markdown code fence is not closed.",
                    "Close every markdown code block.");
        }
    }

    private void validatePlaceholders(String combined, DraftSanityResult result) {
        if (combined == null || combined.isBlank()) {
            return;
        }

        if (TODO_PATTERN.matcher(combined).find()) {
            result.addCritical(
                    "TODO_PLACEHOLDER",
                    "Draft contains TODO/FIXME/TBD markers.",
                    "Replace all TODO/FIXME/TBD markers with complete content.");
        }

        if (PLACEHOLDER_PATTERN.matcher(combined).find()) {
            result.addCritical(
                    "PLACEHOLDER_CONTENT",
                    "Draft contains placeholder or omitted implementation text.",
                    "Provide the full concrete implementation/content.");
        }

        if (combined.contains("...") && looksLikeCode(combined)) {
            result.addMajor(
                    "ELLIPSIS_IN_CODE",
                    "Code-like output contains ellipsis, which may indicate omitted code.",
                    "Replace ellipsis with real code or remove it if not needed.");
        }
    }

    private void validateInternalLeak(String combined, DraftSanityResult result) {
        if (combined == null || combined.isBlank()) {
            return;
        }

        if (INTERNAL_AGENT_PATTERN.matcher(combined).find()) {
            result.addMajor(
                    "INTERNAL_AGENT_LEAK",
                    "Draft appears to expose internal agent architecture or critic loop details.",
                    "Remove internal agent-stage references from the user-facing answer.");
        }
    }

    private void validateUnsupportedExecutionClaims(
            String combined,
            DraftSanityContext context,
            DraftSanityResult result) {
        if (combined == null || combined.isBlank()) {
            return;
        }

        if (!context.isToolExecutionAllowed() &&
                FAKE_EXECUTION_PATTERN.matcher(combined).find()) {
            result.addCritical(
                    "UNSUPPORTED_EXECUTION_CLAIM",
                    "Draft claims tests/files/web/code execution were performed, but tools were not available.",
                    "Remove unsupported execution claims or clearly state that this is a static review.");
        }
    }

    private void validateTaskSpecificRisks(
            String summary,
            DraftSanityContext context,
            DraftSanityResult result) {
        String task = safeLower(context.getOriginalTask());
        String output = safeLower(summary);

        if (task.contains("complete code") ||
                task.contains("full code") ||
                task.contains("no placeholder") ||
                task.contains("compile-ready") ||
                task.contains("compile ready")) {

            if (!looksLikeCode(output)) {
                result.addMajor(
                        "EXPECTED_CODE_MISSING",
                        "Task appears to request code, but output does not look like code.",
                        "Provide the requested complete code.");
            }
        }

        if ((task.contains("json") || task.contains("structured json")) &&
                !containsJsonLikeContent(summary)) {
            result.addMajor(
                    "EXPECTED_JSON_MISSING",
                    "Task appears to request JSON, but output does not contain JSON-like content.",
                    "Return the requested JSON structure.");
        }
    }

    private void validateCodeOutput(
            String summary,
            DraftSanityContext context,
            DraftSanityResult result) {
        String taskType = safeLower(context.getTaskType());
        String task = safeLower(context.getOriginalTask());
        String output = safeLower(summary);

        boolean codeTask = context.isCodeSanityEnabled() ||
                taskType.contains("code") ||
                task.contains("java") ||
                task.contains("spring") ||
                task.contains("class") ||
                task.contains("method") ||
                task.contains("function") ||
                task.contains("compile") ||
                looksLikeCode(output);

        if (!codeTask) {
            return;
        }

        if (output.contains("pseudocode") || output.contains("pseudo-code")) {
            result.addMajor(
                    "PSEUDOCODE_IN_CODE_TASK",
                    "Output includes pseudocode for a code task.",
                    "Replace pseudocode with concrete code.");
        }

        if (output.contains("undefined") ||
                output.contains("missing import") ||
                output.contains("does not compile") ||
                output.contains("compile error")) {
            result.addCritical(
                    "CODE_SELF_REPORTED_BROKEN",
                    "Output itself indicates broken or incomplete code.",
                    "Repair code until it is internally coherent.");
        }

        if (output.contains("public class") && !summary.contains("```")) {
            result.addMinor(
                    "CODE_NOT_FENCED",
                    "Code appears outside markdown fences.",
                    "Wrap code in a fenced code block with the correct language tag.");
        }

        if (summary.contains("```") && !hasLanguageTaggedFence(summary)) {
            result.addMinor(
                    "CODE_FENCE_WITHOUT_LANGUAGE",
                    "Code fence lacks a language tag.",
                    "Use a language tag such as ```java.");
        }

        validateLikelyJavaBalance(summary, result);
    }

    private void validateMedicalOutput(
            String summary,
            DraftSanityContext context,
            DraftSanityResult result) {
        String taskType = safeLower(context.getTaskType());
        String task = safeLower(context.getOriginalTask());

        boolean medicalTask = taskType.contains("medical") ||
                task.contains("patient") ||
                task.contains("diagnosis") ||
                task.contains("dose") ||
                task.contains("inj ") ||
                task.contains("icu") ||
                task.contains("abg");

        if (!medicalTask) {
            return;
        }

        String output = safeLower(summary);

        if (output.contains("guaranteed") ||
                output.contains("definitely safe") ||
                output.contains("no risk")) {
            result.addMajor(
                    "OVERCONFIDENT_MEDICAL_LANGUAGE",
                    "Medical output contains overconfident safety/certainty wording.",
                    "Use clinically cautious wording and avoid unsupported certainty.");
        }

        if (output.contains("as your doctor") || output.contains("i prescribe")) {
            result.addMajor(
                    "UNSAFE_MEDICAL_PERSONA",
                    "Output may imply direct prescribing authority.",
                    "Use appropriate clinical advisory wording.");
        }
    }

    /**
     * Performs length sanity checks without sabotaging large code/freeform output.
     *
     * The validator is called through DraftSanityContext, whose fourth argument is
     * now expected complexity, not a hard max summary length. Older code used that
     * integer as maxSummaryCharacters, which accidentally made a complexity score
     * like 8 mean "summary longer than 8 characters is too long". That caused
     * perfectly valid large code to be marked as too long.
     *
     * A hard length limit is still supported through setMaxSummaryCharacters(...)
     * for future UI-specific summaries, but normal worker answers should not have
     * one. Large/code tasks are allowed to be long by design.
     */
    private void validateLength(
            String summary,
            DraftSanityContext context,
            DraftSanityResult result) {
        if (summary == null || context == null) {
            return;
        }

        int maxChars = context.getMaxSummaryCharacters();
        if (maxChars <= 0) {
            return;
        }

        if (context.isCodeSanityEnabled() || context.getExpectedComplexity() >= 7) {
            return;
        }

        if (summary.length() > maxChars) {
            result.addMinor(
                    "SUMMARY_TOO_LONG",
                    "Summary exceeds configured maximum length.",
                    "Compress the answer without removing required content.");
        }
    }

    private void validateSpokenSummary(
            StructuredResponse response,
            DraftSanityResult result) {
        String spoken = response.getSpoken_summary();
        String summary = response.getSummary();

        if (summary.length() > 1000 && spoken.length() > 700) {
            result.addMinor(
                    "SPOKEN_SUMMARY_TOO_LONG",
                    "spoken_summary is too long for TTS.",
                    "Replace spoken_summary with a short natural overview.");
        }

        if (spoken.contains("```")) {
            result.addMajor(
                    "TTS_CONTAINS_CODE",
                    "spoken_summary contains code fences.",
                    "Do not read code aloud in spoken_summary.");
        }
    }

    private void validateLikelyJavaBalance(String summary, DraftSanityResult result) {
        if (summary == null || summary.isBlank()) {
            return;
        }

        if (!summary.contains("public class") &&
                !summary.contains("class ") &&
                !summary.contains("interface ") &&
                !summary.contains("enum ")) {
            return;
        }

        int openBraces = countChar(summary, '{');
        int closeBraces = countChar(summary, '}');

        if (Math.abs(openBraces - closeBraces) >= 2) {
            result.addMajor(
                    "UNBALANCED_BRACES",
                    "Code appears to have significantly unbalanced braces.",
                    "Check and balance all class/method/control-flow braces.");
        }

        int openParens = countChar(summary, '(');
        int closeParens = countChar(summary, ')');

        if (Math.abs(openParens - closeParens) >= 2) {
            result.addMajor(
                    "UNBALANCED_PARENTHESES",
                    "Code appears to have significantly unbalanced parentheses.",
                    "Check method calls, declarations, and conditionals.");
        }
    }

    private boolean looksLikeCode(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String lower = text.toLowerCase();

        return lower.contains("public class") ||
                lower.contains("private final") ||
                lower.contains("public static void") ||
                lower.contains("import java.") ||
                lower.contains("@service") ||
                lower.contains("@restcontroller") ||
                lower.contains("function ") ||
                lower.contains("const ") ||
                lower.contains("let ") ||
                lower.contains("class ") ||
                lower.contains("package ");
    }

    private boolean containsJsonLikeContent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String trimmed = text.trim();

        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                (trimmed.startsWith("[") && trimmed.endsWith("]")) ||
                trimmed.contains("```json");
    }

    private boolean hasLanguageTaggedFence(String text) {
        if (text == null) {
            return false;
        }

        return text.contains("```java") ||
                text.contains("```javascript") ||
                text.contains("```typescript") ||
                text.contains("```python") ||
                text.contains("```json") ||
                text.contains("```html") ||
                text.contains("```css") ||
                text.contains("```xml") ||
                text.contains("```sql") ||
                text.contains("```bash") ||
                text.contains("```kotlin") ||
                text.contains("```swift") ||
                text.contains("```go") ||
                text.contains("```rust") ||
                text.contains("```cpp") ||
                text.contains("```csharp");
    }

    private int countOccurrences(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) {
            return 0;
        }

        int count = 0;
        int index = 0;

        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }

        return count;
    }

    private int countChar(String text, char target) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int count = 0;

        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == target) {
                count++;
            }
        }

        return count;
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /**
     * Immutable-ish context object used by deterministic validation.
     *
     * The validator deliberately accepts a small context object instead of the
     * whole AgentRunPlan. That keeps this class reusable and prevents it from
     * growing into another orchestration layer. The context tells the validator
     * enough to know whether code checks should run, whether tool execution claims
     * are allowed, and how strict the output should be.
     */
    public static class DraftSanityContext {

        private String originalTask = "";
        private String taskType = "";
        private boolean codeSanityEnabled;
        private boolean toolExecutionAllowed;
        private int expectedComplexity;
        private int maxSummaryCharacters;

        /**
         * Creates a blank low-strictness context.
         *
         * This is used when older callers invoke the validator without plan
         * information. The validator still catches empty output and obvious raw
         * JSON leaks, but it avoids task-specific overreach.
         */
        public static DraftSanityContext empty() {
            return new DraftSanityContext();
        }

        /**
         * Builds the normal validator context used by SafeThoughtExecutor.
         *
         * Parameter meaning is intentionally aligned with SafeThoughtExecutor:
         * - originalTask: the user task or repair context being checked
         * - taskType: a compact stage/task/difficulty/pipeline label
         * - codeSanityEnabled: whether code-specific checks should be active
         * - expectedComplexity: 0-10 rough strictness derived from AgentRunPlan
         *
         * Tool execution permission is inferred from the task label. This prevents
         * the old bug where the third boolean was treated as tool permission even
         * though the caller was actually passing "code sanity enabled".
         */
        public static DraftSanityContext of(
                String originalTask,
                String taskType,
                boolean codeSanityEnabled,
                int expectedComplexity) {
            DraftSanityContext context = new DraftSanityContext();
            context.setOriginalTask(originalTask);
            context.setTaskType(taskType);
            context.setCodeSanityEnabled(codeSanityEnabled);
            context.setExpectedComplexity(expectedComplexity);
            context.setToolExecutionAllowed(inferToolExecutionAllowed(taskType));
            context.setMaxSummaryCharacters(0);
            return context;
        }

        /**
         * Creates a context when a future caller explicitly knows tool permission.
         *
         * This overload is not required by the current flow, but it prevents the
         * class from needing another breaking API change when tool-backed drafting
         * starts passing true execution state into deterministic validation.
         */
        public static DraftSanityContext of(
                String originalTask,
                String taskType,
                boolean codeSanityEnabled,
                boolean toolExecutionAllowed,
                int expectedComplexity,
                int maxSummaryCharacters) {
            DraftSanityContext context = new DraftSanityContext();
            context.setOriginalTask(originalTask);
            context.setTaskType(taskType);
            context.setCodeSanityEnabled(codeSanityEnabled);
            context.setToolExecutionAllowed(toolExecutionAllowed);
            context.setExpectedComplexity(expectedComplexity);
            context.setMaxSummaryCharacters(maxSummaryCharacters);
            return context;
        }

        public String getOriginalTask() {
            return originalTask;
        }

        public void setOriginalTask(String originalTask) {
            this.originalTask = originalTask == null ? "" : originalTask;
        }

        public String getTaskType() {
            return taskType;
        }

        public void setTaskType(String taskType) {
            this.taskType = taskType == null ? "" : taskType;
        }

        public boolean isCodeSanityEnabled() {
            return codeSanityEnabled;
        }

        public void setCodeSanityEnabled(boolean codeSanityEnabled) {
            this.codeSanityEnabled = codeSanityEnabled;
        }

        public boolean isToolExecutionAllowed() {
            return toolExecutionAllowed;
        }

        public void setToolExecutionAllowed(boolean toolExecutionAllowed) {
            this.toolExecutionAllowed = toolExecutionAllowed;
        }

        public int getExpectedComplexity() {
            return expectedComplexity;
        }

        public void setExpectedComplexity(int expectedComplexity) {
            this.expectedComplexity = Math.max(0, Math.min(10, expectedComplexity));
        }

        public int getMaxSummaryCharacters() {
            return maxSummaryCharacters;
        }

        public void setMaxSummaryCharacters(int maxSummaryCharacters) {
            this.maxSummaryCharacters = Math.max(0, maxSummaryCharacters);
        }

        private static boolean inferToolExecutionAllowed(String taskType) {
            if (taskType == null || taskType.isBlank()) {
                return false;
            }

            String lower = taskType.toLowerCase(Locale.ROOT);

            return lower.contains("tool_agent")
                    || lower.contains("tool_required")
                    || lower.contains("needs_tools")
                    || lower.contains("tool/");
        }
    }

    public static class DraftSanityResult {

        private final List<DraftSanityIssue> issues = new ArrayList<>();
        private boolean passed = true;
        private int highestSeverity = 0;

        private void addCritical(String code, String message, String fix) {
            addIssue("critical", code, message, fix, 10);
        }

        private void addMajor(String code, String message, String fix) {
            addIssue("major", code, message, fix, 7);
        }

        private void addMinor(String code, String message, String fix) {
            addIssue("minor", code, message, fix, 3);
        }

        private void addIssue(
                String severity,
                String code,
                String message,
                String fix,
                int severityScore) {
            DraftSanityIssue issue = new DraftSanityIssue(
                    severity,
                    code,
                    message,
                    fix,
                    severityScore);

            if (!issues.contains(issue)) {
                issues.add(issue);
            }

            highestSeverity = Math.max(highestSeverity, severityScore);

            if (severityScore >= 7) {
                passed = false;
            }
        }

        private DraftSanityResult finish() {
            issues.sort((a, b) -> Integer.compare(b.getSeverityScore(), a.getSeverityScore()));
            return this;
        }

        public boolean isPassed() {
            return passed;
        }

        public boolean hasCriticalIssues() {
            return highestSeverity >= 10;
        }

        public boolean hasMajorIssues() {
            return highestSeverity >= 7;
        }

        public int getHighestSeverity() {
            return highestSeverity;
        }

        public List<DraftSanityIssue> getIssues() {
            return Collections.unmodifiableList(issues);
        }

        public List<String> toRepairInstructions() {
            List<String> fixes = new ArrayList<>();

            for (DraftSanityIssue issue : issues) {
                if (issue.getFix() != null && !issue.getFix().isBlank()) {
                    fixes.add(issue.getCode() + ": " + issue.getFix());
                }
            }

            return fixes;
        }

        public String compactSummary() {
            if (issues.isEmpty()) {
                return "No deterministic sanity issues found.";
            }

            StringBuilder sb = new StringBuilder();
            for (DraftSanityIssue issue : issues) {
                sb.append("- ")
                        .append(issue.getSeverity())
                        .append(" / ")
                        .append(issue.getCode())
                        .append(": ")
                        .append(issue.getMessage())
                        .append(" Fix: ")
                        .append(issue.getFix())
                        .append("\n");
            }

            return sb.toString().trim();
        }
    }

    public static class DraftSanityIssue {

        private final String severity;
        private final String code;
        private final String message;
        private final String fix;
        private final int severityScore;

        public DraftSanityIssue(
                String severity,
                String code,
                String message,
                String fix,
                int severityScore) {
            this.severity = severity == null ? "minor" : severity;
            this.code = code == null ? "UNKNOWN" : code;
            this.message = message == null ? "" : message;
            this.fix = fix == null ? "" : fix;
            this.severityScore = severityScore;
        }

        public String getSeverity() {
            return severity;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        public String getFix() {
            return fix;
        }

        public int getSeverityScore() {
            return severityScore;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof DraftSanityIssue that)) {
                return false;
            }

            return this.code.equals(that.code) &&
                    this.message.equals(that.message);
        }

        @Override
        public int hashCode() {
            return (code + "|" + message).hashCode();
        }

        @Override
        public String toString() {
            return "DraftSanityIssue{" +
                    "severity='" + severity + '\'' +
                    ", code='" + code + '\'' +
                    ", message='" + message + '\'' +
                    ", fix='" + fix + '\'' +
                    ", severityScore=" + severityScore +
                    '}';
        }
    }
}