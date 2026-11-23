package xyz.yychainsaw.backend.service;

import java.util.List;
import java.util.Locale;

/** File-name policy for new upload tasks. It does not inspect or certify file contents. */
public final class UploadPolicy {
    public static final long MAX_SIZE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final List<String> EXTENSIONS = List.of(
        "txt", "md", "csv", "json", "pdf", "png", "jpg", "jpeg", "gif", "webp",
        "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "rar", "7z", "tar", "gz", "mp3", "mp4"
    );
    private UploadPolicy() {}
    public static List<String> allowedExtensions() { return EXTENSIONS; }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
    public static void validateNewFileName(String normalizedName) {
        if (!EXTENSIONS.contains(extension(normalizedName)))
            throw new NodeService.Problem(400, "UNSUPPORTED_FILE_TYPE", "不支持此文件类型，请选择允许的文件格式");
    }
    /** Keeps the final allowed suffix on copies while respecting the 191-code-point limit. */
    public static String copyName(String original, int index) {
        if (index < 1) throw new IllegalArgumentException("index must be positive");
        String add = " (" + index + ")";
        String ext = extension(original);
        int dot = original.lastIndexOf('.');
        String tail = EXTENSIONS.contains(ext) ? original.substring(dot) : "";
        String base = tail.isEmpty() ? original : original.substring(0, dot);
        int max = 191 - add.codePointCount(0, add.length()) - tail.codePointCount(0, tail.length());
        if (base.codePointCount(0, base.length()) > max) base = base.substring(0, base.offsetByCodePoints(0, max));
        return base + add + tail;
    }
}

