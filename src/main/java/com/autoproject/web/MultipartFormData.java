package com.autoproject.web;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small streaming multipart/form-data reader that keeps uploaded files off the JVM heap. */
final class MultipartFormData {
    private static final int MAX_LINE_BYTES = 16 * 1024;
    private static final int MAX_FIELD_BYTES = 128 * 1024;
    private static final int MAX_PARTS = 4_000;
    private static final Pattern PARAMETER = Pattern.compile(
            "(?:^|;)\\s*([A-Za-z0-9_-]+)=(?:\\\"((?:\\\\.|[^\\\"])*)\\\"|([^;]*))");

    private MultipartFormData() {
    }

    static Form parse(InputStream body, String contentType, long maxBytes, Path taskRoot) throws IOException {
        String boundary = boundaryFrom(contentType);
        CountingLimitedInputStream limited = new CountingLimitedInputStream(body, maxBytes);
        BufferedInputStream in = new BufferedInputStream(limited, 64 * 1024);
        String opening = readLine(in);
        if (!("--" + boundary).equals(opening)) {
            throw new BadRequestException("Invalid multipart opening boundary");
        }

        Map<String, List<String>> fields = new LinkedHashMap<>();
        Map<String, List<FilePart>> files = new LinkedHashMap<>();
        byte[] delimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);
        int fileIndex = 0;
        for (int partIndex = 0; partIndex < MAX_PARTS; partIndex++) {
            Map<String, String> headers = readHeaders(in);
            Map<String, String> disposition = parseDisposition(headers.get("content-disposition"));
            String name = disposition.get("name");
            if (name == null || name.isBlank()) {
                throw new BadRequestException("A multipart part is missing its name");
            }
            String fileName = disposition.get("filename");
            if (fileName == null) {
                ByteArrayOutputStream value = new ByteArrayOutputStream();
                boolean last = copyUntilBoundary(in, value, delimiter, MAX_FIELD_BYTES);
                fields.computeIfAbsent(name, ignored -> new ArrayList<>())
                        .add(value.toString(StandardCharsets.UTF_8));
                if (last) {
                    return new Form(fields, files, limited.count());
                }
                continue;
            }

            Path destination = destinationFor(taskRoot, name, fileName, fileIndex++);
            Files.createDirectories(destination.getParent());
            boolean last;
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(
                    destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), 64 * 1024)) {
                last = copyUntilBoundary(in, out, delimiter, Long.MAX_VALUE);
            } catch (Exception e) {
                Files.deleteIfExists(destination);
                throw e;
            }
            FilePart part = new FilePart(name, fileName, destination, Files.size(destination));
            files.computeIfAbsent(name, ignored -> new ArrayList<>()).add(part);
            if (last) {
                return new Form(fields, files, limited.count());
            }
        }
        throw new BadRequestException("Too many multipart fields or files");
    }

    private static String boundaryFrom(String contentType) throws BadRequestException {
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            throw new BadRequestException("Content-Type must be multipart/form-data");
        }
        Matcher matcher = Pattern.compile("(?:^|;)\\s*boundary=(?:\\\"([^\\\"]+)\\\"|([^;]+))",
                Pattern.CASE_INSENSITIVE).matcher(contentType);
        if (!matcher.find()) {
            throw new BadRequestException("Missing multipart boundary");
        }
        String boundary = matcher.group(1) != null ? matcher.group(1) : matcher.group(2).trim();
        if (boundary.isEmpty() || boundary.length() > 200 || boundary.indexOf('\r') >= 0 || boundary.indexOf('\n') >= 0) {
            throw new BadRequestException("Invalid multipart boundary");
        }
        return boundary;
    }

    private static Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String line = readLine(in);
            if (line.isEmpty()) {
                return headers;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new BadRequestException("Invalid multipart header");
            }
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
    }

    private static Map<String, String> parseDisposition(String raw) throws BadRequestException {
        if (raw == null || !raw.toLowerCase(Locale.ROOT).startsWith("form-data")) {
            throw new BadRequestException("Invalid Content-Disposition");
        }
        Map<String, String> values = new LinkedHashMap<>();
        Matcher matcher = PARAMETER.matcher(raw);
        while (matcher.find()) {
            String value = matcher.group(2) != null ? unescapeQuoted(matcher.group(2)) : matcher.group(3).trim();
            values.put(matcher.group(1).toLowerCase(Locale.ROOT), value);
        }
        return values;
    }

    private static String unescapeQuoted(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int previous = -1;
        while (out.size() <= MAX_LINE_BYTES) {
            int current = in.read();
            if (current < 0) {
                throw new BadRequestException("Unexpected end of multipart request");
            }
            if (previous == '\r' && current == '\n') {
                byte[] bytes = out.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.ISO_8859_1);
            }
            out.write(current);
            previous = current;
        }
        throw new BadRequestException("Multipart header line is too long");
    }

    /** Returns true for the closing boundary and false when another part follows. */
    private static boolean copyUntilBoundary(InputStream in, OutputStream out, byte[] delimiter, long partLimit)
            throws IOException {
        int[] lps = prefixTable(delimiter);
        int matched = 0;
        long written = 0;
        while (true) {
            int next = in.read();
            if (next < 0) {
                if (matched > 0) {
                    out.write(delimiter, 0, matched);
                }
                throw new BadRequestException("Multipart request ended before its closing boundary");
            }
            byte value = (byte) next;
            while (matched > 0 && value != delimiter[matched]) {
                int fallback = lps[matched - 1];
                int emit = matched - fallback;
                written = writeChecked(out, delimiter, 0, emit, written, partLimit);
                matched = fallback;
            }
            if (value == delimiter[matched]) {
                matched++;
                if (matched == delimiter.length) {
                    break;
                }
            } else {
                written = writeChecked(out, new byte[]{value}, 0, 1, written, partLimit);
            }
        }
        out.flush();

        int first = in.read();
        int second = in.read();
        if (first == '-' && second == '-') {
            consumeOptionalCrlf(in);
            return true;
        }
        if (first == '\r' && second == '\n') {
            return false;
        }
        throw new BadRequestException("Invalid multipart boundary suffix");
    }

    private static long writeChecked(
            OutputStream out, byte[] bytes, int offset, int length, long written, long partLimit) throws IOException {
        long updated = written + length;
        if (updated > partLimit) {
            throw new BadRequestException("A form field is too large");
        }
        out.write(bytes, offset, length);
        return updated;
    }

    private static int[] prefixTable(byte[] pattern) {
        int[] lps = new int[pattern.length];
        for (int i = 1, length = 0; i < pattern.length;) {
            if (pattern[i] == pattern[length]) {
                lps[i++] = ++length;
            } else if (length > 0) {
                length = lps[length - 1];
            } else {
                lps[i++] = 0;
            }
        }
        return lps;
    }

    private static void consumeOptionalCrlf(InputStream in) throws IOException {
        in.mark(2);
        int first = in.read();
        int second = in.read();
        if (first != '\r' || second != '\n') {
            in.reset();
        }
    }

    private static Path destinationFor(Path taskRoot, String fieldName, String original, int index)
            throws BadRequestException {
        if ("inputFiles".equals(fieldName)) {
            String base = safeSegment(baseName(original));
            return taskRoot.resolve("inputs").resolve(String.format(Locale.ROOT, "%04d-%s", index, base));
        }
        if ("picsFiles".equals(fieldName)) {
            Path relative = safeRelativePath(original);
            return taskRoot.resolve("pics").resolve(relative);
        }
        throw new BadRequestException("Unexpected file field: " + fieldName);
    }

    private static Path safeRelativePath(String raw) throws BadRequestException {
        String normalized = raw == null ? "" : raw.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            throw new BadRequestException("An uploaded file has no name");
        }
        Path result = Path.of("");
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new BadRequestException("Unsafe upload path");
            }
            result = result.resolve(safeSegment(segment));
        }
        if (result.getNameCount() == 0) {
            throw new BadRequestException("An uploaded file has no usable name");
        }
        return result;
    }

    private static String safeSegment(String raw) {
        String value = raw == null ? "upload" : raw.replaceAll("[\\p{Cntrl}/\\\\:]", "_").trim();
        if (value.isEmpty() || ".".equals(value) || "..".equals(value)) {
            return "upload";
        }
        return value.length() > 180 ? value.substring(value.length() - 180) : value;
    }

    private static String baseName(String raw) {
        if (raw == null) {
            return "upload";
        }
        String normalized = raw.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    record FilePart(String fieldName, String originalFileName, Path path, long size) {
    }

    static final class Form {
        private final Map<String, List<String>> fields;
        private final Map<String, List<FilePart>> files;
        private final long receivedBytes;

        private Form(Map<String, List<String>> fields, Map<String, List<FilePart>> files, long receivedBytes) {
            this.fields = fields;
            this.files = files;
            this.receivedBytes = receivedBytes;
        }

        String first(String name) {
            List<String> values = fields.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        List<FilePart> files(String name) {
            return files.containsKey(name) ? Collections.unmodifiableList(files.get(name)) : List.of();
        }

        long receivedBytes() {
            return receivedBytes;
        }
    }

    static final class BadRequestException extends IOException {
        BadRequestException(String message) {
            super(message);
        }
    }

    static final class UploadTooLargeException extends IOException {
        UploadTooLargeException(long maxBytes) {
            super("Upload exceeds the server limit of " + (maxBytes / 1024 / 1024) + " MiB");
        }
    }

    private static final class CountingLimitedInputStream extends InputStream {
        private final InputStream delegate;
        private final long limit;
        private long count;

        private CountingLimitedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                increment(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = delegate.read(bytes, offset, length);
            if (read > 0) {
                increment(read);
            }
            return read;
        }

        private void increment(long amount) throws UploadTooLargeException {
            count += amount;
            if (count > limit) {
                throw new UploadTooLargeException(limit);
            }
        }

        long count() {
            return count;
        }
    }
}
