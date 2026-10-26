/*
 * fdsec: installs Flatdroid's seccomp filter on itself, checks that it is in effect, then
 * executes a command, which inherits the filter and can't remove it.
 *
 *   fdsec [--extra] [--no-inet] [--] /absolute/command args...
 *
 * Always blocked: io_uring and openat2 (ENOSYS, so programs fall back to what proot
 * translates), open_by_handle_at, name_to_handle_at, pidfd_getfd, bpf, perf_event_open,
 * userfaultfd, ptrace and process_vm_readv/writev (EPERM); system calls of other ABIs (i386 and
 * x32 on x86_64, AArch32 on aarch64) get ENOSYS.
 *   --extra    also pidfd_open, process_madvise, kcmp, keyctl, add_key, request_key (ENOSYS)
 *   --no-inet  socket(AF_INET or AF_INET6) fails with EACCES; Unix and netlink sockets stay
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

#define AF_INET 2
#define AF_INET6 10
#define CHECK_WHICH 0x66647365 /* "fdse" */
#define CHECK_ERRNO ENOTRECOVERABLE
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
};

static const struct rule EXTRA[] = {
	{ __NR_pidfd_open, ENOSYS }, { __NR_process_madvise, ENOSYS }, { __NR_kcmp, ENOSYS },
	{ __NR_keyctl, ENOSYS }, { __NR_add_key, ENOSYS }, { __NR_request_key, ENOSYS },
};

/* Big enough for every rule build() can emit; the _Static_assert below fails the build if a
 * future rule would overflow the stack array. */
#define MAX_FILTER 96
/* base (arch check, nr load) + x32 guard + ALWAYS + EXTRA + the no_inet block + the check rule. */
_Static_assert(5 + 2 + 2 * (sizeof ALWAYS / sizeof *ALWAYS) + 2 * (sizeof EXTRA / sizeof *EXTRA) + 7 + 5 <= MAX_FILTER,
               "fdsec filter would overflow MAX_FILTER");
#define LOAD(field) BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, field))
/* The low 32 bits of argument i (seccomp_data is little-endian on both architectures). */
#define LOAD_ARG(i) BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args) + 8 * (i))
#define RETURN(action) BPF_STMT(BPF_RET | BPF_K, (action))
#define IF_EQUAL(k, skip_if_not) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (k), 0, (skip_if_not))

static unsigned build(struct sock_filter *f, int extra, int no_inet)
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
	if (no_inet) {
		f[n++] = (struct sock_filter)IF_EQUAL(__NR_socket, 6);
		f[n++] = (struct sock_filter)LOAD_ARG(0);
		f[n++] = (struct sock_filter)IF_EQUAL(AF_INET, 1);
		f[n++] = (struct sock_filter)RETURN(SECCOMP_RET_ERRNO | EACCES);
		f[n++] = (struct sock_filter)IF_EQUAL(AF_INET6, 1);
		f[n++] = (struct sock_filter)RETURN(SECCOMP_RET_ERRNO | EACCES);
		/* Another socket: the accumulator holds the argument now, so allow it here. */
		f[n++] = (struct sock_filter)RETURN(SECCOMP_RET_ALLOW);
	}
	/* The check rule, last; getpriority is otherwise allowed. */
	f[n++] = (struct sock_filter)IF_EQUAL(__NR_getpriority, 3);
	f[n++] = (struct sock_filter)LOAD_ARG(0);
	f[n++] = (struct sock_filter)IF_EQUAL(CHECK_WHICH, 1);
	f[n++] = (struct sock_filter)RETURN(SECCOMP_RET_ERRNO | CHECK_ERRNO);
	f[n++] = (struct sock_filter)RETURN(SECCOMP_RET_ALLOW);
	return n;
}

/* ---- entry */

__attribute__((used)) static void start(long *sp)
{
	long argc = sp[0];
	char **argv = (char **)(sp + 1);
	char **envp = argv + argc + 1;
	int extra = 0, no_inet = 0;
	int i = 1;
	for (; i < argc && argv[i][0] == '-'; i++) {
		if (equal(argv[i], "--extra")) extra = 1;
		else if (equal(argv[i], "--no-inet")) no_inet = 1;
		else if (equal(argv[i], "--")) { i++; break; }
		else { say("fdsec: unknown option ", argv[i]); leave(EXIT_USAGE); }
	}
	if (i >= argc || argv[i][0] != '/') {
		say("fdsec: usage: fdsec [--extra] [--no-inet] [--] /absolute/command args...", 0);
		leave(EXIT_USAGE);
	}

	struct sock_filter filter[MAX_FILTER];
	struct sock_fprog prog = { .len = (unsigned short)build(filter, extra, no_inet), .filter = filter };
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
