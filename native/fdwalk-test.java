package com.garathon.flatdroid.util.io;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** The JNI functions of fdwalk.c as NativeFs.kt declares them (the class name makes their JNI names). */
class NativeFs {
    static native byte[] list(byte[] dir);
    static native byte[] readlinks(byte[] dir, byte[] names, int[] starts, int count);
    static native boolean stats(byte[] dir, byte[] names, int[] starts, int count, boolean follow, long[] out);
}

/** Checks fdwalk.c on a Linux host (test-fdwalk.sh builds and runs it). */
public class FdwalkTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    static byte[] joined(List<String> names) {
        StringBuilder b = new StringBuilder();
        for (String n : names) b.append(n).append('\0');
        return utf8(b.toString());
    }

    /** Where each name starts in joined(names). */
    static int[] starts(List<String> names) {
        int[] at = new int[names.size()];
        int p = 0;
        for (int i = 0; i < names.size(); i++) { at[i] = p; p += utf8(names.get(i)).length + 1; }
        return at;
    }

    /** [type, name] pairs from list()'s blob. */
    static List<String[]> entries(byte[] blob) {
        List<String[]> out = new ArrayList<>();
        int i = 0;
        while (i < blob.length) {
            int end = i + 1;
            while (blob[end] != 0) end++;
            out.add(new String[]{String.valueOf(blob[i]), new String(blob, i + 1, end - i - 1, StandardCharsets.UTF_8)});
            i = end + 1;
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        System.load(new File(args[0]).getAbsolutePath());
        File root = Files.createTempDirectory("fdwalk").toFile();
        File sub = new File(root, "sub");
        sub.mkdir();
        String odd = "naïve 😀\ttab";
        Files.write(new File(root, odd).toPath(), utf8("content"));
        Files.write(new File(sub, "inner").toPath(), new byte[5000]);
        Files.createSymbolicLink(new File(root, "link").toPath(), new File(root, odd).toPath());
        Files.createSymbolicLink(new File(root, "dangling").toPath(), new File(root, "gone").toPath());

        List<String[]> listed = entries(NativeFs.list(utf8(root.getPath())));
        check(listed.size() == 4, "list: four entries, no . or .. (" + listed.size() + ")");
        boolean types = true, oddSeen = false;
        for (String[] e : listed) {
            String want = e[1].equals("sub") ? "1" : e[1].equals("link") || e[1].equals("dangling") ? "2" : "3";
            types &= e[0].equals(want);
            oddSeen |= e[1].equals(odd);
        }
        check(types, "list: types (directory 1, link 2, file 3)");
        check(oddSeen, "list: a name with an emoji, an accent and a tab comes back as it is");
        check(NativeFs.list(utf8(new File(root, "missing").getPath())) == null, "list: a missing folder is null");
        check(NativeFs.list(utf8(new File(root, odd).getPath())) == null, "list: a file is null");

        List<String> names = List.of("link", odd, "dangling", "sub/inner", "missing");
        byte[] links = NativeFs.readlinks(utf8(root.getPath()), joined(names), starts(names), names.size());
        List<String[]> read = entries(links);
        check(read.size() == 5, "readlinks: one answer per name");
        check(read.get(0)[0].equals("1") && read.get(0)[1].equals(new File(root, odd).getPath()), "readlinks: a link's target, with its odd name");
        check(read.get(1)[0].equals("0"), "readlinks: a file isn't a link");
        check(read.get(2)[0].equals("1") && read.get(2)[1].endsWith("/gone"), "readlinks: a dangling link still reads");
        check(read.get(3)[0].equals("0") && read.get(4)[0].equals("0"), "readlinks: a path below the folder, and a missing name");

        long[] out = new long[names.size() * 5];
        check(NativeFs.stats(utf8(root.getPath()), joined(names), starts(names), names.size(), false, out), "stats: done");
        long linkMode = out[3], fileMode = out[8], innerSize = out[17];
        check((linkMode & 0xF000) == 0xA000, "stats: a link without following is a link");
        check((fileMode & 0xF000) == 0x8000 && out[7] == 7, "stats: a file's mode and size");
        check(innerSize == 5000 && out[19] >= 4096, "stats: a path below the folder, size and allocated bytes");
        check(out[23] == -1, "stats: a missing name has mode -1");
        check(out[1] > 1_000_000_000L * 1_000_000_000L, "stats: change time in nanoseconds");
        long[] followed = new long[names.size() * 5];
        NativeFs.stats(utf8(root.getPath()), joined(names), starts(names), names.size(), true, followed);
        check((followed[3] & 0xF000) == 0x8000 && followed[0] == out[5], "stats: following a link gives its file");
        check(followed[13] == -1, "stats: following a dangling link fails");
        check(!NativeFs.stats(utf8(root.getPath()), joined(names), starts(names), names.size(), false, new long[3]), "stats: refuses a short array");
        check(NativeFs.readlinks(utf8(root.getPath()), joined(names), new int[]{0, 999999}, 2) == null, "readlinks: refuses an offset outside the names");
        check(NativeFs.readlinks(utf8(new File(root, "missing").getPath()), joined(names), starts(names), names.size()) == null, "readlinks: a missing folder is null");

        System.out.println(failures == 0 ? "all passed" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
