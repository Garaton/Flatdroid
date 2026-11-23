package com.garathon.flatdroid.flatpak.integrity;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The JNI functions of fdcheck.c as NativeCheck.kt declares them (the class name makes their JNI names). */
class NativeCheck {
    static native long start(byte[] root, byte[] manifest, byte[] store, byte[] ignore, int ignoreCount, int threads);
    static native int await(long handle, int millis, int[] progress);
    static native void cancel(long handle);
    static native byte[] strings(long handle);
    static native long[] numbers(long handle);
    static native void free(long handle);
}

/**
 * Checks fdcheck.c on a Linux host (test-fdwalk.sh builds and runs it): a deployment-like tree
 * with files, a link into a store folder (by its path and by another path to it), a guest link, a
 * link to a folder, an ignored path and names with a tab and an emoji, against manifests written
 * as FileIntegrity.write writes them.
 */
public class FdcheckTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    static byte[] joined(List<String> items) {
        StringBuilder b = new StringBuilder();
        for (String s : items) b.append(s).append('\0');
        return utf8(b.toString());
    }

    static String escape(String s) { return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r"); }

    /** A manifest line: hash, inode, ctime (seconds), size, link, path. */
    static String line(String sha, long ino, long ctime, long size, String link, String path) {
        return (sha == null ? "-" : sha) + "\t" + ino + "\t" + ctime + "\t" + size + "\t" + (link == null ? "-" : escape(link)) + "\t" + escape(path) + "\n";
    }

    /** The followed (inode, ctime seconds, size) of a path. */
    static long[] stat(Path p) throws Exception {
        long ino = ((Number) Files.getAttribute(p, "unix:ino")).longValue();
        long ctime = ((FileTime) Files.getAttribute(p, "unix:ctime")).toMillis() / 1000;
        return new long[] {ino, ctime, Files.size(p)};
    }

    static class Result {
        int status;
        Map<String, long[]> current = new HashMap<>(), base = new HashMap<>();
        Map<String, String[]> texts = new HashMap<>();
        List<String> removed = new ArrayList<>();
        long leaves;
    }

    static Result run(Path root, Path manifest, List<String> store, List<String> ignore, int threads) {
        long h = NativeCheck.start(utf8(root.toString()), utf8(manifest.toString()), joined(store), joined(ignore), ignore.size(), threads);
        Result r = new Result();
        if (h == 0) { r.status = -2; return r; }
        int[] progress = new int[2];
        int status;
        while ((status = NativeCheck.await(h, 50, progress)) < 0) {}
        r.status = status;
        if (status == 0) {
            byte[] s = NativeCheck.strings(h);
            long[] n = NativeCheck.numbers(h);
            int at = 0;
            List<String> all = new ArrayList<>();
            while (at < s.length) { int end = at; while (s[end] != 0) end++; all.add(new String(s, at, end - at, StandardCharsets.UTF_8)); at = end + 1; }
            int records = (int) n[0];
            for (int i = 0; i < records; i++) {
                int k = 3 + i * 10;
                String path = all.get(i * 4);
                r.current.put(path, new long[] {n[k + 1], n[k + 2], n[k + 3], n[k + 4]});
                if (n[k] != 0) r.base.put(path, new long[] {n[k + 5], n[k + 6], n[k + 7], n[k + 8], n[k + 9]});
                r.texts.put(path, new String[] {all.get(i * 4 + 1), all.get(i * 4 + 2), all.get(i * 4 + 3)});
            }
            for (int i = 0; i < n[1]; i++) r.removed.add(all.get(records * 4 + i));
            r.leaves = n[2];
            check(progress[0] == progress[1] && progress[1] == n[2], "progress at the end is every leaf (" + progress[0] + "/" + progress[1] + ")");
        }
        NativeCheck.free(h);
        return r;
    }

    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        Path work = Files.createTempDirectory("fdcheck");
        Path store = Files.createDirectory(work.resolve("store"));
        Path alias = Files.createSymbolicLink(work.resolve("alias"), store);
        Path root = Files.createDirectory(work.resolve("files"));
        // The store: an intermediate link to its content file.
        Path content = Files.write(store.resolve(".l2s.objabc0001.0002"), utf8("stored content"));
        Files.createSymbolicLink(store.resolve(".l2s.objabc0001"), content);
        Files.write(root.resolve("a.txt"), utf8("plain"));
        Files.createDirectories(root.resolve("dir/sub"));
        Files.write(root.resolve("dir/sub/b.txt"), utf8("bee"));
        Files.createSymbolicLink(root.resolve("dir/guest"), Path.of("/usr/lib/nowhere"));
        Files.createSymbolicLink(root.resolve("stored"), store.resolve(".l2s.objabc0001"));
        Files.createSymbolicLink(root.resolve("aliased"), alias.resolve(".l2s.objabc0001"));
        Files.createSymbolicLink(root.resolve("linkdir"), root.resolve("dir"));
        Files.createDirectories(root.resolve("etc"));
        Files.write(root.resolve("etc/ignored"), utf8("x"));
        String odd = "tab\there é 😀";
        Files.write(root.resolve(odd), utf8("odd"));
        List<String> stores = List.of(store.toString(), store.toString(), store.toRealPath().toString());
        List<String> ignore = List.of("etc/ignored");

        long[] a = stat(root.resolve("a.txt")), b = stat(root.resolve("dir/sub/b.txt")), c = stat(content), o = stat(root.resolve(odd));
        StringBuilder m = new StringBuilder();
        m.append(line("aa", a[0], a[1], a[2], null, "a.txt"));
        m.append(line("bb", b[0], b[1], b[2], null, "dir/sub/b.txt"));
        m.append(line(null, 0, 0, 0, "/usr/lib/nowhere", "dir/guest"));
        m.append(line("cc", c[0], c[1], c[2], store.resolve(".l2s.objabc0001").toString(), "stored"));
        m.append(line("cc", c[0], c[1], c[2], alias.resolve(".l2s.objabc0001").toString(), "aliased"));
        m.append(line(null, 0, 0, 0, root.resolve("dir").toString(), "linkdir"));
        m.append(line("oo", o[0], o[1], o[2], null, odd));
        m.append(line("ii", 1, 1, 1, null, "etc/ignored"));
        m.append("broken line\n").append("x\t1\t2\tnot-a-number\t-\tbad\n");
        Path manifest = Files.write(work.resolve("m.manifest"), utf8(m.toString()));

        for (int threads : new int[] {1, 4}) {
            Result r = run(root, manifest, stores, ignore, threads);
            check(r.status == 0, threads + " threads: done");
            check(r.leaves == 7, threads + " threads: 7 leaves, the ignored one and the folders left out (" + r.leaves + ")");
            check(r.current.isEmpty() && r.removed.isEmpty(), threads + " threads: nothing differs " + r.current.keySet() + " " + r.removed);
        }

        // Changes: a.txt grows, a file is added, b.txt goes, the guest link moves.
        Files.write(root.resolve("a.txt"), utf8("plain and more"));
        Files.write(root.resolve("dir/new.txt"), utf8("new"));
        Files.delete(root.resolve("dir/sub/b.txt"));
        Files.delete(root.resolve("dir/guest"));
        Files.createSymbolicLink(root.resolve("dir/guest"), Path.of("/usr/lib/elsewhere"));
        Result r = run(root, manifest, stores, ignore, 4);
        check(r.status == 0, "changed: done");
        check(r.current.keySet().equals(new java.util.HashSet<>(List.of("a.txt", "dir/new.txt", "dir/guest"))), "changed: a.txt, dir/new.txt, dir/guest differ " + r.current.keySet());
        check(r.base.containsKey("a.txt") && r.base.get("a.txt")[1] == 1 && "aa".equals(r.texts.get("a.txt")[2]), "changed: a.txt comes with its hash");
        check(r.current.get("a.txt")[3] == 14, "changed: a.txt's new size");
        check(!r.base.containsKey("dir/new.txt"), "changed: dir/new.txt has no entry (added)");
        check(r.current.get("dir/guest")[0] == 1 && "/usr/lib/elsewhere".equals(r.texts.get("dir/guest")[0])
            && "/usr/lib/nowhere".equals(r.texts.get("dir/guest")[1]) && r.current.get("dir/guest")[1] == 0, "changed: the guest link by its targets alone");
        check(r.removed.equals(List.of("dir/sub/b.txt")), "changed: b.txt removed, the ignored entry not " + r.removed);

        // A later line for a path counts.
        Files.write(manifest, utf8(m + line("aa", 9, 9, 9, null, "a.txt")), java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        r = run(root, manifest, stores, ignore, 2);
        check(r.base.containsKey("a.txt") && r.base.get("a.txt")[2] == 9, "the last line of a path counts");

        check(run(root, work.resolve("missing.manifest"), stores, ignore, 2).status == 1, "no manifest: no baseline");

        // Cancelled at once, and freed while running.
        long h = NativeCheck.start(utf8(root.toString()), utf8(manifest.toString()), joined(stores), joined(ignore), 1, 2);
        NativeCheck.cancel(h);
        int status;
        while ((status = NativeCheck.await(h, 50, new int[2])) < 0) {}
        check(status == 2 || status == 0, "cancelled: ends (" + status + ")");
        NativeCheck.free(h);
        NativeCheck.free(NativeCheck.start(utf8(root.toString()), utf8(manifest.toString()), joined(stores), joined(ignore), 1, 2));
        check(NativeCheck.start(utf8(root.toString()), utf8(manifest.toString()), joined(stores), joined(ignore), 1, 0) == 0, "no threads: refused");

        // A wide tree, so the threads overlap: a full run, then cancels while the files are being read.
        Path wide = Files.createDirectory(work.resolve("wide"));
        StringBuilder wm = new StringBuilder();
        for (int d = 0; d < 40; d++) {
            Path dir = Files.createDirectories(wide.resolve("d" + d + "/sub"));
            for (int f = 0; f < 100; f++) {
                Path p = Files.write((f % 2 == 0 ? dir : dir.getParent()).resolve("f" + f), new byte[] {(byte) f});
                if (f % 4 == 0) { long[] st = stat(p); wm.append(line(null, st[0], st[1], st[2], null, wide.relativize(p).toString())); }
            }
        }
        Path wideManifest = Files.write(work.resolve("wide.manifest"), utf8(wm.toString()));
        r = run(wide, wideManifest, List.of("", "", ""), List.of(), 4);
        check(r.status == 0 && r.leaves == 4000 && r.current.size() == 3000 && r.removed.isEmpty(), "wide: 4000 leaves, the 3000 without a line differ (" + r.leaves + ", " + r.current.size() + ")");
        for (int at : new int[] {500, 2000, 3500}) {
            int[] progress = new int[2];
            h = NativeCheck.start(utf8(wide.toString()), utf8(wideManifest.toString()), joined(List.of("", "", "")), joined(List.of()), 0, 4);
            while ((status = NativeCheck.await(h, 1, progress)) < 0 && progress[0] < at) {}
            NativeCheck.cancel(h);
            while ((status = NativeCheck.await(h, 50, progress)) < 0) {}
            check(status == 2 || status == 0, "wide: cancelled near " + at + " of 4000 (" + status + " at " + progress[0] + ")");
            NativeCheck.free(h);
        }

        System.out.println(failures == 0 ? "all passed" : failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }
}
