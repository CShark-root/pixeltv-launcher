import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Инструмент перевода PixelTv.
 *   java WrapStrings.java wrap <file.kt>...      — обернуть русские строки в tr("…"), вставки → {0}, {1}
 *   java WrapStrings.java keys <file.kt>...      — вывести все ключи tr("…") (по одному в строке, JSON-строкой)
 * Пропускает комментарии, сырые строки, строки журнала (DebugLog./Log.), const val и уже обёрнутые tr(.
 */
public class WrapStrings {
    static String src;
    static int pos;
    static final Set<String> keys = new TreeSet<>();

    public static void main(String[] a) throws Exception {
        String mode = a[0];
        for (int i = 1; i < a.length; i++) {
            Path p = Paths.get(a[i]);
            src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            pos = 0;
            if (mode.equals("wrap")) {
                String out = code(false);
                if (!out.equals(src)) Files.write(p, out.getBytes(StandardCharsets.UTF_8));
            } else {
                collect();
            }
        }
        if (mode.equals("keys")) for (String k : keys) System.out.println(k);
    }

    static boolean cyr(String s) {
        for (char c : s.toCharArray()) if ((c >= 'А' && c <= 'я') || c == 'Ё' || c == 'ё') return true;
        return false;
    }

    /** Код до конца файла или до закрывающей } шаблона (inTemplate). */
    static String code(boolean inTemplate) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (src.startsWith("//", pos)) { int e = src.indexOf('\n', pos); if (e < 0) e = src.length(); sb.append(src, pos, e); pos = e; continue; }
            if (src.startsWith("/*", pos)) { int e = src.indexOf("*/", pos + 2); e = e < 0 ? src.length() : e + 2; sb.append(src, pos, e); pos = e; continue; }
            if (src.startsWith("\"\"\"", pos)) { int e = src.indexOf("\"\"\"", pos + 3); e = e < 0 ? src.length() : e + 3; sb.append(src, pos, e); pos = e; continue; }
            if (c == '\'') { int e = pos + 1; while (e < src.length() && src.charAt(e) != '\'') { if (src.charAt(e) == '\\') e++; e++; } sb.append(src, pos, Math.min(e + 1, src.length())); pos = e + 1; continue; }
            if (c == '"') { sb.append(string(sb)); continue; }
            if (inTemplate) {
                if (c == '{') depth++;
                if (c == '}') { if (depth == 0) return sb.toString(); depth--; }
            }
            sb.append(c); pos++;
        }
        return sb.toString();
    }

    /** Строка с позиции pos (на кавычке). Возвращает исходный или обёрнутый вариант. */
    static String string(StringBuilder before) {
        int start = pos;
        pos++; // "
        StringBuilder text = new StringBuilder();      // текст ключа с {i}
        StringBuilder raw = new StringBuilder("\"");   // исходник с переписанными вложенными строками
        List<String> args = new ArrayList<>();
        while (pos < src.length() && src.charAt(pos) != '"') {
            char c = src.charAt(pos);
            if (c == '\\') { text.append(src, pos, pos + 2); raw.append(src, pos, pos + 2); pos += 2; continue; }
            if (c == '$' && pos + 1 < src.length() && src.charAt(pos + 1) == '{') {
                pos += 2;
                String expr = code(true);
                pos++; // }
                raw.append("${").append(expr).append("}");
                text.append("{").append(args.size()).append("}");
                args.add("(" + expr.trim() + ")");
                continue;
            }
            if (c == '$' && pos + 1 < src.length() && Character.isJavaIdentifierStart(src.charAt(pos + 1))) {
                int e = pos + 1;
                while (e < src.length() && Character.isJavaIdentifierPart(src.charAt(e))) e++;
                String id = src.substring(pos + 1, e);
                raw.append(src, pos, e);
                text.append("{").append(args.size()).append("}");
                args.add(id);
                pos = e;
                continue;
            }
            text.append(c); raw.append(c); pos++;
        }
        pos++; // "
        raw.append('"');
        String t = text.toString();
        if (!cyr(t) || skip(before)) return raw.toString();
        StringBuilder w = new StringBuilder("tr(\"").append(t).append('"');
        for (String arg : args) w.append(", ").append(arg);
        return w.append(')').toString();
    }

    static boolean skip(StringBuilder before) {
        int ls = before.lastIndexOf("\n");
        String line = before.substring(ls + 1);
        String tail = before.length() >= 3 ? before.substring(before.length() - 3) : before.toString();
        return line.contains("DebugLog.") || line.contains("Log.i(") || line.contains("Log.w(") || line.contains("Log.e(")
            || line.contains("const val") || tail.endsWith("tr(") || line.trim().startsWith("@");
    }

    static void collect() {
        int i = 0;
        while ((i = src.indexOf("tr(\"", i)) >= 0) {
            if (i > 0 && Character.isJavaIdentifierPart(src.charAt(i - 1))) { i++; continue; }
            int e = i + 4;
            StringBuilder k = new StringBuilder();
            while (e < src.length() && src.charAt(e) != '"') { if (src.charAt(e) == '\\') { k.append(src, e, e + 2); e += 2; continue; } k.append(src.charAt(e)); e++; }
            keys.add("\"" + k + "\"");
            i = e;
        }
    }
}
