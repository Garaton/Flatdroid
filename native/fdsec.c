/*
 * fdsec: installs Flatdroid's seccomp filter on itself, checks that it is in effect, then
 * executes a command, which inherits the filter and can't remove it.
 *
 *   fdsec [--extra] [--no-inet] [--no-connect] [--tty /dev/pts/N] [--] /absolute/command args...
 *   fdsec [filter options] --hold-tty FILE
 *   fdsec --no-filter --tty /dev/pts/N [--] /absolute/command args...
 *
 * Always blocked: io_uring and openat2 (ENOSYS, so programs fall back to what proot
 * translates), fanotify_init, fanotify_mark and quotactl (ENOSYS: their paths aren't translated
 * either), open_by_handle_at, name_to_handle_at, pidfd_getfd, bpf, perf_event_open,
 * userfaultfd, ptrace and process_vm_readv/writev (EPERM); statmount, listmount, the *xattrat
 * calls, file_getattr and file_setattr, and every system call numbered above open_tree_attr
 * (ENOSYS): they take paths proot doesn't translate (its table ends at open_tree_attr), so they
 * would reach the host's files directly; later ones fail closed until proot knows them. System
 * calls of other ABIs (i386 and x32 on x86_64, AArch32 on aarch64) get ENOSYS. socket takes
 * only AF_UNIX, AF_NETLINK, AF_INET and AF_INET6 (the families Flatpak allows), any other
 * family fails with EAFNOSUPPORT: some (AF_SMC, AF_VSOCK, AF_PACKET) reach beyond the device.
 *   --extra    also pidfd_open, process_madvise, kcmp, keyctl, add_key, request_key (ENOSYS)
 *   --no-inet  socket(AF_INET or AF_INET6) fails with EACCES; Unix and netlink sockets stay
 *   --no-connect  connect fails with EACCES, for a server that only accepts connections
 *
 * A terminal app's terminal, held by a terminal emulator in another root that shares /dev/pts:
 *   --hold-tty FILE  run as the emulator's command (which must stay open after it exits): leaves the
 *                    terminal to a child and exits, which releases it as controlling terminal (a
 *                    terminal belongs to one session); the child writes the terminal's name to FILE
 *                    (whole, through a rename) and keeps it open until it hangs up. No command. A
 *                    failure is also written to FILE.error.
 *   --tty PATH       a new session, with PATH (under /dev/pts/) as its controlling terminal and as
 *                    standard input, output and error, before the command is executed.
 *   --no-filter      only with --tty and no filter option: the terminal without the filter, for a
 *                    shell whose user turned the filter off (debuggers need ptrace). Nothing is
 *                    removed from a filter the caller already has.
 *
 * Kernels before 5.8 have no faccessat2. Android 8 and later trap it with SIGSYS, and proot
 * answers the trap with faccessat; without that filter (Android 7) the kernel's ENOSYS reaches the
 * program, and musl then fails AT_SYMLINK_NOFOLLOW with EINVAL (GLib takes the file as missing).
 * When faccessat2 answers ENOSYS here, the filter traps it the same way.
 *
 * Kernels before 3.17 have no memfd, and so no seals: there apps make their shared memory through
 * libfdmemfd.so's fallback file, and glycin (GNOME's image loader) seals it with raw system calls
 * and gives up when that fails. When memfd_create answers ENOSYS here, fcntl's F_ADD_SEALS and
 * F_GET_SEALS succeed without doing anything (no seals: nothing was sealed before either).
 *
 * The check: a rule only this filter has answers getpriority(CHECK_WHICH, 0) with CHECK_ERRNO
 * (the kernel itself would say EINVAL); where the kernel reports Seccomp_filters (5.9 and
 * later), the count must also have gone up by one. If either fails, nothing is executed and a
 * line starting with "fdsec: refused" is printed.
 *
 * No C library: the same static binary then runs in any root (glibc runtimes, Alpine's musl),
 * and stays a few KB (a static bionic binary is about 900 KB). It runs only once Flatdroid has
 * built its arguments, and reads nothing else but /proc/self/status.
 */
#include <asm/ioctls.h>
#include <asm/signal.h>
#include <asm/unistd.h>
#include <linux/audit.h>
#include <linux/errno.h>
#include <linux/fcntl.h>
#include <linux/filter.h>
#include <linux/prctl.h>
#include <linux/seccomp.h>
#include <stddef.h>

#if defined(__aarch64__)
#define NATIVE_ARCH AUDIT_ARCH_AARCH64
#elif defined(__x86_64__)
#define NATIVE_ARCH AUDIT_ARCH_X86_64
#else
#error "unsupported architecture"
#endif

/* Since Linux 5.1 new system calls have the same number on every architecture; older kernel
 * headers (the NDK's) may not name these yet. */
#ifndef __NR_faccessat2
#define __NR_faccessat2 439
#endif
#ifndef __NR_statmount
#define __NR_statmount 457
#endif
#ifndef __NR_listmount
#define __NR_listmount 458
#endif
#ifndef __NR_setxattrat
#define __NR_setxattrat 463
#endif
#ifndef __NR_getxattrat
#define __NR_getxattrat 464
#endif
#ifndef __NR_listxattrat
#define __NR_listxattrat 465
#endif
#ifndef __NR_removexattrat
#define __NR_removexattrat 466
#endif
/* The highest system call proot translates (open_tree_attr); everything above gets ENOSYS. */
#define LAST_KNOWN_NR 467

#define AF_UNIX 1
#define AF_INET 2
#define AF_INET6 10
#define AF_NETLINK 16
#define CHECK_WHICH 0x66647365 /* "fdse" */
#define CHECK_ERRNO ENOTRECOVERABLE
#define EXIT_TTY 124
#define EXIT_REFUSED 125
#define EXIT_USAGE 126
#define EXIT_EXEC 127

/* ---- system calls */

static long sys6(long n, long a, long b, long c, long d, long e, long f)
{
#if defined(__aarch64__)
	register long x8 __asm__("x8") = n;
	register long x0 __asm__("x0") = a;
	register long x1 __asm__("x1") = b;
	register long x2 __asm__("x2") = c;
	register long x3 __asm__("x3") = d;
	register long x4 __asm__("x4") = e;
	register long x5 __asm__("x5") = f;
	__asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5) : "memory");
	return x0;
#else
	register long r10 __asm__("r10") = d;
	register long r8 __asm__("r8") = e;
	register long r9 __asm__("r9") = f;
	long ret;
	__asm__ volatile("syscall" : "=a"(ret) : "a"(n), "D"(a), "S"(b), "d"(c), "r"(r10), "r"(r8), "r"(r9)
	                 : "rcx", "r11", "memory");
	return ret;
#endif
}

#define sys3(n, a, b, c) sys6((n), (long)(a), (long)(b), (long)(c), 0, 0, 0)

static unsigned long length(const char *s)
{
	unsigned long n = 0;
	while (s[n]) n++;
	return n;
}

static int equal(const char *a, const char *b)
{
	while (*a && *a == *b) a++, b++;
	return *a == *b;
}

static int starts_with(const char *s, const char *prefix)
{
	while (*prefix && *s == *prefix) s++, prefix++;
	return !*prefix;
}

static void say(const char *a, const char *b)
{
	sys3(__NR_write, 2, a, length(a));
	if (b) sys3(__NR_write, 2, b, length(b));
	sys3(__NR_write, 2, "\n", 1);
}

static __attribute__((noreturn)) void leave(int code)
{
	sys3(__NR_exit_group, code, 0, 0);
	__builtin_trap();
}

/* The Seccomp_filters count in /proc/self/status, or -1 where the kernel has no such line (or
 * /proc isn't there). */
static long filter_count(void)
{
	static const char key[] = "\nSeccomp_filters:";
	char buf[8192];
	long fd = sys6(__NR_openat, AT_FDCWD, (long)"/proc/self/status", O_RDONLY | O_CLOEXEC, 0, 0, 0);
	if (fd < 0) return -1;
	unsigned long used = 0;
	for (;;) {
		long n = sys3(__NR_read, fd, buf + used, sizeof buf - 1 - used);
		if (n <= 0) break;
		used += n;
		if (used == sizeof buf - 1) break;
	}
	sys3(__NR_close, fd, 0, 0);
	buf[used] = 0;
	for (unsigned long i = 0; i + sizeof key - 1 <= used; i++) {
		unsigned long k = 0;
		while (key[k] && buf[i + k] == key[k]) k++;
		if (key[k]) continue;
		const char *p = buf + i + k;
		while (*p == ' ' || *p == '\t') p++;
		if (*p < '0' || *p > '9') return -1;
		long count = 0;
		while (*p >= '0' && *p <= '9') count = count * 10 + (*p++ - '0');
		return count;
	}
	return -1;
}

/* ---- the filter */

struct rule { int nr; int err; };

static const struct rule ALWAYS[] = {
	{ __NR_io_uring_setup, ENOSYS }, { __NR_io_uring_enter, ENOSYS }, { __NR_io_uring_register, ENOSYS },
	{ __NR_openat2, ENOSYS },
	{ __NR_open_by_handle_at, EPERM }, { __NR_name_to_handle_at, EPERM },
	{ __NR_pidfd_getfd, EPERM },
	{ __NR_bpf, EPERM },
	{ __NR_perf_event_open, EPERM },
	{ __NR_userfaultfd, EPERM },
	{ __NR_ptrace, EPERM },
	{ __NR_process_vm_readv, EPERM }, { __NR_process_vm_writev, EPERM },
	{ __NR_statmount, ENOSYS }, { __NR_listmount, ENOSYS },
	{ __NR_setxattrat, ENOSYS }, { __NR_getxattrat, ENOSYS }, { __NR_listxattrat, ENOSYS }, { __NR_removexattrat, ENOSYS },
	/* Paths that proot's enter.c doesn't translate (like the *xattrat calls above). */
	{ __NR_fanotify_init, ENOSYS }, { __NR_fanotify_mark, ENOSYS }, { __NR_quotactl, ENOSYS },
};

/* The socket families that stay open; --no-inet drops the last two. */
static const int FAMILIES[] = { AF_UNIX, AF_NETLINK, AF_INET, AF_INET6 };

static const struct rule EXTRA[] = {
	{ __NR_pidfd_open, ENOSYS }, { __NR_process_madvise, ENOSYS }, { __NR_kcmp, ENOSYS },
	{ __NR_keyctl, ENOSYS }, { __NR_add_key, ENOSYS }, { __NR_request_key, ENOSYS },
};

/* Big enough for every rule build() can emit; the _Static_assert below fails the build if a
 * future rule would overflow the stack array. */
#define MAX_FILTER 96
/* base (arch check, nr load) + x32 guard + newer-than-proot guard + the faccessat2 trap + the seal
 * block + ALWAYS + EXTRA + the no_connect block + the socket block (test, argument load, two EACCES
 * rules for --no-inet, the families, EAFNOSUPPORT) + the check rule. */
_Static_assert(5 + 2 + 2 + 2 + 6 + 2 * (sizeof ALWAYS / sizeof *ALWAYS) + 2 * (sizeof EXTRA / sizeof *EXTRA) + 2
                   + (2 + 4 + 2 * (sizeof FAMILIES / sizeof *FAMILIES) + 1) + 5 <= MAX_FILTER,
               "fdsec filter would overflow MAX_FILTER");
#define LOAD(field) BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, field))
/* The low 32 bits of argument i (seccomp_data is little-endian on both architectures). */
#define LOAD_ARG(i) BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args) + 8 * (i))
#define RETURN(action) BPF_STMT(BPF_RET | BPF_K, (action))
#define IF_EQUAL(k, skip_if_not) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (k), 0, (skip_if_not))

static unsigned build(struct sock_filter *f, int extra, int no_inet, int no_connect, int trap_faccessat2, int answer_seals)
{
	unsigned n = 0;
	f[n++] = (struct sock_filter)LOAD(arch);
	f[n++] = (struct sock_filter)IF_EQUAL(NATIVE_ARCH, 1);
	f[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JA, 1, 0, 0);
	f[n++] = (struct sock_filter)RETURN(SECCOMP_RET_ERRNO | ENOSYS);
	f[n++] = (struct sock_filter)LOAD(nr);
#if defined(__x86_64__)
	f[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, __X32_SYSCALL_BIT, 0, 1);
	f[n++] = (struct sock_filter)RETURN(SECCOMP_RET_ERRNO | ENOSYS);
#endif
	f[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JGT | BPF_K, LAST_KNOWN_NR, 0, 1);
	f[n++] = (struct sock_filter)RETURN(SECCOMP_RET_ERRNO | ENOSYS);
	if (trap_faccessat2) {
		f[n++] = (struct sock_filter)IF_EQUAL(__NR_faccessat2, 1);
		f[n++] = (struct sock_filter)RETURN(SECCOMP_RET_TRAP);
	}
	/* fcntl's seal commands succeed without effect; every other fcntl is allowed (no later rule
	 * names it). The argument load clobbers the accumulator, so every path out returns. */
	if (answer_seals) {
		f[n++] = (struct sock_filter)IF_EQUAL(__NR_fcntl, 5);
		f[n++] = (struct sock_filter)LOAD_ARG(1);
		f[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, F_ADD_SEALS, 1, 0);
		f[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, F_GET_SEALS, 0, 1);
		f[n++] = (struct sock_filter)RETURN(SECCOMP_RET_ERRNO | 0);
		f[n++] = (struct sock_filter)RETURN(SECCOMP_RET_ALLOW);
	}
	for (unsigned i = 0; i < sizeof ALWAYS / sizeof *ALWAYS; i++) {
		f[n++] = (struct sock_filter)IF_EQUAL(ALWAYS[i].nr, 1);
		f[n++] = (struct sock_filter)RETURN(SECCOMP_RET_ERRNO | ALWAYS[i].err);
	}
	if (extra) {
		for (unsigned i = 0; i < sizeof EXTRA / sizeof *EXTRA; i++) {
			f[n++] = (struct sock_filter)IF_EQUAL(EXTRA[i].nr, 1);
			f[n++] = (struct sock_filter)RETURN(SECCOMP_RET_ERRNO | EXTRA[i].err);
		}
	}
	if (no_connect) {
		f[n++] = (struct sock_filter)IF_EQUAL(__NR_connect, 1);
		f[n++] = (struct sock_filter)RETURN(SECCOMP_RET_ERRNO | EACCES);
	}
	/* socket: only the allowed families (without the two Internet ones for --no-inet, which
	 * fail with EACCES), the rest EAFNOSUPPORT. The argument load clobbers the accumulator, so
	 * every path out of this block returns. */
	unsigned open = (unsigned)(sizeof FAMILIES / sizeof *FAMILIES) - (no_inet ? 2 : 0);
	f[n++] = (struct sock_filter)IF_EQUAL(__NR_socket, 1 + (no_inet ? 4 : 0) + 2 * open + 1);
	f[n++] = (struct sock_filter)LOAD_ARG(0);
	if (no_inet) {
		f[n++] = (struct sock_filter)IF_EQUAL(AF_INET, 1);
		f[n++] = (struct sock_filter)RETURN(SECCOMP_RET_ERRNO | EACCES);
		f[n++] = (struct sock_filter)IF_EQUAL(AF_INET6, 1);
		f[n++] = (struct sock_filter)RETURN(SECCOMP_RET_ERRNO | EACCES);
	}
	for (unsigned i = 0; i < open; i++) {
		f[n++] = (struct sock_filter)IF_EQUAL(FAMILIES[i], 1);
		f[n++] = (struct sock_filter)RETURN(SECCOMP_RET_ALLOW);
	}
	f[n++] = (struct sock_filter)RETURN(SECCOMP_RET_ERRNO | EAFNOSUPPORT);
	/* The check rule, last; getpriority is otherwise allowed. */
	f[n++] = (struct sock_filter)IF_EQUAL(__NR_getpriority, 3);
	f[n++] = (struct sock_filter)LOAD_ARG(0);
	f[n++] = (struct sock_filter)IF_EQUAL(CHECK_WHICH, 1);
	f[n++] = (struct sock_filter)RETURN(SECCOMP_RET_ERRNO | CHECK_ERRNO);
	f[n++] = (struct sock_filter)RETURN(SECCOMP_RET_ALLOW);
	return n;
}

/* ---- the terminal */

#define PTS_PREFIX "/dev/pts/"
#define PATH_MAX_LEN 4096
#define POLLHUP_ERR_NVAL (0x010 | 0x008 | 0x020)

/* The kernel's sigaction (not the C library's). */
struct kernel_sigaction { void *handler; unsigned long flags; void *restorer; unsigned long mask; };
struct kernel_timespec { long sec; long nsec; };
struct kernel_pollfd { int fd; short events; short revents; };

/* --hold-tty's FILE, where a failure is also written (its output is the terminal nobody may see yet). */
static const char *hold_file;

static void set_hangup(void *handler)
{
	struct kernel_sigaction action = { .handler = handler };
	sys6(__NR_rt_sigaction, SIGHUP, (long)&action, 0, sizeof action.mask, 0, 0);
}

/* [file] with [suffix] in [out] (PATH_MAX_LEN), or 0 when too long. */
static char *suffixed(const char *file, const char *suffix, char *out)
{
	unsigned long len = length(file), more = length(suffix);
	if (len + more + 1 > PATH_MAX_LEN) return 0;
	for (unsigned long i = 0; i < len; i++) out[i] = file[i];
	for (unsigned long i = 0; i <= more; i++) out[len + i] = suffix[i];
	return out;
}

/* Writes [text] to [file] whole: to FILE.new, then renamed. */
static int write_whole(const char *file, const char *text, unsigned long n)
{
	char partial[PATH_MAX_LEN];
	if (!suffixed(file, ".new", partial)) return -1;
	long fd = sys6(__NR_openat, AT_FDCWD, (long)partial, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600, 0, 0);
	if (fd < 0) return -1;
	long written = sys3(__NR_write, fd, text, n);
	sys3(__NR_close, fd, 0, 0);
	if (written != (long)n) return -1;
	return sys6(__NR_renameat2, AT_FDCWD, (long)partial, AT_FDCWD, (long)file, 0, 0) == 0 ? 0 : -1;
}

static __attribute__((noreturn)) void tty_failed(const char *what, const char *path)
{
	say(what, path);
	char error[PATH_MAX_LEN], text[512];
	if (hold_file && suffixed(hold_file, ".error", error)) {
		unsigned long n = 0;
		for (const char *p = what; *p && n < sizeof text - 1; p++) text[n++] = *p;
		for (const char *p = path ? path : ""; *p && n < sizeof text - 1; p++) text[n++] = *p;
		write_whole(error, text, n);
	}
	leave(EXIT_TTY);
}

static void nap(long ms)
{
	struct kernel_timespec ts = { 0, ms * 1000000L };
	sys6(__NR_ppoll, 0, 0, (long)&ts, 0, 0, 0);
}

/*
 * --hold-tty: the emulator's end, which never returns. Its process leads the terminal's session;
 * a child, in the same session, keeps the terminal open while the leader exits, which releases the
 * terminal as anyone's controlling terminal (the ioctl that would release it, TIOCNOTTY, Android
 * refuses to apps). The child then names it and waits until the terminal hangs up (the emulator
 * closed). The emulator must stay open when its first process exits (foot's --hold).
 */
static __attribute__((noreturn)) void hold_tty(const char *file)
{
	hold_file = file;
	char name[256];
	long n = sys6(__NR_readlinkat, AT_FDCWD, (long)"/proc/self/fd/0", (long)name, sizeof name - 1, 0, 0);
	if (n <= 0) tty_failed("fdsec: no terminal on standard input", 0);
	name[n] = 0;
	if (!starts_with(name, PTS_PREFIX)) tty_failed("fdsec: standard input is not a pseudo-terminal: ", name);
	/* The leader's exit hangs up the terminal's foreground process group: the child's too. */
	set_hangup(SIG_IGN);
	long leader = sys3(__NR_getpid, 0, 0, 0);
	long child = sys6(__NR_clone, SIGCHLD, 0, 0, 0, 0, 0);
	if (child < 0) tty_failed("fdsec: could not start the terminal's holder for ", name);
	if (child > 0) leave(0);
	/* Once the leader is gone (its child reparented), the terminal is released. */
	for (int i = 0; sys3(__NR_getppid, 0, 0, 0) == leader; i++) {
		if (i > 500) tty_failed("fdsec: the terminal's session leader did not end: ", name);
		nap(10);
	}
	set_hangup(SIG_DFL);
	if (write_whole(file, name, n) != 0) tty_failed("fdsec: cannot write ", file);
	struct kernel_pollfd terminal = { 0, 0, 0 };
	for (;;) {
		if (sys6(__NR_ppoll, (long)&terminal, 1, 0, 0, 0, 0) > 0 && (terminal.revents & POLLHUP_ERR_NVAL)) leave(0);
	}
}

/* The controlling terminal's device number (/proc/self/stat's tty_nr), 0 for none, -1 unreadable. */
static long controlling_tty(void)
{
	char buf[1024];
	long fd = sys6(__NR_openat, AT_FDCWD, (long)"/proc/self/stat", O_RDONLY | O_CLOEXEC, 0, 0, 0);
	if (fd < 0) return -1;
	long n = sys3(__NR_read, fd, buf, sizeof buf - 1);
	sys3(__NR_close, fd, 0, 0);
	if (n <= 0) return -1;
	buf[n] = 0;
	/* After the name's closing parenthesis: state, ppid, pgrp, session, tty_nr. */
	long close = -1;
	for (long i = 0; i < n; i++) if (buf[i] == ')') close = i;
	if (close < 0) return -1;
	const char *p = buf + close + 1;
	for (int field = 0; field < 4; field++) {
		while (*p == ' ') p++;
		while (*p && *p != ' ') p++;
	}
	while (*p == ' ') p++;
	long value = 0;
	if (*p < '0' || *p > '9') return -1;
	while (*p >= '0' && *p <= '9') value = value * 10 + (*p++ - '0');
	return value;
}

/*
 * --tty: the app's end. A session leader opening a terminal without O_NOCTTY makes it its
 * controlling terminal; TIOCSCTTY is asked too, where allowed, and the result is checked in /proc.
 */
static void take_tty(const char *path)
{
	if (!starts_with(path, PTS_PREFIX)) tty_failed("fdsec: not a pseudo-terminal: ", path);
	if (sys3(__NR_setsid, 0, 0, 0) < 0) tty_failed("fdsec: could not start a session for ", path);
	long fd = sys6(__NR_openat, AT_FDCWD, (long)path, O_RDWR, 0, 0, 0);
	if (fd < 0) tty_failed("fdsec: cannot open ", path);
	sys3(__NR_ioctl, fd, TIOCSCTTY, 0);
	if (controlling_tty() == 0) tty_failed("fdsec: could not take the terminal ", path);
	for (int std = 0; std < 3; std++) {
		if (fd != std && sys3(__NR_dup3, fd, std, 0) < 0) tty_failed("fdsec: could not use the terminal ", path);
	}
	if (fd > 2) sys3(__NR_close, fd, 0, 0);
}

/* ---- entry */

__attribute__((used)) static void start(long *sp)
{
	long argc = sp[0];
	char **argv = (char **)(sp + 1);
	char **envp = argv + argc + 1;
	int extra = 0, no_inet = 0, no_connect = 0, no_filter = 0;
	const char *tty = 0, *hold = 0;
	int i = 1;
	for (; i < argc && argv[i][0] == '-'; i++) {
		if (equal(argv[i], "--extra")) extra = 1;
		else if (equal(argv[i], "--no-inet")) no_inet = 1;
		else if (equal(argv[i], "--no-connect")) no_connect = 1;
		else if (equal(argv[i], "--no-filter")) no_filter = 1;
		else if (equal(argv[i], "--tty") && i + 1 < argc) tty = argv[++i];
		else if (equal(argv[i], "--hold-tty") && i + 1 < argc) hold = argv[++i];
		else if (equal(argv[i], "--")) { i++; break; }
		else { say("fdsec: unknown option ", argv[i]); leave(EXIT_USAGE); }
	}
	if ((hold ? (tty || i < argc) : (i >= argc || argv[i][0] != '/')) ||
	    (no_filter && (!tty || hold || extra || no_inet || no_connect))) {
		say("fdsec: usage: fdsec [--extra] [--no-inet] [--no-connect] [--tty /dev/pts/N] [--] /absolute/command args..."
		    " | fdsec [options] --hold-tty FILE | fdsec --no-filter --tty /dev/pts/N [--] /absolute/command args...", 0);
		leave(EXIT_USAGE);
	}
	if (no_filter) {
		take_tty(tty);
		sys3(__NR_execve, argv[i], argv + i, envp);
		say("fdsec: cannot execute ", argv[i]);
		leave(EXIT_EXEC);
	}

	/* Asked before the filter exists: under Android's own filter proot already answers it. */
	int trap_faccessat2 = sys6(__NR_faccessat2, AT_FDCWD, (long)"/", 0 /* F_OK */, 0, 0, 0) == -ENOSYS;
	long memfd = sys3(__NR_memfd_create, (long)"fdsec", 1 /* MFD_CLOEXEC */, 0);
	if (memfd >= 0) sys3(__NR_close, memfd, 0, 0);
	int answer_seals = memfd == -ENOSYS;
	struct sock_filter filter[MAX_FILTER];
	struct sock_fprog prog = { .len = (unsigned short)build(filter, extra, no_inet, no_connect, trap_faccessat2, answer_seals), .filter = filter };
	long before = filter_count();
	if (sys3(__NR_prctl, PR_SET_NO_NEW_PRIVS, 1, 0) != 0) {
		say("fdsec: refused: prctl(PR_SET_NO_NEW_PRIVS) failed", 0);
		leave(EXIT_REFUSED);
	}
	if (sys3(__NR_prctl, PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) != 0) {
		say("fdsec: refused: the filter could not be installed", 0);
		leave(EXIT_REFUSED);
	}
	if (sys3(__NR_getpriority, CHECK_WHICH, 0, 0) != -CHECK_ERRNO) {
		say("fdsec: refused: the filter is not in effect", 0);
		leave(EXIT_REFUSED);
	}
	long after = filter_count();
	if (before >= 0 && after != before + 1) {
		say("fdsec: refused: Seccomp_filters did not count the filter", 0);
		leave(EXIT_REFUSED);
	}

	if (hold) hold_tty(hold);
	if (tty) take_tty(tty);
	sys3(__NR_execve, argv[i], argv + i, envp);
	say("fdsec: cannot execute ", argv[i]);
	leave(EXIT_EXEC);
}

/* The kernel (or proot's loader) enters here with the stack pointer on argc. */
#if defined(__aarch64__)
__asm__(".text\n.global _start\n.type _start,%function\n_start:\n"
        "hint #34\n"            /* bti c */
        "mov x29, #0\nmov x30, #0\nmov x0, sp\nbl start\nbrk #0\n");
#else
__asm__(".text\n.global _start\n.type _start,@function\n_start:\n"
        "endbr64\n"
        "xor %rbp, %rbp\nmov %rsp, %rdi\nand $-16, %rsp\ncall start\nhlt\n");
#endif
