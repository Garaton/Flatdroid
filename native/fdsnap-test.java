package com.garathon.flatdroid.alpine.programs;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The JNI function of fdsnap.c as NativeSnapshot.kt declares it (the class name makes its JNI name). */
class NativeSnapshot {
    static native byte[] take(byte[] rootfs, byte[] store, byte[] dirs, int count);
}

/**
 * Checks fdsnap.c on a Linux host (test-fdwalk.sh builds and runs it): Alpine-like folders with a
 * link2symlink hard link, a chain of store links, a store link to a folder, a link out of the
 * store, a name with an emoji, an empty folder, and a folder outside the walk.
 */
public class FdsnapTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    /** stamps: path to (ctime ns, inode, size, mode); binds as (store file, path Alpine's links name). */
    static Map<String, long[]> stamps;
    static List<String[]> binds;

    static boolean take(Path rootfs, String store, List<String> dirs) {
        StringBuilder b = new StringBuilder();
        for (String d : dirs) b.append(d).append('\0');
        byte[] blob = NativeSnapshot.take(utf8(rootfs.toString()), utf8(store), utf8(b.toString()), dirs.size());
        if (blob == null) return false;
        ByteBuffer n = ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder());
        int count = (int) n.getLong(0), bound = (int) n.getLong(8);
        int at = 16 + count * 32;
        List<String> texts = new ArrayList<>();
        while (at < blob.length) { int end = at; while (blob[end] != 0) end++; texts.add(new String(blob, at, end - at, StandardCharsets.UTF_8)); at = end + 1; }
        stamps = new HashMap<>();
        for (int i = 0; i < count; i++) {
            int k = 16 + i * 32;
            stamps.put(texts.get(i), new long[] {n.getLong(k), n.getLong(k + 8), n.getLong(k + 16), n.getLong(k + 24)});
        }
        binds = new ArrayList<>();
        for (int i = 0; i < bound; i++) binds.add(new String[] {texts.get(count + 2 * i + 1), texts.get(count + 2 * i)});
        return texts.size() == count + 2 * bound;
    }

    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        Path rootfs = Files.createTempDirectory("fdsnap");
        Path store = Files.createDirectory(rootfs.resolve(".l2s"));
        Files.createDirectories(rootfs.resolve("usr/bin"));
        Files.createDirectories(rootfs.resolve("usr/empty"));
        Files.write(rootfs.resolve("usr/bin/tool"), utf8("x"));
        Files.createDirectories(rootfs.resolve("etc"));
        Files.write(rootfs.resolve("etc/hosts"), utf8("h"));
        Files.write(rootfs.resolve("etc/é 😀"), utf8("odd"));
        Files.createDirectories(rootfs.resolve("var"));
        Files.write(rootfs.resolve("var/log"), utf8("not walked"));
        // A hard link as link2symlink keeps it: the path names an entry, which names the file.
        Files.write(store.resolve(".l2s.lib0001.0002"), utf8("lib"));
        Files.createSymbolicLink(store.resolve(".l2s.lib0001"), store.resolve(".l2s.lib0001.0002"));
        Files.createDirectories(rootfs.resolve("lib"));
        Files.createSymbolicLink(rootfs.resolve("lib/libx.so"), store.resolve(".l2s.lib0001"));
        Files.createSymbolicLink(rootfs.resolve("lib/libx.so.1"), store.resolve(".l2s.lib0001"));
        // A chain longer than the hops followed, a store entry that is a folder, a link out of the store.
        Path last = Files.write(store.resolve(".l2s.far.0001"), utf8("far"));
        for (int i = 5; i >= 1; i--) last = Files.createSymbolicLink(store.resolve(".l2s.far" + i), last);
        Files.createSymbolicLink(rootfs.resolve("lib/far"), last);
        Files.createDirectory(store.resolve(".l2s.dir"));
        Files.createSymbolicLink(rootfs.resolve("lib/dir"), store.resolve(".l2s.dir"));
        Files.createSymbolicLink(rootfs.resolve("lib/out"), rootfs.resolve("var/log"));
        List<String> dirs = List.of("usr", "lib", "bin", "sbin", "etc");
        String s = store.toString();

        check(take(rootfs, s + "/", dirs), "taken (the store named with a trailing slash)");
        check(stamps.keySet().containsAll(List.of("usr", "usr/bin", "usr/bin/tool", "usr/empty", "etc", "etc/hosts", "etc/é 😀", "lib", "lib/libx.so", "lib/far", "lib/out")),
            "Alpine's folders and their entries are stamped " + stamps.keySet());
        check(stamps.keySet().stream().noneMatch(k -> k.startsWith("var") || k.equals("bin") || k.equals("sbin")), "nothing outside the walk, missing folders left out");
        check(stamps.containsKey(s + "/.l2s.lib0001") && stamps.containsKey(s + "/.l2s.lib0001.0002"), "the store entry and its file are stamped");
        check(binds.size() == 1 && binds.get(0)[0].equals(s + "/.l2s.lib0001.0002") && binds.get(0)[1].equals(s + "/.l2s.lib0001"),
            "one bind: the store's file at the entry's path, for both links to it");
        check(!stamps.containsKey(s + "/.l2s.far.0001") && stamps.containsKey(s + "/.l2s.far1"), "a long chain is followed four links, no further");
        check(stamps.containsKey(s + "/.l2s.dir") && binds.stream().noneMatch(x -> x[1].endsWith(".l2s.dir")), "a store folder is stamped, not bound");
        check(stamps.get("usr/bin/tool")[2] == 1 && (stamps.get("usr/bin/tool")[3] & 0xF000) == 0x8000, "a file's size and mode");

        long[] before = stamps.get("usr/bin");
        Files.write(rootfs.resolve("usr/bin/new"), utf8("n"));
        take(rootfs, s, dirs);
        check(stamps.containsKey("usr/bin/new") && stamps.get("usr/bin")[0] != before[0], "a new file, and its folder's ctime moved");

        check(take(rootfs.resolve("missing"), s, dirs) && stamps.isEmpty() && binds.isEmpty(), "a missing root: nothing");
        check(NativeSnapshot.take(utf8(rootfs + "\0x"), utf8(s), utf8("usr\0"), 1) == null, "a path with a NUL is refused");

        System.out.println(failures == 0 ? "all passed" : failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }
}
