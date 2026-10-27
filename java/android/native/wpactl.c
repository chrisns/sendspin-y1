/*
 * wpactl CMD...: send one command to wpa_supplicant's control socket and print the reply (Android 4.2 ships no wpa_cli).
 * Used for "SET ps 0" / "SET ps 1": Wi-Fi power save, which must be off while the Find My beacon runs (rom/bt/NOTES.md).
 * Run as root. Build: native/build.sh
 */
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/time.h>

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: wpactl CMD...\n"); return 1; }
    char cmd[512] = "";
    for (int i = 1; i < argc; i++) { if (i > 1) strcat(cmd, " "); strncat(cmd, argv[i], sizeof cmd - strlen(cmd) - 2); }
    const char *servers[] = { "/data/misc/wpa_supplicant/wlan0", "/data/misc/wifi/sockets/wlan0", NULL };
    int fd = socket(AF_UNIX, SOCK_DGRAM, 0);
    struct sockaddr_un me = { .sun_family = AF_UNIX }, to = { .sun_family = AF_UNIX };
    snprintf(me.sun_path, sizeof me.sun_path, "/data/misc/wifi/sockets/wpactl_%d", getpid());
    unlink(me.sun_path);
    if (bind(fd, (struct sockaddr *) &me, sizeof me) < 0) { perror("bind"); return 2; }
    struct timeval tv = { 3, 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    int rc = 3;
    for (int s = 0; servers[s]; s++) {
        strncpy(to.sun_path, servers[s], sizeof to.sun_path - 1);
        if (sendto(fd, cmd, strlen(cmd), 0, (struct sockaddr *) &to, sizeof to) < 0) continue;
        char buf[4096];
        int n = recv(fd, buf, sizeof buf - 1, 0);
        if (n > 0) { buf[n] = 0; fputs(buf, stdout); if (buf[n - 1] != '\n') putchar('\n'); rc = 0; break; }
    }
    if (rc) fprintf(stderr, "no answer from wpa_supplicant\n");
    unlink(me.sun_path);
    return rc;
}
