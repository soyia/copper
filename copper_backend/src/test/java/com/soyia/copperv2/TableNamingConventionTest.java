package com.soyia.copperv2;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TableNamingConventionTest {
    private static final Pattern PREFIXED_TABLE = Pattern.compile(
            "(?i)\\b(?:from|join|into|update|table|references)\\s+`?c_[a-z][a-z0-9_]*\\b");
    private static final Path LEGACY_RENAME_MIGRATION = Path.of(
            "src/main/resources/db/migration_20260713_04_remove_table_prefix.sql");

    @Test
    void applicationSqlMustNotReintroduceCPrefixedTables() throws IOException {
        List<Path> sourceFiles;
        try (var paths = Files.walk(Path.of("src"))) {
            sourceFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(this::isCheckedSource)
                    .filter(path -> !path.equals(LEGACY_RENAME_MIGRATION))
                    .toList();
        }

        List<String> violations = sourceFiles.stream()
                .flatMap(path -> matchingLines(path).stream())
                .toList();

        assertTrue(violations.isEmpty(), () ->
                "禁止新增 c_ 表名；请使用明确的领域表名。发现：\n" + String.join("\n", violations));
    }

    private boolean isCheckedSource(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".sql");
    }

    private List<String> matchingLines(Path path) {
        try {
            List<String> lines = Files.readAllLines(path);
            return java.util.stream.IntStream.range(0, lines.size())
                    .filter(index -> PREFIXED_TABLE.matcher(lines.get(index)).find())
                    .mapToObj(index -> path + ":" + (index + 1) + " " + lines.get(index).trim())
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("读取源文件失败：" + path, ex);
        }
    }
}
