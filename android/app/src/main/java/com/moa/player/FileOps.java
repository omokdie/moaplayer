package com.moa.player;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Moving, copying, renaming and deleting video files together with their subtitle files. */
final class FileOps {
    private FileOps() {}

    /** Files that are surely not videos (posters, info files…): they never own subtitles. */
    private static final Set<String> NOT_VIDEO_EXT = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "tif", "tiff", "nfo", "txt", "xml",
            "json", "md5", "sha1", "sfv", "par2", "torrent", "url", "lnk", "ini", "db", "log", "htm", "html",
            "pdf", "zip", "rar", "7z", "nomedia"));

    /**
     * Whether another file next to a video may be a video that shares its subtitles. Anything that is
     * not clearly something else counts (".3gpp", ".qt", ".m2t"…): wrongly keeping a subtitle file is
     * harmless, wrongly deleting or moving another video's subtitles is not.
     */
    static boolean mayBeVideo(String name) {
        return !SubtitleParser.isSubtitleFile(name) && !NOT_VIDEO_EXT.contains(Util.ext(name))
                && !name.endsWith(PART);
    }

    /** Hidden name a copy is written under until it is complete: a killed copy never looks like the file. */
    private static final String PART = ".moa-part";

    private static File partFile(File dst) {
        return new File(dst.getParentFile(), "." + dst.getName() + PART);
    }

    /** Removes copies left half done (app killed during a copy) more than 10 minutes ago. */
    static void cleanParts(File dir) {
        File[] all = dir != null ? dir.listFiles() : null;
        if (all == null) return;
        long old = System.currentTimeMillis() - 10 * 60_000L;
        for (File f : all) {
            String n = f.getName();
            if (f.isFile() && n.startsWith(".") && n.endsWith(PART) && f.lastModified() < old) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    // ------------------------------------------------------------------ subtitle files of a video

    /** Subtitle files that go with a video: "Movie.smi", "Movie.ko.srt", "Movie_eng.srt" for "Movie.mp4". */
    static final class Sidecars {
        final List<File> files = new ArrayList<>();
        /** Another video in the folder has the same name ("Movie.mkv" next to "Movie.mp4"): they share them. */
        boolean shared;
    }

    private static String base(String name) {
        return Util.stripExt(name).toLowerCase(Locale.ROOT);
    }

    private static boolean belongs(String subBase, String videoBase) {
        return subBase.equals(videoBase) || subBase.startsWith(videoBase + ".")
                || subBase.startsWith(videoBase + "_") || subBase.startsWith(videoBase + " ");
    }

    static Sidecars sidecarsOf(File video) {
        Sidecars s = new Sidecars();
        File dir = video.getParentFile();
        File[] all = dir != null ? dir.listFiles() : null;
        if (all == null) return s;
        String name = video.getName();
        String vb = base(name);
        List<String> others = new ArrayList<>();
        for (File f : all) {
            String n = f.getName();
            if (n.equals(name) || !mayBeVideo(n) || !f.isFile()) continue;
            String b = base(n);
            if (b.equals(vb)) s.shared = true;
            else if (b.length() > vb.length()) others.add(b);
        }
        for (File f : all) {
            String n = f.getName();
            if (!SubtitleParser.isSubtitleFile(n) || !f.isFile()) continue;
            String sb = base(n);
            if (!belongs(sb, vb)) continue;
            // "Movie 2.srt" next to "Movie.mp4" and "Movie 2.mp4" belongs to the longer name
            boolean other = false;
            for (String b : others) {
                if (belongs(sb, b)) {
                    other = true;
                    break;
                }
            }
            if (!other) s.files.add(f);
        }
        return s;
    }

    /** Gives the subtitle files the new video name ("Old.ko.srt" -> "New.ko.srt"), in {@code dir}. */
    private static File sidecarTarget(File sub, File oldVideo, File newVideo, File dir) {
        int ob = Util.stripExt(oldVideo.getName()).length();
        String rest = sub.getName().length() >= ob ? sub.getName().substring(ob) : sub.getName();
        return new File(dir, Util.stripExt(newVideo.getName()) + rest);
    }

    static void renameSidecars(File oldVideo, File newVideo, Sidecars subs) {
        for (File s : subs.files) {
            File t = sidecarTarget(s, oldVideo, newVideo, s.getParentFile());
            if (t.exists()) continue;
            if (subs.shared) copyPlain(s, t, false);
            else //noinspection ResultOfMethodCallIgnored
                s.renameTo(t);
        }
    }

    /** "movie.mp4" -> "Movie.mp4": shared storage ignores case, so go through a temporary name. */
    static boolean renameCaseOnly(File from, File to) {
        File tmp = new File(from.getParentFile(), from.getName() + ".moa-rename");
        if (!from.renameTo(tmp)) return false;
        if (tmp.renameTo(to)) return true;
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(from);   // put it back
        return false;
    }

    /** A free name in {@code dir}: "Movie.mp4", else "Movie (1).mp4", "Movie (2).mp4"… */
    static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String b = dot > 0 ? name.substring(0, dot) : name;
        String e = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, b + " (" + i + ")" + e);
            if (!f.exists()) return f;
        }
        return new File(dir, b + " (" + System.currentTimeMillis() + ")" + e);
    }

    /** "/storage/emulated/0" or "/storage/1234-5678": a move inside one is a quick rename. */
    static String volumeOf(String path) {
        if (path == null) return "";
        String[] p = path.split("/");
        if (p.length > 3 && "storage".equals(p[1]) && "emulated".equals(p[2])) return "/storage/emulated/" + p[3];
        if (p.length > 2 && "storage".equals(p[1])) return "/storage/" + p[2];
        if (p.length > 1) return "/" + p[1];
        return "/";
    }

    // ------------------------------------------------------------------ delete

    static final class DeleteResult {
        int ok, failed;
        final List<String> gone = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
    }

    /**
     * Deletes a video file. Android 9 and older cannot write to SD cards through the file API, but
     * MediaStore (which deletes the file with its entry) can: tried when the file API fails.
     */
    static boolean deleteVideo(File f, Uri uri, ContentResolver cr) {
        if (!f.exists() || f.delete()) return true;
        if (cr == null || uri == null) return false;
        try {
            cr.delete(uri, null, null);
        } catch (RuntimeException ignored) {   // e.g. RecoverableSecurityException
        }
        return !f.exists();
    }

    static DeleteResult delete(List<VideoItem> items, ContentResolver cr) {
        DeleteResult r = new DeleteResult();
        for (VideoItem v : items) {
            if (v.path == null) {
                r.failed++;
                continue;
            }
            File f = new File(v.path);
            Sidecars subs = sidecarsOf(f);
            if (deleteVideo(f, v.uri, cr)) {
                r.ok++;
                r.gone.add(f.getAbsolutePath());
                r.keys.add(v.key());
                if (!subs.shared) {
                    for (File s : subs.files) if (s.delete()) r.gone.add(s.getAbsolutePath());
                }
            } else {
                r.failed++;
            }
        }
        return r;
    }

    // ------------------------------------------------------------------ move / copy

    interface Progress {
        /** File {@code i} of {@code n} ({@code name}); {@code done} of {@code total} bytes copied (total 0: count files). */
        void update(int i, int n, long done, long total, String name);

        boolean cancelled();

        /** A video was moved: its history should follow at once (a batch may be cut short). */
        void moved(String from, String to);
    }

    static final class Result {
        int ok, failed, skipped;
        /** Moves that had to copy (another storage) and could not delete the original afterwards. */
        int keptOriginal;
        boolean cancelled;
        final List<String> scan = new ArrayList<>();
        /** Moved videos: {old path, new path}. */
        final List<String[]> moved = new ArrayList<>();
    }

    /** Bytes that a transfer really copies (a move on the same storage is only a rename). */
    static long bytesToCopy(List<VideoItem> items, File dest, boolean copy) {
        String vol = volumeOf(dest.getAbsolutePath());
        long n = 0;
        for (VideoItem v : items) {
            if (v.path == null) continue;
            if (copy || !vol.equals(volumeOf(v.path))) n += new File(v.path).length();
        }
        return n;
    }

    static Result transfer(List<VideoItem> items, File dest, boolean copy, Progress p, ContentResolver cr) {
        Result r = new Result();
        int n = items.size();
        long total = bytesToCopy(items, dest, copy);
        long done = 0;
        String destPath = dest.getAbsolutePath();
        String destVol = volumeOf(destPath);
        cleanParts(dest);
        for (int i = 0; i < n; i++) {
            if (p.cancelled()) {
                r.cancelled = true;
                break;
            }
            VideoItem v = items.get(i);
            File src = v.path != null ? new File(v.path) : null;
            if (src == null || !src.isFile()) {
                r.failed++;
                continue;
            }
            p.update(i, n, done, total, src.getName());
            File parent = src.getParentFile();
            if (!copy && parent != null && parent.getAbsolutePath().equals(destPath)) {
                r.skipped++;   // already there
                continue;
            }
            boolean bytes = copy || !destVol.equals(volumeOf(src.getAbsolutePath()));
            long len = src.length();
            Sidecars subs = sidecarsOf(src);
            File dst = unique(dest, src.getName());
            boolean ok = false, original = false;
            if (!copy && src.renameTo(dst)) {
                ok = true;
            } else if (copyFile(src, dst, i, n, done, total, p)) {
                ok = true;
                if (!copy && !deleteVideo(src, v.uri, cr)) original = true;
            }
            if (bytes) done += len;
            if (!ok) {
                if (p.cancelled()) {
                    r.cancelled = true;
                    break;
                }
                r.failed++;
                continue;
            }
            r.ok++;
            r.scan.add(dst.getAbsolutePath());
            if (!copy && !original) {
                r.scan.add(src.getAbsolutePath());
                r.moved.add(new String[]{src.getAbsolutePath(), dst.getAbsolutePath()});
                p.moved(src.getAbsolutePath(), dst.getAbsolutePath());
            }
            if (original) r.keptOriginal++;
            // subtitles go along, renamed like the video when it needed a free name
            boolean moveSubs = !copy && !original && !subs.shared;
            for (File s : subs.files) {
                File t = sidecarTarget(s, src, dst, dest);
                if (t.exists()) continue;
                if (moveSubs) {
                    if (!s.renameTo(t) && copyPlain(s, t, true)) //noinspection ResultOfMethodCallIgnored
                        s.delete();
                } else {
                    copyPlain(s, t, false);
                }
            }
        }
        return r;
    }

    /**
     * Copies under a hidden temporary name and renames it to {@code dst} only when it is complete and
     * on the storage (fsync): if the app is killed meanwhile, no half file shows up as a video.
     */
    private static boolean copyFile(File src, File dst, int i, int n, long done, long total, Progress p) {
        File part = partFile(dst);
        byte[] buf = new byte[1 << 20];
        long pos = 0;
        boolean ok = false;
        try (InputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(part)) {
            int r;
            while ((r = in.read(buf)) > 0) {
                if (p.cancelled()) return false;
                out.write(buf, 0, r);
                pos += r;
                p.update(i, n, done + pos, total, src.getName());
            }
            // a move deletes the original next: the copy must really be on the storage first
            out.getFD().sync();
            ok = true;
        } catch (IOException | RuntimeException e) {
            ok = false;
        } finally {
            if (!ok) //noinspection ResultOfMethodCallIgnored
                part.delete();
        }
        if (!ok) return false;
        if (dst.exists() || !part.renameTo(dst)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        }
        //noinspection ResultOfMethodCallIgnored
        dst.setLastModified(src.lastModified());
        return true;
    }

    /** Small files (subtitles). {@code sync}: the original is deleted next. */
    static boolean copyPlain(File src, File dst, boolean sync) {
        boolean ok = false;
        try (InputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            if (sync) out.getFD().sync();
            ok = true;
        } catch (IOException | RuntimeException e) {
            ok = false;
        } finally {
            if (!ok) //noinspection ResultOfMethodCallIgnored
                dst.delete();
        }
        return ok;
    }
}
