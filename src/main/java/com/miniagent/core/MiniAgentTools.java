package com.miniagent.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * MiniAgentTools is the compact tool layer for MiniAgent.
 *
 * Design:
 * - One class, no fake wrapper zoo.
 * - Local repo read/search/list tools.
 * - CodeWeaver REST tools for AST-aware edits and symbol/slice operations.
 * - Optional command execution with strict safety gates.
 * - Write operations are disabled unless explicitly enabled.
 *
 * Intended agent flow:
 * - First use read-only tools: list_files, read_file, search_code, slice.
 * - Then use CodeWeaver /apply for safe AST-aware edits.
 * - Raw overwrite /codemod is allowed only if config.allowRawCodemod is true.
 * - Commands are allowed only if config.allowCommandExecution is true.
 */
public final class MiniAgentTools implements AutoCloseable {

    private static final int DEFAULT_MAX_READ_BYTES = 1_500_000;
    private static final int DEFAULT_MAX_SEARCH_BYTES_PER_FILE = 750_000;
    private static final int DEFAULT_MAX_SEARCH_RESULTS = 100;
    private static final int DEFAULT_MAX_LIST_RESULTS = 300;
    private static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 120;

    private static final Set<String> DEFAULT_IGNORED_DIRS = Set.of(
            ".git",
            ".idea",
            ".gradle",
            "build",
            "target",
            "out",
            "node_modules",
            ".codeweaver_index",
            ".DS_Store");

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".java", ".kt", ".kts", ".xml", ".gradle", ".properties", ".yml", ".yaml",
            ".json", ".txt", ".md", ".html", ".css", ".js", ".ts", ".jsx", ".tsx",
            ".py", ".sh", ".bat", ".cmd", ".c", ".cpp", ".h", ".hpp", ".cs", ".go",
            ".rs", ".sql", ".dockerfile", ".gitignore", ".env", ".conf", ".ini");

    private final Config config;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public MiniAgentTools(Config config) {
        this.config = config == null ? Config.builder().build() : config.normalized();
        this.mapper = this.config.objectMapper == null ? new ObjectMapper() : this.config.objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(this.config.httpConnectTimeoutSeconds))
                .build();
    }

    /**
     * Execute a tool by name.
     *
     * Supported tool names:
     * - health
     * - index_status
     * - list_files
     * - file_stat
     * - read_file
     * - search_code
     * - slice
     * - symbols
     * - resolve_symbol
     * - apply
     * - format_file
     * - organize_imports
     * - add_import
     * - add_function
     * - replace_function_body
     * - anchor_insert_before
     * - anchor_insert_after
     * - codemod_write
     * - run_command
     * - run_tests
     */
    /**
     * Executes the requested tool operation securely.
     * 
     * Deep Insight:
     * This acts as the master dispatch switch for the Agent's read/write actions.
     * It strictly adheres to the configuration flags (e.g., 'allowWrites', 'allowRawCodemod')
     * to prevent rogue AI behavior from corrupting the filesystem or executing dangerous
     * terminal commands without explicit authorization.
     */
    public ToolResult execute(String toolName, Map<String, Object> args) {
        String name = safeToolName(toolName);
        Map<String, Object> safeArgs = args == null ? Map.of() : args;

        try {
            return switch (name) {
                case "health" -> health();
                case "index_status" -> indexStatus();
                case "list_files" -> listFiles(safeArgs);
                case "file_stat" -> fileStat(safeArgs);
                case "read_file" -> readFile(safeArgs);
                case "search_code" -> searchCode(safeArgs);
                case "slice" -> slice(safeArgs);
                case "symbols" -> symbols(safeArgs);
                case "resolve_symbol" -> resolveSymbol(safeArgs);
                case "apply" -> apply(safeArgs);
                case "format_file" -> applySimpleOperation("FORMAT_FILE", safeArgs);
                case "organize_imports" -> applySimpleOperation("ORGANIZE_IMPORTS", safeArgs);
                case "add_import" -> addImport(safeArgs);
                case "add_function" -> addFunction(safeArgs);
                case "replace_function_body" -> replaceFunctionBody(safeArgs);
                case "anchor_insert_before" -> anchorInsert("ANCHOR_INSERT_BEFORE", safeArgs);
                case "anchor_insert_after" -> anchorInsert("ANCHOR_INSERT_AFTER", safeArgs);
                case "codemod_write" -> codemodWrite(safeArgs);
                case "run_command" -> runCommand(safeArgs);
                case "run_tests" -> runTests(safeArgs);
                default -> ToolResult.fail(name, "Unknown tool: " + toolName)
                        .with("availableTools", availableToolNames());
            };
        } catch (Exception e) {
            return ToolResult.fail(name, "Tool crashed: " + e.getClass().getSimpleName() + ": " + e.getMessage())
                    .with("exceptionClass", e.getClass().getName());
        }
    }

    public List<String> availableToolNames() {
        return List.of(
                "health",
                "index_status",
                "list_files",
                "file_stat",
                "read_file",
                "search_code",
                "slice",
                "symbols",
                "resolve_symbol",
                "apply",
                "format_file",
                "organize_imports",
                "add_import",
                "add_function",
                "replace_function_body",
                "anchor_insert_before",
                "anchor_insert_after",
                "codemod_write",
                "run_command",
                "run_tests");
    }

    /**
     * Compact manifest for the LLM prompt.
     */
    public String toolManifestForPrompt() {
        return """
                Available tools:
                1. list_files {dir?, recursive?, maxResults?}
                   Lists files under repo root. Read-only.

                2. read_file {file, maxBytes?}
                   Reads a repo-relative text file. Read-only.

                3. search_code {query, dir?, regex?, caseSensitive?, maxResults?}
                   Searches code text. Read-only.

                4. slice {file?, lang?, targetSignature?, startLine?, startCol?, endLine?, endCol?}
                   Uses CodeWeaver /slice when available. Can return full file, range, or symbol body.

                5. symbols {query?, lang?, file?}
                   Uses CodeWeaver symbol index when available.

                6. resolve_symbol {display, lang}
                   Resolves a symbol display to candidate file.

                7. apply {file, operation, language?, ...}
                   Uses CodeWeaver /apply. Requires writes enabled.

                8. format_file {file}
                   Uses CodeWeaver FORMAT_FILE. Requires writes enabled.

                9. organize_imports {file}
                   Uses CodeWeaver ORGANIZE_IMPORTS. Requires writes enabled.

                10. add_import {file, importStmt, language?}
                    Uses CodeWeaver ADD_IMPORT. Requires writes enabled.

                11. add_function {file, targetSignature?, functionCode, language?}
                    Uses CodeWeaver ADD_FUNCTION. Requires writes enabled.

                12. replace_function_body {file, targetSignature, newBody, language?}
                    Uses CodeWeaver REPLACE_FUNCTION_BODY. Requires writes enabled.

                13. anchor_insert_before / anchor_insert_after {file, anchor, insertText, language?}
                    Uses CodeWeaver anchor insert operations. Requires writes enabled.

                14. codemod_write {file, updatedContent}
                    Raw overwrite through /codemod. Disabled unless explicitly allowed.

                15. run_tests {command?}
                    Runs a configured safe test command. Disabled unless command execution is enabled.
                """;
    }

    public boolean isCodeWeaverConfigured() {
        return config.codeWeaverBaseUrl != null && !config.codeWeaverBaseUrl.isBlank();
    }

    public boolean isRepoConfigured() {
        return config.repoRoot != null;
    }

    /*
     * =============================================================================
     * =============
     * Read-only tools
     * =============================================================================
     * =============
     */

    public ToolResult health() {
        if (!isCodeWeaverConfigured()) {
            return ToolResult.fail("health", "CodeWeaver base URL is not configured.");
        }

        return httpGet("/health", "health");
    }

    public ToolResult indexStatus() {
        if (!isCodeWeaverConfigured()) {
            return ToolResult.fail("index_status", "CodeWeaver base URL is not configured.");
        }

        return httpGet("/index/status", "index_status");
    }

    /**
     * Lists files within a directory, providing necessary context for navigation.
     * 
     * Deep Insight:
     * We cap the depth and number of files returned to prevent "context window exhaustion"
     * attacks or accidental loops where the AI reads thousands of compiled artifacts.
     * Always ignores standard build directories like 'node_modules' or 'build/'.
     */
    public ToolResult listFiles(Map<String, Object> args) {
        String dir = stringArg(args, "dir", ".");
        boolean recursive = boolArg(args, "recursive", false);
        int maxResults = intArg(args, "maxResults", DEFAULT_MAX_LIST_RESULTS, 1, 5000);

        if (isRepoConfigured()) {
            return listFilesLocal(dir, recursive, maxResults);
        }

        if (isCodeWeaverConfigured()) {
            String endpoint = "/files/list?dir=" + urlEncode(dir);
            ToolResult first = httpGet(endpoint, "list_files");
            if (first.success) {
                return first;
            }

            return httpGet("/list?dir=" + urlEncode(dir), "list_files");
        }

        return ToolResult.fail("list_files", "No repoRoot or CodeWeaver base URL configured.");
    }

    public static MiniAgentTools fromEnvironment() {
        return new MiniAgentTools(Config.fromEnvironment());
    }

    public ToolResult fileStat(Map<String, Object> args) {
        String file = requiredString(args, "file");

        if (isRepoConfigured()) {
            Path path = safeResolve(file);
            try {
                boolean exists = Files.exists(path);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("file", normalizeRelPath(file));
                data.put("exists", exists);
                data.put("isDirectory", exists && Files.isDirectory(path));
                data.put("sizeBytes", exists && !Files.isDirectory(path) ? Files.size(path) : 0L);
                data.put("lastModified", exists ? Files.getLastModifiedTime(path).toInstant().toString() : null);
                return ToolResult.ok("file_stat", "File stat collected.", data);
            } catch (IOException e) {
                return ToolResult.fail("file_stat", "Failed to stat file: " + e.getMessage());
            }
        }

        if (isCodeWeaverConfigured()) {
            return httpGet("/files/stat?file=" + urlEncode(file), "file_stat");
        }

        return ToolResult.fail("file_stat", "No repoRoot or CodeWeaver base URL configured.");
    }

    /**
     * Reads a file's contents into the AI's context.
     * 
     * Deep Insight:
     * The read operation enforces strict byte limits. Reading massive minified JS files
     * or binary blobs would crash the token limit instantly. This function safely truncates
     * while alerting the Agent so it knows it has an incomplete view of the file.
     */
    public ToolResult readFile(Map<String, Object> args) {
        String file = requiredString(args, "file");
        int maxBytes = intArg(args, "maxBytes", config.maxReadBytes, 100, Math.max(100, config.maxReadBytes));

        if (isRepoConfigured()) {
            ToolResult local = readFileLocal(file, maxBytes);
            if (local.success || !isCodeWeaverConfigured()) {
                return local;
            }
        }

        if (isCodeWeaverConfigured()) {
            ToolResult remote = httpGet("/files/read?file=" + urlEncode(file), "read_file");
            if (remote.success) {
                return remote;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("file", file);
            return httpPost("/slice", payload, "read_file");
        }

        return ToolResult.fail("read_file", "No repoRoot or CodeWeaver base URL configured.");
    }

    public ToolResult searchCode(Map<String, Object> args) {
        String query = requiredString(args, "query");
        String dir = stringArg(args, "dir", ".");
        boolean regex = boolArg(args, "regex", false);
        boolean caseSensitive = boolArg(args, "caseSensitive", false);
        int maxResults = intArg(args, "maxResults", DEFAULT_MAX_SEARCH_RESULTS, 1, 1000);

        if (!isRepoConfigured()) {
            return ToolResult.fail("search_code", "search_code currently requires local repoRoot.");
        }

        Path root = safeResolve(dir);
        if (!Files.exists(root)) {
            return ToolResult.fail("search_code", "Search dir does not exist: " + dir);
        }

        Pattern pattern = regex
                ? Pattern.compile(query, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE)
                : Pattern.compile(Pattern.quote(query), caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);

        List<Map<String, Object>> hits = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(this::isLikelyTextFile)
                    .filter(this::notIgnoredPath)
                    .sorted()
                    .toList();

            for (Path file : files) {
                if (hits.size() >= maxResults) {
                    break;
                }

                if (Files.size(file) > config.maxSearchBytesPerFile) {
                    continue;
                }

                String content = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\r",
                        "\n");
                String[] lines = content.split("\n", -1);

                for (int i = 0; i < lines.length; i++) {
                    if (hits.size() >= maxResults) {
                        break;
                    }

                    if (pattern.matcher(lines[i]).find()) {
                        Map<String, Object> hit = new LinkedHashMap<>();
                        hit.put("file", rel(file));
                        hit.put("line", i + 1);
                        hit.put("preview", trim(lines[i].trim(), 500));
                        hits.add(hit);
                    }
                }
            }

            return ToolResult.ok("search_code", "Search completed.", Map.of(
                    "query", query,
                    "count", hits.size(),
                    "hits", hits));
        } catch (Exception e) {
            return ToolResult.fail("search_code", "Search failed: " + e.getMessage());
        }
    }

    public ToolResult slice(Map<String, Object> args) {
        if (isCodeWeaverConfigured()) {
            Map<String, Object> payload = new LinkedHashMap<>(args);
            normalizeLanguageInPayload(payload);
            return httpPost("/slice", payload, "slice");
        }

        String file = requiredString(args, "file");
        int startLine = intArg(args, "startLine", 1, 1, Integer.MAX_VALUE);
        int endLine = intArg(args, "endLine", startLine, startLine, Integer.MAX_VALUE);

        ToolResult read = readFile(Map.of("file", file));
        if (!read.success) {
            return read.withTool("slice");
        }

        Object contentObj = read.data.get("content");
        String content = contentObj == null ? "" : String.valueOf(contentObj);
        String[] lines = content.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);

        int from = Math.max(1, startLine);
        int to = Math.min(lines.length, endLine);

        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append(lines[i - 1]).append("\n");
        }

        return ToolResult.ok("slice", "Local line slice returned.", Map.of(
                "file", file,
                "startLine", from,
                "endLine", to,
                "slice", sb.toString()));
    }

    public ToolResult symbols(Map<String, Object> args) {
        if (!isCodeWeaverConfigured()) {
            return ToolResult.fail("symbols", "CodeWeaver base URL is not configured.");
        }

        String query = stringArg(args, "query", "");
        String lang = stringArg(args, "lang", "");
        String file = stringArg(args, "file", "");

        StringBuilder endpoint = new StringBuilder("/symbols?");
        boolean any = false;

        if (!query.isBlank()) {
            endpoint.append("query=").append(urlEncode(query));
            any = true;
        }

        if (!lang.isBlank()) {
            if (any)
                endpoint.append("&");
            endpoint.append("lang=").append(urlEncode(lang));
            any = true;
        }

        if (!file.isBlank()) {
            if (any)
                endpoint.append("&");
            endpoint.append("file=").append(urlEncode(file));
        }

        return httpGet(endpoint.toString(), "symbols");
    }

    public ToolResult resolveSymbol(Map<String, Object> args) {
        if (!isCodeWeaverConfigured()) {
            return ToolResult.fail("resolve_symbol", "CodeWeaver base URL is not configured.");
        }

        String display = requiredString(args, "display");
        String lang = requiredString(args, "lang");

        return httpGet(
                "/symbols/resolve?display=" + urlEncode(display) + "&lang=" + urlEncode(lang),
                "resolve_symbol");
    }

    /*
     * =============================================================================
     * =============
     * CodeWeaver write/edit tools
     * =============================================================================
     * =============
     */

    public ToolResult apply(Map<String, Object> args) {
        requireWrites("apply");

        if (!isCodeWeaverConfigured()) {
            return ToolResult.fail("apply", "CodeWeaver base URL is required for /apply edits.");
        }

        Map<String, Object> payload = new LinkedHashMap<>(args);
        String operation = requiredString(payload, "operation");
        payload.put("operation", normalizeOperation(operation));

        if (payload.containsKey("file")) {
            normalizeLanguageInPayload(payload);
        }

        return httpPost("/apply", payload, "apply");
    }

    public ToolResult applySimpleOperation(String operation, Map<String, Object> args) {
        Map<String, Object> payload = new LinkedHashMap<>(args);
        payload.put("operation", operation);
        requiredString(payload, "file");
        return apply(payload).withTool(operation.toLowerCase(Locale.ROOT));
    }

    public ToolResult addImport(Map<String, Object> args) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("file", requiredString(args, "file"));
        payload.put("operation", "ADD_IMPORT");
        payload.put("importStmt", requiredString(args, "importStmt"));
        putIfPresent(payload, args, "language");
        normalizeLanguageInPayload(payload);
        return apply(payload).withTool("add_import");
    }

    public ToolResult addFunction(Map<String, Object> args) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("file", requiredString(args, "file"));
        payload.put("operation", "ADD_FUNCTION");
        payload.put("functionCode", requiredString(args, "functionCode"));
        payload.put("insertText", payload.get("functionCode"));

        putIfPresent(payload, args, "targetSignature");
        putIfPresent(payload, args, "className");
        putIfPresent(payload, args, "language");

        normalizeLanguageInPayload(payload);
        return apply(payload).withTool("add_function");
    }

    public ToolResult replaceFunctionBody(Map<String, Object> args) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("file", requiredString(args, "file"));
        payload.put("operation", "REPLACE_FUNCTION_BODY");
        payload.put("targetSignature", requiredString(args, "targetSignature"));
        payload.put("newBody", requiredString(args, "newBody"));
        putIfPresent(payload, args, "language");

        normalizeLanguageInPayload(payload);
        return apply(payload).withTool("replace_function_body");
    }

    public ToolResult anchorInsert(String operation, Map<String, Object> args) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("file", requiredString(args, "file"));
        payload.put("operation", operation);
        payload.put("anchor", requiredString(args, "anchor"));
        payload.put("insertText", requiredString(args, "insertText"));
        putIfPresent(payload, args, "language");

        normalizeLanguageInPayload(payload);
        return apply(payload).withTool(operation.toLowerCase(Locale.ROOT));
    }

    public ToolResult codemodWrite(Map<String, Object> args) {
        requireWrites("codemod_write");

        if (!config.allowRawCodemod) {
            return ToolResult.fail("codemod_write", "Raw /codemod writes are disabled. Use /apply instead.");
        }

        if (!isCodeWeaverConfigured()) {
            return ToolResult.fail("codemod_write", "CodeWeaver base URL is required for /codemod.");
        }

        String file = requiredString(args, "file");
        String updatedContent = requiredString(args, "updatedContent");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("file", file);
        payload.put("updatedContent", updatedContent);

        return httpPost("/codemod", payload, "codemod_write");
    }

    /*
     * =============================================================================
     * =============
     * Optional command tools
     * =============================================================================
     * =============
     */

    public ToolResult runTests(Map<String, Object> args) {
        String command = stringArg(args, "command", "");
        if (command.isBlank()) {
            command = detectTestCommand();
        }

        if (command.isBlank()) {
            return ToolResult.fail("run_tests", "Could not detect test command. Provide command explicitly.");
        }

        return runCommand(Map.of(
                "command", command,
                "timeoutSeconds", intArg(args, "timeoutSeconds", DEFAULT_COMMAND_TIMEOUT_SECONDS, 5, 600)))
                .withTool("run_tests");
    }

    /**
     * Executes arbitrary bash commands via CodeWeaver or local processes.
     * 
     * Deep Insight:
     * Extremely high-risk. This is locked behind 'allowCommandExecution' and 'allowedCommandPrefixes'.
     * The output is truncated to prevent runaway stdout from blowing up the context window.
     * Use with extreme caution.
     */
    public ToolResult runCommand(Map<String, Object> args) {
        if (!config.allowCommandExecution) {
            return ToolResult.fail("run_command", "Command execution is disabled.");
        }

        if (!isRepoConfigured()) {
            return ToolResult.fail("run_command", "repoRoot is required for command execution.");
        }

        String command = requiredString(args, "command");
        int timeoutSeconds = intArg(args, "timeoutSeconds", DEFAULT_COMMAND_TIMEOUT_SECONDS, 1, 900);

        List<String> argv = splitSafeCommand(command);
        if (argv.isEmpty()) {
            return ToolResult.fail("run_command", "Empty command.");
        }

        if (!isCommandAllowed(argv)) {
            return ToolResult.fail("run_command", "Command is not allowed by safety policy: " + command)
                    .with("allowedPrefixes", config.allowedCommandPrefixes);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(config.repoRoot.toFile());
            pb.redirectErrorStream(true);

            long started = System.currentTimeMillis();
            Process process = pb.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.fail("run_command", "Command timed out after " + timeoutSeconds + " seconds.")
                        .with("command", command);
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            output = trim(output, config.maxCommandOutputChars);

            int exitCode = process.exitValue();

            return new ToolResult(
                    exitCode == 0,
                    "run_command",
                    exitCode == 0 ? "Command completed." : "Command failed with exit code " + exitCode,
                    mapOf(
                            "command", command,
                            "argv", argv,
                            "exitCode", exitCode,
                            "durationMs", System.currentTimeMillis() - started,
                            "output", output));
        } catch (Exception e) {
            return ToolResult.fail("run_command", "Command execution failed: " + e.getMessage())
                    .with("command", command);
        }
    }

    /*
     * =============================================================================
     * =============
     * Local helpers
     * =============================================================================
     * =============
     */

    private ToolResult listFilesLocal(String dir, boolean recursive, int maxResults) {
        Path start = safeResolve(dir);
        if (!Files.exists(start)) {
            return ToolResult.fail("list_files", "Directory does not exist: " + dir);
        }

        if (!Files.isDirectory(start)) {
            return ToolResult.fail("list_files", "Path is not a directory: " + dir);
        }

        List<Map<String, Object>> entries = new ArrayList<>();

        try {
            if (recursive) {
                try (Stream<Path> stream = Files.walk(start, 30)) {
                    List<Path> paths = stream
                            .filter(this::notIgnoredPath)
                            .sorted()
                            .limit(maxResults)
                            .toList();

                    for (Path path : paths) {
                        if (path.equals(start)) {
                            continue;
                        }
                        entries.add(fileEntry(path));
                    }
                }
            } else {
                try (Stream<Path> stream = Files.list(start)) {
                    List<Path> paths = stream
                            .filter(this::notIgnoredPath)
                            .sorted()
                            .limit(maxResults)
                            .toList();

                    for (Path path : paths) {
                        entries.add(fileEntry(path));
                    }
                }
            }

            return ToolResult.ok("list_files", "Files listed.", Map.of(
                    "dir", normalizeRelPath(dir),
                    "recursive", recursive,
                    "count", entries.size(),
                    "entries", entries));
        } catch (Exception e) {
            return ToolResult.fail("list_files", "List failed: " + e.getMessage());
        }
    }

    private ToolResult readFileLocal(String file, int maxBytes) {
        Path path = safeResolve(file);

        try {
            if (!Files.exists(path)) {
                return ToolResult.fail("read_file", "File does not exist: " + file);
            }

            if (Files.isDirectory(path)) {
                return ToolResult.fail("read_file", "Path is a directory, not a file: " + file);
            }

            long size = Files.size(path);
            if (size > maxBytes) {
                return ToolResult.fail("read_file", "File too large for read_file: " + size + " bytes.")
                        .with("file", normalizeRelPath(file))
                        .with("sizeBytes", size)
                        .with("maxBytes", maxBytes);
            }

            if (!isLikelyTextFile(path)) {
                return ToolResult.fail("read_file", "Refusing to read non-text file: " + file);
            }

            String content = Files.readString(path, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace("\r", "\n");

            return ToolResult.ok("read_file", "File read.", Map.of(
                    "file", normalizeRelPath(file),
                    "content", content,
                    "sizeBytes", size));
        } catch (Exception e) {
            return ToolResult.fail("read_file", "Read failed: " + e.getMessage());
        }
    }

    private Path safeResolve(String userPath) {
        if (config.repoRoot == null) {
            throw new IllegalStateException("repoRoot is not configured.");
        }

        if (userPath == null || userPath.isBlank()) {
            userPath = ".";
        }

        String cleaned = userPath.replace('\\', '/');

        if (cleaned.contains("\0")) {
            throw new IllegalArgumentException("Invalid path contains NUL byte.");
        }

        try {
            Path raw = Path.of(cleaned);
            if (raw.isAbsolute()) {
                throw new IllegalArgumentException("Absolute paths are not allowed: " + userPath);
            }

            Path resolved = config.repoRoot.resolve(raw).normalize();
            if (!resolved.startsWith(config.repoRoot)) {
                throw new IllegalArgumentException("Path escapes repo root: " + userPath);
            }

            return resolved;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid path: " + userPath);
        }
    }

    private String rel(Path path) {
        return config.repoRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private Map<String, Object> fileEntry(Path path) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("path", rel(path));
        entry.put("directory", Files.isDirectory(path));

        try {
            entry.put("sizeBytes", Files.isDirectory(path) ? 0L : Files.size(path));
        } catch (IOException ignored) {
            entry.put("sizeBytes", 0L);
        }

        return entry;
    }

    private boolean notIgnoredPath(Path path) {
        Path relative = config.repoRoot == null ? path : config.repoRoot.relativize(path.toAbsolutePath().normalize());

        for (Path part : relative) {
            String name = part.toString();
            if (config.ignoredDirs.contains(name)) {
                return false;
            }
        }

        return true;
    }

    private boolean isLikelyTextFile(Path path) {
        if (Files.isDirectory(path)) {
            return false;
        }

        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);

        if (name.equals("dockerfile") || name.equals("makefile") || name.equals(".gitignore")) {
            return true;
        }

        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }

        return TEXT_EXTENSIONS.contains(name.substring(dot));
    }

    /*
     * =============================================================================
     * =============
     * HTTP helpers
     * =============================================================================
     * =============
     */

    private ToolResult httpGet(String endpoint, String toolName) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri(endpoint))
                    .timeout(Duration.ofSeconds(config.httpRequestTimeoutSeconds))
                    .GET();

            addAuth(builder);

            HttpResponse<String> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            return parseHttpResponse(toolName, endpoint, response);
        } catch (Exception e) {
            return ToolResult.fail(toolName, "HTTP GET failed: " + e.getMessage())
                    .with("endpoint", endpoint);
        }
    }

    private ToolResult httpPost(String endpoint, Map<String, Object> payload, String toolName) {
        try {
            String json = mapper.writeValueAsString(payload == null ? Map.of() : payload);

            if (json.getBytes(StandardCharsets.UTF_8).length > config.maxJsonPayloadBytes) {
                return ToolResult.fail(toolName, "JSON payload too large.")
                        .with("endpoint", endpoint)
                        .with("maxJsonPayloadBytes", config.maxJsonPayloadBytes);
            }

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri(endpoint))
                    .timeout(Duration.ofSeconds(config.httpRequestTimeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));

            addAuth(builder);

            HttpResponse<String> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            return parseHttpResponse(toolName, endpoint, response);
        } catch (Exception e) {
            return ToolResult.fail(toolName, "HTTP POST failed: " + e.getMessage())
                    .with("endpoint", endpoint);
        }
    }

    private ToolResult parseHttpResponse(String toolName, String endpoint, HttpResponse<String> response) {
        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        String requestId = response.headers().firstValue("X-Request-Id").orElse("");

        Map<String, Object> parsed = parseJsonMap(body).orElseGet(() -> mapOf("body", body));

        if (status >= 200 && status < 300) {
            return ToolResult.ok(toolName, "HTTP " + status, parsed)
                    .with("endpoint", endpoint)
                    .with("status", status)
                    .with("requestId", requestId);
        }

        return ToolResult.fail(toolName, "HTTP " + status + " from CodeWeaver.")
                .with("endpoint", endpoint)
                .with("status", status)
                .with("requestId", requestId)
                .with("response", parsed);
    }

    private Optional<Map<String, Object>> parseJsonMap(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }

        try {
            Map<String, Object> map = mapper.readValue(body, new TypeReference<Map<String, Object>>() {
            });
            return Optional.of(map);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private URI uri(String endpoint) {
        String base = config.codeWeaverBaseUrl;
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("CodeWeaver base URL not configured.");
        }

        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String normalizedEndpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;

        return URI.create(normalizedBase + normalizedEndpoint);
    }

    private void addAuth(HttpRequest.Builder builder) {
        if (config.codeWeaverToken != null && !config.codeWeaverToken.isBlank()) {
            builder.header("Authorization", "Bearer " + config.codeWeaverToken);
        }
    }

    /*
     * =============================================================================
     * =============
     * Safety + misc helpers
     * =============================================================================
     * =============
     */

    private void requireWrites(String toolName) {
        if (!config.allowWrites) {
            throw new SecurityException(toolName + " requires allowWrites=true.");
        }
    }

    private String detectTestCommand() {
        if (!isRepoConfigured()) {
            return "";
        }

        if (Files.exists(config.repoRoot.resolve("mvnw"))) {
            return "./mvnw test";
        }

        if (Files.exists(config.repoRoot.resolve("pom.xml"))) {
            return "mvn test";
        }

        if (Files.exists(config.repoRoot.resolve("gradlew"))) {
            return "./gradlew test";
        }

        if (Files.exists(config.repoRoot.resolve("build.gradle")) ||
                Files.exists(config.repoRoot.resolve("build.gradle.kts"))) {
            return "gradle test";
        }

        return "";
    }

    private List<String> splitSafeCommand(String command) {
        if (command == null || command.isBlank()) {
            return List.of();
        }

        String cleaned = command.trim();

        if (containsShellMetachar(cleaned)) {
            return List.of();
        }

        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);

            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }

            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }

            if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }

            current.append(c);
        }

        if (current.length() > 0) {
            out.add(current.toString());
        }

        return out;
    }

    private boolean containsShellMetachar(String command) {
        return command.contains(";") ||
                command.contains("&&") ||
                command.contains("||") ||
                command.contains("|") ||
                command.contains(">") ||
                command.contains("<") ||
                command.contains("`") ||
                command.contains("$(");
    }

    private boolean isCommandAllowed(List<String> argv) {
        if (argv == null || argv.isEmpty()) {
            return false;
        }

        String joined = String.join(" ", argv);

        for (String prefix : config.allowedCommandPrefixes) {
            if (joined.equals(prefix) || joined.startsWith(prefix + " ")) {
                return true;
            }
        }

        return false;
    }

    private String normalizeOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation is required.");
        }

        return operation.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
    }

    private void normalizeLanguageInPayload(Map<String, Object> payload) {
        if (payload == null) {
            return;
        }

        if (payload.containsKey("language") || payload.containsKey("lang")) {
            return;
        }

        Object file = payload.get("file");
        if (file instanceof String f && !f.isBlank()) {
            payload.put("language", inferLanguageFromPath(f));
        }
    }

    private String inferLanguageFromPath(String file) {
        if (file == null) {
            return "text";
        }

        String lower = file.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".java"))
            return "java";
        if (lower.endsWith(".kt") || lower.endsWith(".kts"))
            return "kotlin";
        if (lower.endsWith(".cs"))
            return "csharp";
        if (lower.endsWith(".py"))
            return "python";
        if (lower.endsWith(".js") || lower.endsWith(".jsx"))
            return "javascript";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx"))
            return "typescript";
        if (lower.endsWith(".sh") || lower.endsWith(".bash"))
            return "shell";
        if (lower.endsWith(".bat") || lower.endsWith(".cmd"))
            return "bat";
        if (lower.endsWith(".c"))
            return "c";
        if (lower.endsWith(".cpp") || lower.endsWith(".cc") || lower.endsWith(".cxx") || lower.endsWith(".hpp")
                || lower.endsWith(".h"))
            return "cpp";
        if (lower.endsWith(".html") || lower.endsWith(".htm"))
            return "html";
        if (lower.endsWith(".css"))
            return "css";
        if (lower.endsWith(".xml"))
            return "xml";
        if (lower.endsWith(".json"))
            return "json";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml"))
            return "yaml";

        return "text";
    }

    private static String safeToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "";
        }

        return toolName.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toLowerCase(Locale.ROOT);
    }

    private String requiredString(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(key + " is required.");
        }

        return String.valueOf(value).trim();
    }

    private String stringArg(Map<String, Object> args, String key, String fallback) {
        Object value = args == null ? null : args.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }

        return String.valueOf(value).trim();
    }

    private boolean boolArg(Map<String, Object> args, String key, boolean fallback) {
        Object value = args == null ? null : args.get(key);
        if (value == null) {
            return fallback;
        }

        if (value instanceof Boolean b) {
            return b;
        }

        return Boolean.parseBoolean(String.valueOf(value));
    }

    private int intArg(Map<String, Object> args, String key, int fallback, int min, int max) {
        Object value = args == null ? null : args.get(key);
        int parsed = fallback;

        if (value instanceof Number n) {
            parsed = n.intValue();
        } else if (value != null && !String.valueOf(value).isBlank()) {
            try {
                parsed = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                parsed = fallback;
            }
        }

        return Math.max(min, Math.min(max, parsed));
    }

    private void putIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        if (source == null || target == null || key == null) {
            return;
        }

        Object value = source.get(key);
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, value);
        }
    }

    private String normalizeRelPath(String path) {
        if (path == null || path.isBlank()) {
            return ".";
        }

        return path.replace('\\', '/');
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String trim(String value, int maxChars) {
        if (value == null) {
            return "";
        }

        int safeMax = Math.max(100, maxChars);
        if (value.length() <= safeMax) {
            return value;
        }

        return value.substring(0, safeMax) + "\n...[TRUNCATED]";
    }

    private Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (pairs == null) {
            return map;
        }

        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object key = pairs[i];
            Object value = pairs[i + 1];

            if (key != null) {
                map.put(String.valueOf(key), value);
            }
        }

        return map;
    }

    @Override
    public void close() {
        // Java HttpClient has no explicit close in Java 17.
    }

    /*
     * =============================================================================
     * =============
     * Config
     * =============================================================================
     * =============
     */

    public static final class Config {

        private final Path repoRoot;
        private final String codeWeaverBaseUrl;
        private final String codeWeaverToken;
        private final boolean allowWrites;
        private final boolean allowRawCodemod;
        private final boolean allowCommandExecution;
        private final int maxReadBytes;
        private final int maxSearchBytesPerFile;
        private final int maxJsonPayloadBytes;
        private final int maxCommandOutputChars;
        private final int httpConnectTimeoutSeconds;
        private final int httpRequestTimeoutSeconds;
        private final Set<String> ignoredDirs;
        private final List<String> allowedCommandPrefixes;
        private final ObjectMapper objectMapper;

        private Config(Builder builder) {
            this.repoRoot = builder.repoRoot == null ? null : builder.repoRoot.toAbsolutePath().normalize();
            this.codeWeaverBaseUrl = cleanUrl(builder.codeWeaverBaseUrl);
            this.codeWeaverToken = cleanBlank(builder.codeWeaverToken);
            this.allowWrites = builder.allowWrites;
            this.allowRawCodemod = builder.allowRawCodemod;
            this.allowCommandExecution = builder.allowCommandExecution;
            this.maxReadBytes = builder.maxReadBytes;
            this.maxSearchBytesPerFile = builder.maxSearchBytesPerFile;
            this.maxJsonPayloadBytes = builder.maxJsonPayloadBytes;
            this.maxCommandOutputChars = builder.maxCommandOutputChars;
            this.httpConnectTimeoutSeconds = builder.httpConnectTimeoutSeconds;
            this.httpRequestTimeoutSeconds = builder.httpRequestTimeoutSeconds;
            this.ignoredDirs = Set.copyOf(builder.ignoredDirs);
            this.allowedCommandPrefixes = List.copyOf(builder.allowedCommandPrefixes);
            this.objectMapper = builder.objectMapper;
        }

        public static Builder builder() {
            return new Builder();
        }

        /**
         * Railway/GitHub friendly config.
         *
         * Environment variables supported:
         *
         * MINIAGENT_REPO_ROOT=/app
         * CODEWEAVER_REPO=/workspace/repo
         * RAILWAY_WORKSPACE_DIR=/workspace/repo
         *
         * CODEWEAVER_BASE_URL=http://codeweaver.railway.internal:8080
         * CODEWEAVER_INTERNAL_URL=http://codeweaver.railway.internal:8080
         * CODEWEAVER_TOKEN=...
         *
         * MINIAGENT_TOOLS_ALLOW_WRITES=false
         * MINIAGENT_TOOLS_ALLOW_RAW_CODEMOD=false
         * MINIAGENT_TOOLS_ALLOW_COMMANDS=false
         *
         * MINIAGENT_MAX_READ_BYTES=1500000
         * MINIAGENT_MAX_SEARCH_BYTES_PER_FILE=750000
         * MINIAGENT_HTTP_TIMEOUT_SECONDS=120
         */
        public static Config fromEnvironment() {
            return fromEnvironment(System.getenv());
        }

        public static Config fromEnvironment(Map<String, String> env) {
            Map<String, String> safeEnv = env == null ? Map.of() : env;

            Builder builder = builder();

            Path repo = firstExistingDirectory(
                    safeEnv.get("MINIAGENT_REPO_ROOT"),
                    safeEnv.get("CODEWEAVER_REPO"),
                    safeEnv.get("RAILWAY_WORKSPACE_DIR"),
                    safeEnv.get("WORKSPACE_DIR"),
                    safeEnv.get("PWD"));

            if (repo != null) {
                builder.repoRoot(repo);
            }

            String codeWeaverUrl = firstNonBlank(
                    safeEnv.get("CODEWEAVER_BASE_URL"),
                    safeEnv.get("CODEWEAVER_INTERNAL_URL"),
                    buildRailwayInternalCodeWeaverUrl(safeEnv));

            if (codeWeaverUrl != null && !codeWeaverUrl.isBlank()) {
                builder.codeWeaverBaseUrl(codeWeaverUrl);
            }

            builder.codeWeaverToken(safeEnv.get("CODEWEAVER_TOKEN"));

            builder.allowWrites(boolEnv(safeEnv, "MINIAGENT_TOOLS_ALLOW_WRITES", false));
            builder.allowRawCodemod(boolEnv(safeEnv, "MINIAGENT_TOOLS_ALLOW_RAW_CODEMOD", false));
            builder.allowCommandExecution(boolEnv(safeEnv, "MINIAGENT_TOOLS_ALLOW_COMMANDS", false));

            builder.maxReadBytes(intEnv(
                    safeEnv,
                    "MINIAGENT_MAX_READ_BYTES",
                    DEFAULT_MAX_READ_BYTES,
                    10_000,
                    20_000_000));

            builder.maxSearchBytesPerFile(intEnv(
                    safeEnv,
                    "MINIAGENT_MAX_SEARCH_BYTES_PER_FILE",
                    DEFAULT_MAX_SEARCH_BYTES_PER_FILE,
                    10_000,
                    10_000_000));

            builder.maxJsonPayloadBytes(intEnv(
                    safeEnv,
                    "MINIAGENT_MAX_JSON_PAYLOAD_BYTES",
                    2_000_000,
                    100_000,
                    20_000_000));

            builder.maxCommandOutputChars(intEnv(
                    safeEnv,
                    "MINIAGENT_MAX_COMMAND_OUTPUT_CHARS",
                    60_000,
                    1_000,
                    500_000));

            builder.httpConnectTimeoutSeconds(intEnv(
                    safeEnv,
                    "MINIAGENT_HTTP_CONNECT_TIMEOUT_SECONDS",
                    10,
                    1,
                    60));

            builder.httpRequestTimeoutSeconds(intEnv(
                    safeEnv,
                    "MINIAGENT_HTTP_TIMEOUT_SECONDS",
                    120,
                    5,
                    900));

            String allowedCommands = safeEnv.get("MINIAGENT_ALLOWED_COMMAND_PREFIXES");
            if (allowedCommands != null && !allowedCommands.isBlank()) {
                List<String> parsed = Stream.of(allowedCommands.split("\\|"))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .toList();

                if (!parsed.isEmpty()) {
                    builder.allowedCommandPrefixes(parsed);
                }
            }

            return builder.build();
        }

        private Config normalized() {
            if (repoRoot != null && (!Files.exists(repoRoot) || !Files.isDirectory(repoRoot))) {
                throw new IllegalArgumentException("repoRoot must exist and be a directory: " + repoRoot);
            }

            return this;
        }

        public Path repoRoot() {
            return repoRoot;
        }

        public String codeWeaverBaseUrl() {
            return codeWeaverBaseUrl;
        }

        public String codeWeaverToken() {
            return codeWeaverToken;
        }

        public boolean allowWrites() {
            return allowWrites;
        }

        public boolean allowRawCodemod() {
            return allowRawCodemod;
        }

        public boolean allowCommandExecution() {
            return allowCommandExecution;
        }

        public int maxReadBytes() {
            return maxReadBytes;
        }

        public int maxSearchBytesPerFile() {
            return maxSearchBytesPerFile;
        }

        public int maxJsonPayloadBytes() {
            return maxJsonPayloadBytes;
        }

        public int maxCommandOutputChars() {
            return maxCommandOutputChars;
        }

        public int httpConnectTimeoutSeconds() {
            return httpConnectTimeoutSeconds;
        }

        public int httpRequestTimeoutSeconds() {
            return httpRequestTimeoutSeconds;
        }

        public Set<String> ignoredDirs() {
            return ignoredDirs;
        }

        public List<String> allowedCommandPrefixes() {
            return allowedCommandPrefixes;
        }

        public ObjectMapper objectMapper() {
            return objectMapper;
        }

        private static String buildRailwayInternalCodeWeaverUrl(Map<String, String> env) {
            String serviceName = firstNonBlank(
                    env.get("CODEWEAVER_SERVICE_NAME"),
                    env.get("MINIAGENT_CODEWEAVER_SERVICE_NAME"),
                    "codeweaver");

            String port = firstNonBlank(
                    env.get("CODEWEAVER_PORT"),
                    "8080");

            if (serviceName == null || serviceName.isBlank()) {
                return null;
            }

            return "http://" + serviceName.trim() + ".railway.internal:" + port.trim();
        }

        private static Path firstExistingDirectory(String... candidates) {
            if (candidates == null) {
                return null;
            }

            for (String candidate : candidates) {
                if (candidate == null || candidate.isBlank()) {
                    continue;
                }

                try {
                    Path path = Path.of(candidate.trim()).toAbsolutePath().normalize();
                    if (Files.exists(path) && Files.isDirectory(path)) {
                        return path;
                    }
                } catch (Exception ignored) {
                    // Try next candidate.
                }
            }

            return null;
        }

        private static boolean boolEnv(Map<String, String> env, String key, boolean fallback) {
            String value = env.get(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }

            String normalized = value.trim().toLowerCase(Locale.ROOT);

            return normalized.equals("1") ||
                    normalized.equals("true") ||
                    normalized.equals("yes") ||
                    normalized.equals("y") ||
                    normalized.equals("on");
        }

        private static int intEnv(
                Map<String, String> env,
                String key,
                int fallback,
                int min,
                int max) {
            String value = env.get(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }

            try {
                int parsed = Integer.parseInt(value.trim());
                return Math.max(min, Math.min(max, parsed));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private static String firstNonBlank(String... values) {
            if (values == null) {
                return null;
            }

            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }

            return null;
        }

        private static String cleanUrl(String value) {
            String cleaned = cleanBlank(value);
            if (cleaned == null) {
                return null;
            }

            while (cleaned.endsWith("/")) {
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            }

            return cleaned;
        }

        private static String cleanBlank(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }

            return value.trim();
        }

        public static final class Builder {

            private Path repoRoot;
            private String codeWeaverBaseUrl;
            private String codeWeaverToken;
            private boolean allowWrites;
            private boolean allowRawCodemod;
            private boolean allowCommandExecution;
            private int maxReadBytes = DEFAULT_MAX_READ_BYTES;
            private int maxSearchBytesPerFile = DEFAULT_MAX_SEARCH_BYTES_PER_FILE;
            private int maxJsonPayloadBytes = 2_000_000;
            private int maxCommandOutputChars = 60_000;
            private int httpConnectTimeoutSeconds = 10;
            private int httpRequestTimeoutSeconds = 120;
            private Set<String> ignoredDirs = new LinkedHashSet<>(DEFAULT_IGNORED_DIRS);
            private List<String> allowedCommandPrefixes = new ArrayList<>(List.of(
                    "mvn test",
                    "mvn -q test",
                    "./mvnw test",
                    "./mvnw -q test",
                    "gradle test",
                    "gradle build",
                    "./gradlew test",
                    "./gradlew build"));
            private ObjectMapper objectMapper;

            public Builder repoRoot(Path repoRoot) {
                this.repoRoot = repoRoot;
                return this;
            }

            public Builder codeWeaverBaseUrl(String codeWeaverBaseUrl) {
                this.codeWeaverBaseUrl = codeWeaverBaseUrl == null || codeWeaverBaseUrl.isBlank()
                        ? null
                        : codeWeaverBaseUrl.trim();
                return this;
            }

            public Builder codeWeaverToken(String codeWeaverToken) {
                this.codeWeaverToken = codeWeaverToken == null || codeWeaverToken.isBlank()
                        ? null
                        : codeWeaverToken.trim();
                return this;
            }

            public Builder allowWrites(boolean allowWrites) {
                this.allowWrites = allowWrites;
                return this;
            }

            public Builder allowRawCodemod(boolean allowRawCodemod) {
                this.allowRawCodemod = allowRawCodemod;
                return this;
            }

            public Builder allowCommandExecution(boolean allowCommandExecution) {
                this.allowCommandExecution = allowCommandExecution;
                return this;
            }

            public Builder maxReadBytes(int maxReadBytes) {
                this.maxReadBytes = Math.max(1000, maxReadBytes);
                return this;
            }

            public Builder maxSearchBytesPerFile(int maxSearchBytesPerFile) {
                this.maxSearchBytesPerFile = Math.max(1000, maxSearchBytesPerFile);
                return this;
            }

            public Builder maxJsonPayloadBytes(int maxJsonPayloadBytes) {
                this.maxJsonPayloadBytes = Math.max(10_000, maxJsonPayloadBytes);
                return this;
            }

            public Builder maxCommandOutputChars(int maxCommandOutputChars) {
                this.maxCommandOutputChars = Math.max(1000, maxCommandOutputChars);
                return this;
            }

            public Builder httpConnectTimeoutSeconds(int seconds) {
                this.httpConnectTimeoutSeconds = Math.max(1, seconds);
                return this;
            }

            public Builder httpRequestTimeoutSeconds(int seconds) {
                this.httpRequestTimeoutSeconds = Math.max(5, seconds);
                return this;
            }

            public Builder ignoredDirs(Set<String> ignoredDirs) {
                if (ignoredDirs != null && !ignoredDirs.isEmpty()) {
                    this.ignoredDirs = new LinkedHashSet<>(ignoredDirs);
                }
                return this;
            }

            public Builder allowedCommandPrefixes(List<String> allowedCommandPrefixes) {
                if (allowedCommandPrefixes != null && !allowedCommandPrefixes.isEmpty()) {
                    this.allowedCommandPrefixes = new ArrayList<>(allowedCommandPrefixes);
                }
                return this;
            }

            public Builder objectMapper(ObjectMapper objectMapper) {
                this.objectMapper = objectMapper;
                return this;
            }

            public Config build() {
                return new Config(this);
            }
        }
    }

    /*
     * =============================================================================
     * =============
     * Result DTO
     * =============================================================================
     * =============
     */

    public static final class ToolResult {

        private final boolean success;
        private final String tool;
        private final String message;
        private final Map<String, Object> data;

        public ToolResult(boolean success, String tool, String message, Map<String, Object> data) {
            this.success = success;
            this.tool = tool == null ? "" : tool;
            this.message = message == null ? "" : message;
            this.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
        }

        public static ToolResult ok(String tool, String message, Map<String, Object> data) {
            return new ToolResult(true, tool, message, data);
        }

        public static ToolResult fail(String tool, String message) {
            return new ToolResult(false, tool, message, Map.of());
        }

        public boolean success() {
            return success;
        }

        public String tool() {
            return tool;
        }

        public String message() {
            return message;
        }

        public Map<String, Object> data() {
            return new LinkedHashMap<>(data);
        }

        public ToolResult with(String key, Object value) {
            Map<String, Object> copy = new LinkedHashMap<>(data);
            if (key != null && !key.isBlank()) {
                copy.put(key, value);
            }
            return new ToolResult(success, tool, message, copy);
        }

        public ToolResult withTool(String newToolName) {
            return new ToolResult(success, newToolName, message, data);
        }

        public String compactText() {
            return "ToolResult{" +
                    "success=" + success +
                    ", tool='" + tool + '\'' +
                    ", message='" + message + '\'' +
                    ", data=" + data +
                    '}';
        }

        @Override
        public String toString() {
            return compactText();
        }
    }
}