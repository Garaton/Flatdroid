/*
 * Checks fdsec's terminal steps on a Linux host (see test-fdsec.sh): plays the terminal emulator
 * (a pseudo-terminal whose first process, its session's leader, runs `fdsec --hold-tty FILE`), then starts
 * `fdsec --tty <its name> -- fdsec-tty-test check` as the app, and Ctrl+C for a second app; then
 * the same terminal through `fdsec --no-filter --tty`, which must leave the app without the filter.
 *
 *   fdsec-tty-test run <fdsec> <work dir>
 *   fdsec-tty-test check   (the app: its own session, the terminal its controlling terminal and
 *                           standard input, output and error; prints "tty ok", then "filtered"
 *                           or "unfiltered": whether fdsec's check rule answers)
 *   fdsec-tty-test wait    (the app: waits for a signal)
 * Exits 1 if a check fails.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static int check(void)
{
	int ok = getsid(0) == getpid() && tcgetpgrp(0) == getpgrp();
	for (int fd = 0; fd < 3; fd++) ok = ok && isatty(fd);
	int tty = open("/dev/tty", O_RDWR);
	ok = ok && tty >= 0;
	errno = 0;
	/* fdsec's check rule (CHECK_WHICH and CHECK_ERRNO in fdsec.c). */
	int filtered = syscall(SYS_getpriority, 0x66647365, 0) == -1 && errno == ENOTRECOVERABLE;
	printf(ok ? "tty ok %s\n" : "tty FAIL %s\n", filtered ? "filtered" : "unfiltered");
	return !ok;
}

/* What the master reads within [ms], appended to [buf]. */
static void drain(int master, char *buf, size_t size, int ms)
{
	struct pollfd p = { master, POLLIN, 0 };
	size_t used = strlen(buf);
	while (used + 1 < size && poll(&p, 1, ms) > 0) {
		ssize_t n = read(master, buf + used, size - 1 - used);
		if (n <= 0) break;
		used += n;
		buf[used] = 0;
	}
}

static pid_t app(const char *fdsec, const char *name, const char *self, const char *mode, int no_filter)
{
	pid_t pid = fork();
	if (pid == 0) {
		if (no_filter) execl(fdsec, fdsec, "--no-filter", "--tty", name, "--", self, mode, (char *)0);
		else execl(fdsec, fdsec, "--tty", name, "--", self, mode, (char *)0);
		_exit(127);
	}
	return pid;
}

static int run(const char *self, const char *fdsec, const char *work)
{
	int failures = 0;
	int master = posix_openpt(O_RDWR | O_NOCTTY);
	if (master < 0 || grantpt(master) || unlockpt(master)) { perror("posix_openpt"); return 1; }
	char slave[64];
	snprintf(slave, sizeof slave, "%s", ptsname(master));
	char file[512];
	snprintf(file, sizeof file, "%s/tty", work);

	/* The emulator's child: its own session with the terminal, as an emulator starts it. */
	pid_t holder = fork();
	if (holder == 0) {
		setsid();
		int fd = open(slave, O_RDWR);
		ioctl(fd, TIOCSCTTY, 0);
		for (int i = 0; i < 3; i++) dup2(fd, i);
		if (fd > 2) close(fd);
		execl(fdsec, fdsec, "--hold-tty", file, (char *)0);
		_exit(127);
	}
	char name[64] = "";
	for (int i = 0; i < 100 && !name[0]; i++) {
		FILE *f = fopen(file, "r");
		if (f) { if (!fgets(name, sizeof name, f)) name[0] = 0; fclose(f); }
		if (!name[0]) usleep(20000);
	}
	printf("%-20s %s (%s, wrote %s)\n", "hold-tty", strcmp(name, slave) ? "FAIL" : "ok  ", slave, name[0] ? name : "nothing");
	if (strcmp(name, slave)) failures++;
	/* The session's leader leaves the terminal to its child and exits, which releases it. */
	int held = -1;
	waitpid(holder, &held, 0);
	if (!WIFEXITED(held) || WEXITSTATUS(held) != 0) { printf("hold-tty             FAIL (leader ended with %d)\n", held); failures++; }

	char out[4096] = "";
	pid_t one = app(fdsec, slave, self, "check", 0);
	int status = 0;
	waitpid(one, &status, 0);
	drain(master, out, sizeof out, 200);
	int ok = WIFEXITED(status) && WEXITSTATUS(status) == 0 && strstr(out, "tty ok filtered");
	printf("%-20s %s (exit %d, terminal said: %s)\n", "tty", ok ? "ok  " : "FAIL", WIFEXITED(status) ? WEXITSTATUS(status) : -1, out);
	if (!ok) failures++;

	/* --no-filter: the same terminal, no filter. */
	out[0] = 0;
	pid_t free = app(fdsec, slave, self, "check", 1);
	waitpid(free, &status, 0);
	drain(master, out, sizeof out, 200);
	ok = WIFEXITED(status) && WEXITSTATUS(status) == 0 && strstr(out, "tty ok unfiltered");
	printf("%-20s %s (exit %d, terminal said: %s)\n", "tty without filter", ok ? "ok  " : "FAIL", WIFEXITED(status) ? WEXITSTATUS(status) : -1, out);
	if (!ok) failures++;

	/* Ctrl+C typed into the terminal reaches the app, which runs in the foreground. */
	pid_t two = app(fdsec, slave, self, "wait", 0);
	usleep(200000);
	if (write(master, "\003", 1) != 1) perror("write");
	ok = 0;
	for (int i = 0; i < 100; i++) {
		if (waitpid(two, &status, WNOHANG) == two) { ok = WIFSIGNALED(status) && WTERMSIG(status) == SIGINT; break; }
		usleep(20000);
	}
	printf("%-20s %s\n", "ctrl+c", ok ? "ok  " : "FAIL");
	if (!ok) { failures++; kill(two, SIGKILL); waitpid(two, 0, 0); }

	/* Refused: a path outside /dev/pts. */
	pid_t bad = app(fdsec, "/dev/null", self, "check", 0);
	waitpid(bad, &status, 0);
	ok = WIFEXITED(status) && WEXITSTATUS(status) == 124;
	printf("%-20s %s\n", "tty outside pts", ok ? "ok  " : "FAIL");
	if (!ok) failures++;

	/* The holder ends when the terminal hangs up: the master closes when this test exits. */
	printf("%d failure(s)\n", failures);
	return failures != 0;
}

int main(int argc, char **argv)
{
	if (argc == 2 && !strcmp(argv[1], "check")) return check();
	if (argc == 2 && !strcmp(argv[1], "wait")) { for (;;) pause(); }
	if (argc == 4 && !strcmp(argv[1], "run")) return run(argv[0], argv[2], argv[3]);
	fprintf(stderr, "usage: fdsec-tty-test run <fdsec> <work dir> | check | wait\n");
	return 2;
}
