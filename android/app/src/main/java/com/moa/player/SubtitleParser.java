package com.moa.player;

import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser for external subtitle files: SAMI (.smi), SubRip (.srt), WebVTT (.vtt), ASS/SSA. */
public final class SubtitleParser {
    private SubtitleParser() {}

    public static final String[] EXTS = {"smi", "sami", "srt", "ass", "ssa", "vtt"};

    public static boolean isSubtitleFile(String name) {
        String e = Util.ext(name);
        for (String x : EXTS) if (x.equals(e)) return true;
        return false;
    }

    public static final class Cue {
        public final long start, end;
        final String html;
        final boolean top;
        private CharSequence spanned;

        Cue(long start, long end, String html, boolean top) {
            this.start = start;
            this.end = end;
            this.html = html;
            this.top = top;
        }

        @SuppressWarnings("deprecation")
        CharSequence text() {
            if (spanned == null) {
                CharSequence s;
                try {
                    s = android.os.Build.VERSION.SDK_INT >= 24
                            ? Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
                            : Html.fromHtml(html);
                } catch (Exception e) {
                    s = html.replaceAll("<[^>]*>", "");
                }
                spanned = trim(s);
            }
            return spanned;
        }
    }

    public static final class Track {
        public String name;
        public String file;
        public final List<Cue> cues = new ArrayList<>();
        long maxDur = 10000;
        public boolean lastTop;

        void finish() {
            Collections.sort(cues, new Comparator<Cue>() {
                @Override public int compare(Cue a, Cue b) { return Long.compare(a.start, b.start); }
            });
            long m = 0;
            for (Cue c : cues) m = Math.max(m, c.end - c.start);
            maxDur = Math.min(Math.max(m, 1000), 15 * 60 * 1000);
        }

        /** Text of all cues active at time t (ms), or null. */
        public CharSequence textAt(long t) {
            List<Cue> active = activeAt(t);
            if (active == null) return null;
            lastTop = true;
            for (Cue c : active) if (!c.top) lastTop = false;
            if (active.size() == 1) {
                CharSequence s = active.get(0).text();
                return s.length() == 0 ? null : s;
            }
            SpannableStringBuilder sb = new SpannableStringBuilder();
            for (Cue c : active) {
                CharSequence s = c.text();
                if (s.length() == 0) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(s);
            }
            return sb.length() == 0 ? null : sb;
        }

        /** Cues active at time t in start order, or null when none. */
        List<Cue> activeAt(long t) {
            int lo = 0, hi = cues.size() - 1, idx = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (cues.get(mid).start <= t) {
                    idx = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            if (idx < 0) return null;
            List<Cue> active = null;
            for (int i = idx; i >= 0; i--) {
                Cue c = cues.get(i);
                if (c.start < t - maxDur) break;
                if (c.end > t) {
                    if (active == null) active = new ArrayList<>(2);
                    active.add(0, c);
                }
            }
            return active;
        }
    }

    // ------------------------------------------------------------------ IO

    public static byte[] readFile(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    public static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            total += n;
            if (total > 20 * 1024 * 1024) break;
        }
        return bo.toByteArray();
    }

    public static Charset koreanCharset() {
        String[] names = {"MS949", "x-windows-949", "windows-949", "CP949", "EUC-KR"};
        for (String n : names) {
            try {
                if (Charset.isSupported(n)) return Charset.forName(n);
            } catch (Exception ignored) {
            }
        }
        return Charset.forName("ISO-8859-1");
    }

    static String decode(byte[] b, String pref) {
        if (b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF)
            return new String(b, 3, b.length - 3, Charset.forName("UTF-8"));
        if (b.length >= 2 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xFE)
            return new String(b, 2, b.length - 2, Charset.forName("UTF-16LE"));
        if (b.length >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF)
            return new String(b, 2, b.length - 2, Charset.forName("UTF-16BE"));
        if (pref != null && !"auto".equals(pref)) {
            try {
                if ("MS949".equals(pref)) return new String(b, koreanCharset());
                if (Charset.isSupported(pref)) return new String(b, Charset.forName(pref));
            } catch (Exception ignored) {
            }
        }
        int z0 = 0, z1 = 0, n = Math.min(b.length, 4096);
        for (int i = 0; i < n; i++) if (b[i] == 0) { if ((i & 1) == 0) z0++; else z1++; }
        if (n > 16 && z1 > n / 4) return new String(b, Charset.forName("UTF-16LE"));
        if (n > 16 && z0 > n / 4) return new String(b, Charset.forName("UTF-16BE"));
        try {
            CharsetDecoder d = Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return d.decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, koreanCharset());
        }
    }

    // ------------------------------------------------------------------ entry

    public static List<Track> parse(byte[] data, String fileName, String encPref) {
        String s = decode(data, encPref);
        if (s.length() > 0 && s.charAt(0) == '\uFEFF') s = s.substring(1);
        String head = s.substring(0, Math.min(s.length(), 4000)).toLowerCase(Locale.ROOT);
        String ext = Util.ext(fileName);
        List<Track> out;
        if (head.contains("<sami") || head.contains("<sync") || "smi".equals(ext) || "sami".equals(ext)) {
            out = parseSmi(s);
        } else if (head.contains("[script info]") || head.contains("[events]") || "ass".equals(ext) || "ssa".equals(ext)) {
            out = single(parseAss(s));
        } else if (head.startsWith("webvtt") || "vtt".equals(ext)) {
            out = single(parseSrtLike(s, true));
        } else {
            out = single(parseSrtLike(s, false));
        }
        List<Track> res = new ArrayList<>();
        for (Track t : out) {
            if (t.cues.isEmpty()) continue;
            t.file = fileName;
            t.finish();
            res.add(t);
        }
        return res;
    }

    private static List<Track> single(Track t) {
        List<Track> l = new ArrayList<>();
        l.add(t);
        return l;
    }

    // ------------------------------------------------------------------ SRT / VTT

    private static final Pattern SRT_TIME = Pattern.compile(
            "((?:\\d+:)?\\d{1,2}:\\d{1,2}(?:[,.]\\d{1,3})?)\\s*-->\\s*((?:\\d+:)?\\d{1,2}:\\d{1,2}(?:[,.]\\d{1,3})?)");
    private static final Pattern NUMBER = Pattern.compile("^\\s*\\d+\\s*$");

    static long parseClock(String t) {
        t = t.trim().replace(',', '.');
        String frac = "0";
        int dot = t.indexOf('.');
        if (dot >= 0) {
            frac = t.substring(dot + 1);
            t = t.substring(0, dot);
        }
        String[] p = t.split(":");
        long h = 0, m, s;
        try {
            if (p.length == 3) {
                h = Long.parseLong(p[0]);
                m = Long.parseLong(p[1]);
                s = Long.parseLong(p[2]);
            } else if (p.length == 2) {
                m = Long.parseLong(p[0]);
                s = Long.parseLong(p[1]);
            } else {
                return -1;
            }
            long ms;
            if (frac.isEmpty()) ms = 0;
            else if (frac.length() == 1) ms = Long.parseLong(frac) * 100;
            else if (frac.length() == 2) ms = Long.parseLong(frac) * 10;
            else ms = Long.parseLong(frac.substring(0, 3));
            return ((h * 60 + m) * 60 + s) * 1000 + ms;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static Track parseSrtLike(String s, boolean vtt) {
        Track tr = new Track();
        tr.name = vtt ? "WebVTT" : "SRT";
        String[] lines = s.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        long st = -1, en = -1;
        List<String> text = new ArrayList<>();
        for (String line : lines) {
            Matcher m = SRT_TIME.matcher(line);
            if (m.find()) {
                if (st >= 0) addSrtCue(tr, st, en, text, vtt);
                st = parseClock(m.group(1));
                en = parseClock(m.group(2));
                text.clear();
                continue;
            }
            if (st < 0) continue;
            if (line.trim().isEmpty()) {
                addSrtCue(tr, st, en, text, vtt);
                st = -1;
                text.clear();
                continue;
            }
            text.add(line);
        }
        if (st >= 0) addSrtCue(tr, st, en, text, vtt);
        return tr;
    }

    private static final Pattern ASS_TAG = Pattern.compile("\\{[^}]*\\}");
    private static final Pattern TOP_TAG = Pattern.compile("\\{[^}]*\\\\an[789][^}]*\\}");
    private static final Pattern VTT_TAGS = Pattern.compile("<(?!/?(?:b|i|u)\\b)[^>]*>", Pattern.CASE_INSENSITIVE);

    private static void addSrtCue(Track tr, long st, long en, List<String> lines, boolean vtt) {
        if (st < 0 || en <= st) return;
        // A cue number directly before the next time line (missing blank line) is not text.
        while (!lines.isEmpty() && NUMBER.matcher(lines.get(lines.size() - 1)).matches() && lines.size() > 1) {
            lines.remove(lines.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        boolean top = false;
        for (String l : lines) {
            if (TOP_TAG.matcher(l).find()) top = true;
            l = ASS_TAG.matcher(l).replaceAll("");
            if (vtt) l = VTT_TAGS.matcher(l).replaceAll("");
            if (sb.length() > 0) sb.append("<br>");
            sb.append(l);
        }
        String html = sb.toString().trim();
        if (html.isEmpty()) return;
        tr.cues.add(new Cue(st, en, html, top));
    }

    // ------------------------------------------------------------------ SAMI

    private static final Pattern SYNC = Pattern.compile("<sync[^>]*?start\\s*=\\s*[\"']?(-?\\d+)[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_TAG = Pattern.compile("<p(?:\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_CLASS = Pattern.compile("class\\s*=\\s*[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_CLASS = Pattern.compile("\\.([\\w-]+)\\s*\\{([^}]*)\\}");
    private static final Pattern STYLE_NAME = Pattern.compile("name\\s*:\\s*([^;]+);?", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern END_TAGS = Pattern.compile("</(?:p|sync|body|sami)\\s*>", Pattern.CASE_INSENSITIVE);

    private static List<Track> parseSmi(String s) {
        Map<String, String> names = new LinkedHashMap<>();
        Matcher sm = STYLE_CLASS.matcher(s.substring(0, Math.min(s.length(), 20000)));
        while (sm.find()) {
            Matcher nm = STYLE_NAME.matcher(sm.group(2));
            if (nm.find()) names.put(sm.group(1).toUpperCase(Locale.ROOT), nm.group(1).trim());
        }
        int bodyStart = indexOfIgnoreCase(s, "<body");
        String body = bodyStart >= 0 ? s.substring(bodyStart) : s;
        body = COMMENT.matcher(body).replaceAll("");

        // class -> list of (start, html)
        Map<String, List<long[]>> times = new LinkedHashMap<>();
        Map<String, List<String>> texts = new LinkedHashMap<>();
        Matcher m = SYNC.matcher(body);
        List<int[]> spans = new ArrayList<>();
        List<Long> starts = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[]{m.start(), m.end()});
            try {
                starts.add(Long.parseLong(m.group(1)));
            } catch (NumberFormatException e) {
                starts.add(-1L);
            }
        }
        for (int i = 0; i < spans.size(); i++) {
            long start = starts.get(i);
            if (start < 0) continue;
            int segStart = spans.get(i)[1];
            int segEnd = i + 1 < spans.size() ? spans.get(i + 1)[0] : body.length();
            String seg = body.substring(segStart, segEnd);
            Matcher pm = P_TAG.matcher(seg);
            List<int[]> ps = new ArrayList<>();
            List<String> cls = new ArrayList<>();
            while (pm.find()) {
                ps.add(new int[]{pm.start(), pm.end()});
                Matcher cm = P_CLASS.matcher(pm.group());
                cls.add(cm.find() ? cm.group(1).toUpperCase(Locale.ROOT) : "DEFAULT");
            }
            if (ps.isEmpty()) {
                addSmi(times, texts, "DEFAULT", start, seg);
            } else {
                for (int k = 0; k < ps.size(); k++) {
                    int a = ps.get(k)[1];
                    int b = k + 1 < ps.size() ? ps.get(k + 1)[0] : seg.length();
                    addSmi(times, texts, cls.get(k), start, seg.substring(a, b));
                }
            }
        }
        List<Track> out = new ArrayList<>();
        for (Map.Entry<String, List<long[]>> e : times.entrySet()) {
            String c = e.getKey();
            List<long[]> tl = e.getValue();
            List<String> xl = texts.get(c);
            Track t = new Track();
            String nm = names.get(c);
            t.name = nm != null ? nm : smiClassName(c);
            for (int i = 0; i < tl.size(); i++) {
                String html = xl.get(i);
                if (html == null) continue; // a clear marker
                long st = tl.get(i)[0];
                long en = i + 1 < tl.size() ? tl.get(i + 1)[0] : st + 8000;
                if (en <= st) continue;
                t.cues.add(new Cue(st, en, html, false));
            }
            out.add(t);
        }
        // Korean first
        Collections.sort(out, new Comparator<Track>() {
            @Override public int compare(Track a, Track b) {
                return Integer.compare(rank(a.name), rank(b.name));
            }
            int rank(String n) {
                if (n == null) return 5;
                String l = n.toLowerCase(Locale.ROOT);
                if (l.contains("한국") || l.contains("kor") || l.startsWith("kr")) return 0;
                return 1;
            }
        });
        return out;
    }

    private static void addSmi(Map<String, List<long[]>> times, Map<String, List<String>> texts,
                               String cls, long start, String raw) {
        String t = END_TAGS.matcher(raw).replaceAll("");
        t = t.replace("\r", "").replace("\n", "").trim();
        String plain = t.replaceAll("(?i)<br\\s*/?>", "").replaceAll("<[^>]*>", "")
                .replaceAll("(?i)&nbsp;?", "").trim();
        List<long[]> tl = times.get(cls);
        if (tl == null) {
            tl = new ArrayList<>();
            times.put(cls, tl);
            texts.put(cls, new ArrayList<String>());
        }
        tl.add(new long[]{start});
        texts.get(cls).add(plain.isEmpty() ? null : t);
    }

    private static String smiClassName(String c) {
        String u = c.toUpperCase(Locale.ROOT);
        if (u.startsWith("KR") || u.startsWith("KO")) return "한국어";
        if (u.startsWith("EN") || u.startsWith("EG") || u.startsWith("US")) return "English";
        if (u.startsWith("JP") || u.startsWith("JA")) return "日本語";
        if (u.startsWith("CN") || u.startsWith("ZH")) return "中文";
        if ("DEFAULT".equals(u)) return "자막";
        return c;
    }

    private static int indexOfIgnoreCase(String s, String needle) {
        return s.toLowerCase(Locale.ROOT).indexOf(needle);
    }

    // ------------------------------------------------------------------ ASS / SSA

    private static Track parseAss(String s) {
        Track tr = new Track();
        tr.name = "ASS";
        String[] lines = s.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        boolean events = false;
        int iStart = 1, iEnd = 2, iText = 9, nFields = 10;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("[")) {
                events = line.equalsIgnoreCase("[events]");
                continue;
            }
            if (!events) continue;
            if (line.regionMatches(true, 0, "Format:", 0, 7)) {
                String[] f = line.substring(7).split(",");
                nFields = f.length;
                for (int i = 0; i < f.length; i++) {
                    String n = f[i].trim().toLowerCase(Locale.ROOT);
                    if (n.equals("start")) iStart = i;
                    else if (n.equals("end")) iEnd = i;
                    else if (n.equals("text")) iText = i;
                }
                continue;
            }
            if (!line.regionMatches(true, 0, "Dialogue:", 0, 9)) continue;
            String[] p = line.substring(9).split(",", nFields);
            if (p.length < nFields) continue;
            long st = parseClock(p[iStart]);
            long en = parseClock(p[iEnd]);
            if (st < 0 || en <= st) continue;
            String text = p[iText];
            if (text.matches(".*\\{[^}]*\\\\p[1-9][^}]*\\}.*")) continue; // vector drawing
            boolean top = TOP_TAG.matcher(text).find();
            text = ASS_TAG.matcher(text).replaceAll("");
            text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            text = text.replace("\\N", "<br>").replace("\\n", "<br>").replace("\\h", "&nbsp;");
            if (text.trim().isEmpty()) continue;
            tr.cues.add(new Cue(st, en, text, top));
        }
        return tr;
    }

    // ------------------------------------------------------------------ helpers

    static CharSequence trim(CharSequence s) {
        int a = 0, b = s.length();
        while (a < b && Character.isWhitespace(s.charAt(a))) a++;
        while (b > a && Character.isWhitespace(s.charAt(b - 1))) b--;
        if (a == 0 && b == s.length()) return s;
        if (s instanceof Spanned) return new SpannableStringBuilder(s, a, b);
        return s.subSequence(a, b);
    }

    /** Subtitle files next to a video: exact matches first, then the rest of the folder. */
    public static List<File> findSidecars(String videoPath, List<File> others) {
        List<File> exact = new ArrayList<>();
        if (videoPath == null) return exact;
        File v = new File(videoPath);
        File dir = v.getParentFile();
        if (dir == null) return exact;
        File[] files;
        try {
            files = dir.listFiles();
        } catch (SecurityException e) {
            files = null;
        }
        if (files == null) return exact;
        String base = Util.stripExt(v.getName()).toLowerCase(Locale.ROOT);
        List<File> partial = new ArrayList<>();
        for (File f : files) {
            String n = f.getName();
            if (!isSubtitleFile(n) || !f.isFile()) continue;
            String nb = Util.stripExt(n).toLowerCase(Locale.ROOT);
            if (nb.equals(base)) exact.add(f);
            else if (nb.startsWith(base + ".") || nb.startsWith(base + "_") || nb.startsWith(base + " ")) partial.add(f);
            else if (others != null) others.add(f);
        }
        Collections.sort(exact, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Integer.compare(extRank(a.getName()), extRank(b.getName()));
            }
        });
        exact.addAll(partial);
        if (others != null) {
            Collections.sort(others, new Comparator<File>() {
                @Override public int compare(File a, File b) { return Util.natural(a.getName(), b.getName()); }
            });
        }
        return exact;
    }

    private static int extRank(String n) {
        String e = Util.ext(n);
        if (e.equals("smi") || e.equals("sami")) return 0;
        if (e.equals("srt")) return 1;
        if (e.equals("ass") || e.equals("ssa")) return 2;
        return 3;
    }
}
