/*
 * fmhci: holds the MediaTek Bluetooth controller (/dev/stpbt) for the Find My beacon.
 *
 * Why native: the driver's blocking read sleeps uninterruptibly (wait_event in BT_read), so a shell `cat` reader
 * can never be stopped and keeps the radio until a reboot. Here the device is opened O_NONBLOCK and only read when
 * poll() says a reply is waiting, so the process is always killable and closes the radio cleanly.
 *
 *   fmhci BASE
 *     BASE.cmd     the app drops HCI packets here, one per line in hex; taken by rename, so all or nothing
 *     BASE.events  controller replies, appended raw
 *     BASE.pid     written once the radio is open
 *     BASE.stop    created by the app to ask for a clean exit (SIGTERM does the same)
 *
 * Exit codes: 0 clean stop, 1 bad usage, 2 radio busy, 3 driver cannot poll (unsafe to read).
 */
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static volatile sig_atomic_t quit;
static void on_term(int s) { (void) s; quit = 1; }

static char base[200], p_cmd[220], p_work[220], p_ev[220], p_pid[220], p_stop[220];

static void drain(int fd, int wait_ms) {
    struct pollfd p = { fd, POLLIN, 0 };
    unsigned char buf[512];
    while (poll(&p, 1, wait_ms) > 0 && (p.revents & POLLIN)) {
        ssize_t n = read(fd, buf, sizeof buf);
        if (n <= 0) break;
        FILE *f = fopen(p_ev, "ab");
        if (f) { fwrite(buf, 1, (size_t) n, f); fclose(f); }
        wait_ms = 50;                      // a reply can arrive in pieces
    }
}

static int hexval(int c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static void send_line(int fd, const char *line) {
    unsigned char pkt[300];
    size_t n = 0;
    for (const char *s = line; s[0] && s[1] && n < sizeof pkt; ) {
        int hi = hexval(s[0]), lo = hexval(s[1]);
        if (hi < 0 || lo < 0) { s++; continue; }
        pkt[n++] = (unsigned char) (hi << 4 | lo);
        s += 2;
    }
    if (n == 0) return;
    if (write(fd, pkt, n) != (ssize_t) n) return;
    drain(fd, 400);                        // wait for its Command Complete
}

static void run_cmd_file(int fd) {
    if (rename(p_cmd, p_work) != 0) return;
    FILE *f = fopen(p_work, "r");
    if (f) {
        char line[700];
        while (fgets(line, sizeof line, f)) send_line(fd, line);
        fclose(f);
    }
    unlink(p_work);
}

int main(int argc, char **argv) {
    if (argc != 2 || strlen(argv[1]) >= sizeof base) return 1;
    strcpy(base, argv[1]);
    snprintf(p_cmd, sizeof p_cmd, "%s.cmd", base);
    snprintf(p_work, sizeof p_work, "%s.work", base);
    snprintf(p_ev, sizeof p_ev, "%s.events", base);
    snprintf(p_pid, sizeof p_pid, "%s.pid", base);
    snprintf(p_stop, sizeof p_stop, "%s.stop", base);
    unlink(p_cmd); unlink(p_work); unlink(p_stop); unlink(p_ev); unlink(p_pid);

    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_term;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);

    int fd = open("/dev/stpbt", O_RDWR | O_NONBLOCK);
    if (fd < 0) return 2;

    // nothing has been sent yet, so a driver that reports "readable" now has no real poll: reading would block forever
    struct pollfd p = { fd, POLLIN, 0 };
    if (poll(&p, 1, 0) > 0 && (p.revents & POLLIN)) {
        unsigned char junk[64];
        if (read(fd, junk, sizeof junk) < 0 && errno == EAGAIN) { close(fd); return 3; }
    }

    FILE *pf = fopen(p_pid, "w");
    if (pf) { fprintf(pf, "%d\n", (int) getpid()); fclose(pf); }

    while (!quit && access(p_stop, F_OK) != 0) {
        if (access(p_cmd, F_OK) == 0) run_cmd_file(fd);
        drain(fd, 300);                    // also our tick: wakes at least every 300 ms
    }
    send_line(fd, "010a200100");           // LE advertising off before handing the radio back
    close(fd);
    unlink(p_pid);
    unlink(p_stop);
    return 0;
}
