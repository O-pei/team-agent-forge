package com.agentforge.rag.parse;

import com.agentforge.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 文档解析：txt/md 直接按 UTF-8 读取；pdf/docx/html 用 Tika 提取纯文本。
 *
 * <p>上传白名单在 {@link #validate(String, long)} 中校验（扩展名 + 大小），
 * 解析失败抛 {@link BizException}，由异步处理流程标记 FAILED。</p>
 */
@Slf4j
@Component
public class DocumentParser {

    /** 允许上传的扩展名（小写）。 */
    public static final Set<String> ALLOWED_EXTENSIONS = Set.of("txt", "md", "markdown", "pdf", "docx", "html", "htm");

    /** 单文件大小上限 20MB（multipart 上限 50MB，业务再收紧）。 */
    public static final long MAX_FILE_SIZE = 20L * 1024 * 1024;

    private static final Set<String> PLAIN_TEXT_EXTENSIONS = Set.of("txt", "md", "markdown");

    private final Tika tika = new Tika();

    /**
     * 校验文件名与大小，返回安全的文件基名（去掉路径，防目录穿越）。
     */
    public String validate(String originalFilename, long size) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw BizException.badRequest("文件名不能为空");
        }
        String basename = originalFilename.replace('\\', '/');
        int slash = basename.lastIndexOf('/');
        if (slash >= 0) {
            basename = basename.substring(slash + 1);
        }
        basename = basename.strip();
        if (basename.isEmpty() || basename.equals(".") || basename.equals("..")) {
            throw BizException.badRequest("非法文件名");
        }
        String extension = extensionOf(basename);
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension)) {
            throw BizException.badRequest("不支持的文件类型，仅允许: " + ALLOWED_EXTENSIONS);
        }
        if (size <= 0) {
            throw BizException.badRequest("文件内容为空");
        }
        if (size > MAX_FILE_SIZE) {
            throw BizException.badRequest("文件过大，最大 20MB");
        }
        return basename;
    }

    /**
     * 解析为纯文本。
     */
    public String parse(byte[] content, String filename, String contentType) {
        if (content == null || content.length == 0) {
            throw BizException.badRequest("文件内容为空");
        }
        String extension = extensionOf(filename);
        try {
            if (extension != null && PLAIN_TEXT_EXTENSIONS.contains(extension)) {
                return normalize(new String(content, StandardCharsets.UTF_8));
            }
            Metadata metadata = new Metadata();
            if (filename != null) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            }
            if (contentType != null && !contentType.isBlank()) {
                metadata.set(Metadata.CONTENT_TYPE, contentType);
            }
            String text;
            try (InputStream in = new ByteArrayInputStream(content)) {
                text = normalize(tika.parseToString(in, metadata));
            }
            if (text.isBlank()) {
                throw BizException.badRequest("文档未解析出文本内容");
            }
            return text;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("文档解析失败 filename={} error={}", filename, e.getMessage());
            throw new BizException(com.agentforge.common.api.ErrorCode.BAD_REQUEST,
                    "文档解析失败: " + e.getClass().getSimpleName());
        }
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
