/*
 * Checks fdsec's filter from the inside, on an x86_64 Linux host (see test-fdsec.sh):
 *   fdsec-test none          run without fdsec: nothing below may be blocked
 *   fdsec-test base          under `fdsec`
 *   fdsec-test extra noinet  under `fdsec --extra --no-inet` (either word alone also works)
 * Each system call is made with arguments that are harmless whether or not it is blocked.
 * Exits 1 if any result differs from what fdsec should give.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <unistd.h>

static int failures;

static void expect(const char *name, long result, int blocked, int err)
{
	int got = result < 0 ? errno : 0;
	int ok = blocked ? (result < 0 && got == err) : !(result < 0 && got == err);
	printf("%-20s %s (result %ld, errno %d)\n", name, ok ? "ok  " : "FAIL", result, got);
	if (!ok) failures++;
}

static long socket_result(int family)
{
	long fd = socket(family, family == AF_UNIX ? SOCK_STREAM : SOCK_DGRAM, 0);
	if (fd >= 0) close(fd);
	return fd;
}

int main(int argc, char **argv)
{
	int base = 0, extra = 0, noinet = 0;
	for (int i = 1; i < argc; i++) {
		if (!strcmp(argv[i], "base")) base = 1;
		else if (!strcmp(argv[i], "extra")) base = extra = 1;
		else if (!strcmp(argv[i], "noinet")) base = noinet = 1;
		else if (strcmp(argv[i], "none")) { fprintf(stderr, "unknown mode %s\n", argv[i]); return 2; }
	}
	pid_t self = getpid();

	/* PEEKDATA on a process this one doesn't trace: ESRCH without the filter. */
	expect("ptrace", syscall(SYS_ptrace, 2 /* PTRACE_PEEKDATA */, 1, 0, 0), base, EPERM);
	expect("process_vm_readv", syscall(SYS_process_vm_readv, self, 0, 0, 0, 0, 0), base, EPERM);
	expect("process_vm_writev", syscall(SYS_process_vm_writev, self, 0, 0, 0, 0, 0), base, EPERM);
	expect("io_uring_setup", syscall(SYS_io_uring_setup, 0, 0), base, ENOSYS);
	expect("io_uring_enter", syscall(SYS_io_uring_enter, -1, 0, 0, 0, 0, 0), base, ENOSYS);
	expect("io_uring_register", syscall(SYS_io_uring_register, -1, 0, 0, 0), base, ENOSYS);
	expect("openat2", syscall(SYS_openat2, -1, 0, 0, 0), base, ENOSYS);
	expect("open_by_handle_at", syscall(SYS_open_by_handle_at, -1, 0, 0), base, EPERM);
	expect("name_to_handle_at", syscall(SYS_name_to_handle_at, -1, 0, 0, 0, 0), base, EPERM);
	expect("pidfd_getfd", syscall(SYS_pidfd_getfd, -1, 0, 0), base, EPERM);
	expect("bpf", syscall(SYS_bpf, -1, 0, 0), base, EPERM);
	expect("perf_event_open", syscall(SYS_perf_event_open, 0, 0, -1, -1, 0), base, EPERM);
	expect("userfaultfd", syscall(SYS_userfaultfd, -1), base, EPERM);
	expect("x32 getpid", syscall(0x40000000 | SYS_getpid), base, ENOSYS);
	/* Paths proot doesn't translate; a bad file descriptor makes them harmless (EBADF unfiltered). */
	expect("statmount", syscall(457, 0, 0, 0, 0), base, ENOSYS);
	expect("listmount", syscall(458, 0, 0, 0, 0), base, ENOSYS);
	expect("setxattrat", syscall(463, -1, "", 0, 0, 0, 0), base, ENOSYS);
	expect("getxattrat", syscall(464, -1, "", 0, 0, 0, 0), base, ENOSYS);
	expect("listxattrat", syscall(465, -1, "", 0, 0, 0), base, ENOSYS);
	expect("removexattrat", syscall(466, -1, "", 0, 0), base, ENOSYS);
	expect("file_getattr", syscall(468, -1, "", 0, 0, 0), base, ENOSYS);
	expect("file_setattr", syscall(469, -1, "", 0, 0, 0), base, ENOSYS);
	/* open_tree_attr is the last one proot translates, so it stays allowed (EBADF here). */
	expect("open_tree_attr", syscall(467, -1, "", 0, 0, 0), 0, ENOSYS);
	expect("check rule", syscall(SYS_getpriority, 0x66647365, 0), base, ENOTRECOVERABLE);
	expect("getpriority", syscall(SYS_getpriority, 0 /* PRIO_PROCESS */, 0), 0, 0);

	expect("pidfd_open", syscall(SYS_pidfd_open, -1, 0), extra, ENOSYS);
	expect("process_madvise", syscall(SYS_process_madvise, -1, 0, 0, 0, 0), extra, ENOSYS);
	expect("kcmp", syscall(SYS_kcmp, -1, -1, -1, 0, 0), extra, ENOSYS);
	expect("keyctl", syscall(SYS_keyctl, -1, 0, 0, 0, 0), extra, ENOSYS);
	expect("add_key", syscall(SYS_add_key, 0, 0, 0, 0, 0), extra, ENOSYS);
	expect("request_key", syscall(SYS_request_key, 0, 0, 0, 0), extra, ENOSYS);

	expect("socket AF_INET", socket_result(AF_INET), noinet, EACCES);
	expect("socket AF_INET6", socket_result(AF_INET6), noinet, EACCES);
	expect("socket AF_UNIX", socket_result(AF_UNIX), 0, EACCES);
	expect("socket AF_NETLINK", socket_result(AF_NETLINK), 0, EACCES);

	printf("%d failure(s)\n", failures);
	return failures != 0;
}
