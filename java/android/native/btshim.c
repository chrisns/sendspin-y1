/*
 * libbluetoothdrv.so for the Y1: sits between mtkbt and MediaTek's driver library (libbluetoothdrv_real.so) and passes
 * everything through, except that it also runs the Find My beacon INSIDE Android's own Bluetooth session, so the
 * beacon and Bluetooth headphones share the radio (the raw-HCI helper fmhci cannot: it needs the radio to itself).
 *
 * How:
 *  - the beacon needs only two HCI commands, LE Set Advertising Data and LE Set Advertise Enable. It must never send
 *    LE Set Advertising Parameters: this chip's firmware hangs on it and the kernel resets the whole chip (rom/bt/NOTES.md).
 *  - a thread sends them when the host has been quiet, and again after every HCI reset the host does.
 *  - their Command Complete events are taken out of the stream before mtkbt sees them. Packets are reassembled in
 *    mtk_bt_read, so mtkbt still reads the same byte stream it always did.
 *  - advertising is connectable, so a phone may connect: that LE Connection Complete is hidden from mtkbt too, and
 *    the link is dropped straight away (and the Disconnection Complete hidden).
 *  - the advertising data comes from /data/local/tmp/fmbeacon.hex (64 hex characters: length byte + 31 bytes, then the
 *    key's 12-character address), written by the app. No file, no beacon. The advertised address is the controller's
 *    own public one, which classic Bluetooth uses too, so it is set once at enable from /data/local/tmp/fmbeacon.addr
 *    (12 hex characters), which the app writes once and never changes: headphones are paired to that address, and a
 *    different one at the next enable would leave them unable to reconnect. A config whose address is not the one set
 *    at enable is not advertised (half one key, half another, would report to a key nobody owns).
 *
 * Build: native/build.sh. Freestanding on purpose (no libc headers): it links against bionic at load time.
 */
typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned long ulong;

extern void *dlopen(const char *, int);
extern void *dlsym(void *, const char *);
extern int usleep(unsigned);
extern int open(const char *, int, ...);
extern int read(int, void *, unsigned);
extern int close(int);
extern void *memcpy(void *, const void *, unsigned);
extern int pthread_create(ulong *, const void *, void *(*)(void *), void *);
extern int clock_gettime(int, void *);
extern int __android_log_print(int, const char *, const char *, ...);

#define EXPORT __attribute__((visibility("default")))
#define LOG(...) __android_log_print(4, "FMSHIM", __VA_ARGS__)
#define CONFIG "/data/local/tmp/fmbeacon.hex"
#define PINNED "/data/local/tmp/fmbeacon.addr"

typedef int (*f4_t)(int, int, int, int);
typedef int (*wr_t)(int, const void *, int);
typedef int (*rd_t)(int, void *, int);

static int read_full(int fd, u8 *dst, int n, int patient);

static f4_t r_enable, r_disable, r_op;
static wr_t r_write;
static rd_t r_read;
static void *real;

static volatile int g_enabled, g_fd = -1;
static volatile int g_need_inject, g_outstanding, g_lock, g_started;
static volatile long g_last_host_write, g_last_assert, g_status_at;
static volatile int g_pend_2008, g_pend_200a, g_pend_0406;
static volatile int g_drop_handle = -1, g_disc_handle = -1, g_need_restart;
static u8 g_addr[6];                            // the address set at enable (MSB first), when g_addr_set
static volatile int g_addr_set, g_reload;

static long now(void) {
    struct { long s, ns; } ts;
    clock_gettime(1, &ts);                      // CLOCK_MONOTONIC
    return ts.s;
}

static void lock(void) { while (__sync_lock_test_and_set(&g_lock, 1)) usleep(200); }
static void unlock(void) { __sync_lock_release(&g_lock); }

static int resolve(void) {
    if (real) return 1;
    void *h = dlopen("/system/lib/libbluetoothdrv_real.so", 1);
    if (!h) return 0;
    r_enable = (f4_t) dlsym(h, "mtk_bt_enable");
    r_disable = (f4_t) dlsym(h, "mtk_bt_disable");
    r_write = (wr_t) dlsym(h, "mtk_bt_write");
    r_read = (rd_t) dlsym(h, "mtk_bt_read");
    r_op = (f4_t) dlsym(h, "mtk_bt_op");
    real = h;
    return 1;
}

// ---- beacon injection

static int hexval(int c) { return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1; }

static int unhex(const char *s, u8 *dst, int n) {
    for (int i = 0; i < n; i++) {
        int h = hexval(s[2 * i]), l = hexval(s[2 * i + 1]);
        if (h < 0 || l < 0) return 0;
        dst[i] = (u8) (h << 4 | l);
    }
    return 1;
}

/* 32 bytes of advertising data from 64 hex characters; then, optionally, the 6-byte Bluetooth address (12 hex
 * characters, most significant byte first). Returns 0 for no usable config, else 1, plus 2 if there is an address. */
static int load_cfg(u8 *adv, u8 *addr) {
    char b[96];
    int fd = open(CONFIG, 0);
    if (fd < 0) return 0;
    int n = read(fd, b, sizeof b);
    close(fd);
    if (n < 64 || !unhex(b, adv, 32) || adv[0] > 31) return 0;
    return n >= 76 && unhex(b + 64, addr, 6) ? 3 : 1;
}

/* The pinned classic address (12 hex characters, most significant byte first); 0 if there is none. */
static int load_pinned(u8 *addr) {
    char b[16];
    int fd = open(PINNED, 0);
    if (fd < 0) return 0;
    int n = read(fd, b, sizeof b);
    close(fd);
    return n >= 12 && unhex(b, addr, 6);
}

static void send(int pend_kind, const u8 *pkt, int len) {
    if (g_fd < 0) return;
    if (pend_kind == 1) __sync_fetch_and_add(&g_pend_2008, 1);
    else if (pend_kind == 2) __sync_fetch_and_add(&g_pend_200a, 1);
    else if (pend_kind == 3) __sync_fetch_and_add(&g_pend_0406, 1);
    lock();
    r_write(g_fd, pkt, len);
    unlock();
    usleep(30000);
}

static void adv_enable(int on) {
    u8 p[5] = { 1, 0x0a, 0x20, 1, (u8) on };
    send(2, p, 5);
}

static int memcmp_(const u8 *a, const u8 *b, int n) {
    for (int i = 0; i < n; i++) if (a[i] != b[i]) return 1;
    return 0;
}

static void *injector(void *unused) {
    u8 adv[32];
    int have = 0, valid_at = 0, refused = 0;
    long cfg_at = 0;
    for (;;) {
        usleep(300000);
        if (!g_enabled || g_fd < 0 || !real) continue;
        long t = now();
        if (!have || g_reload || t - cfg_at >= 30) {
            u8 n[32], a[6];
            g_reload = 0;
            int st = load_cfg(n, a);
            // only a key whose address is the one the controller has: its payload with another address is a third key
            int ok = st == 3 && g_addr_set && !memcmp_(a, g_addr, 6);
            if (st && !ok && !refused) LOG("config is not for the address set at enable: no beacon");
            refused = st && !ok;
            if (ok && (!have || memcmp_(n, adv, 32))) { memcpy(adv, n, 32); g_need_inject = 1; }
            if (!ok && have) { adv_enable(0); g_last_assert = 0; }
            have = ok;
            cfg_at = t;
        }
        (void) valid_at;
        if (!have) continue;
        if (g_disc_handle >= 0) {                // hang up on whoever connected
            int h = g_disc_handle;
            g_disc_handle = -1;
            u8 d[7] = { 1, 0x06, 0x04, 3, (u8) h, (u8) (h >> 8), 0x13 };
            send(3, d, 7);
            g_need_restart = 1;
            LOG("dropped a connection to the beacon (handle %d)", h);
            continue;
        }
        if (g_outstanding && t - g_last_host_write >= 4) {          // a command unanswered for 4 s will never be: stop waiting for it
            LOG("clearing %d stale outstanding command(s)", g_outstanding);
            g_outstanding = 0;
        }
        if (t - g_status_at >= 120) {
            g_status_at = t;
            LOG("state: need_inject=%d outstanding=%d fd=%d last_assert_age=%ld", g_need_inject, g_outstanding, g_fd, t - g_last_assert);
        }
        if (g_outstanding || t - g_last_host_write < 2) continue;   // wait for the host to be quiet
        if (g_need_inject) {
            u8 p[36] = { 1, 0x08, 0x20, 32 };
            memcpy(p + 4, adv, 32);
            send(1, p, 36);
            adv_enable(1);
            usleep(2000000);
            adv_enable(0);                       // the first enable after a reset is ignored by this controller
            adv_enable(1);
            g_need_inject = 0;
            g_need_restart = 0;
            g_last_assert = now();
            LOG("beacon on");
        } else if (g_need_restart || t - g_last_assert >= 60) {      // a connection (headphones) can stop advertising: re-enable often
            adv_enable(0);
            adv_enable(1);
            g_need_restart = 0;
            g_last_assert = now();
        }
    }
    return 0;
}

// ---- the entry points mtkbt calls

/* Send one command during mtk_bt_enable and read until its Command Complete (the host is not reading yet). */
static int command_sync(int fd, u8 *pkt, int len, int opcode) {
    if (r_write(fd, pkt, len) < 0) return 0;
    for (int tries = 0; tries < 40; tries++) {
        u8 t, h[2], e[64];
        if (read_full(fd, &t, 1, 0) <= 0) { usleep(50000); continue; }
        if (t != 4) continue;                    // nothing but events is expected this early
        if (read_full(fd, h, 2, 1) < 0) return 0;
        int n = h[1] > 64 ? 64 : h[1];
        if (read_full(fd, e, n, 1) < 0) return 0;
        if (h[0] == 0x0e && n >= 4 && (e[1] | e[2] << 8) == opcode) return e[3] == 0;
    }
    return 0;
}

/*
 * The driver library has just done its own init (MediaTek's address, radio and sleep settings, then a reset). The
 * address comes from an NVRAM file that cannot be edited (it zeroes itself if you do), so set the key's address with
 * MediaTek's own command and reset again, as the driver's script does: that is what makes the beacon the key's.
 */
static int set_address(int fd, const u8 *addr) {
    u8 p[10] = { 1, 0x1a, 0xfc, 6 };
    for (int i = 0; i < 6; i++) p[4 + i] = addr[5 - i];      // LSB first
    u8 rst[4] = { 1, 0x03, 0x0c, 0 };
    if (command_sync(fd, p, 10, 0xfc1a) && command_sync(fd, rst, 4, 0x0c03)) { LOG("address set"); return 1; }
    LOG("could not set the address");
    return 0;
}

EXPORT int mtk_bt_enable(int a, int b, int c, int d) {
    if (!resolve()) return -1;
    int r = r_enable(a, b, c, d);
    if (r >= 0) {
        u8 adv[32], addr[6];
        // the pinned address, every time, beacon or not (the config's own only until the app has pinned one)
        g_addr_set = (load_pinned(addr) || load_cfg(adv, addr) == 3) && set_address(r, addr);
        if (g_addr_set) memcpy(g_addr, addr, 6);
        g_reload = 1;                            // judge the config against this address, not the last session's
        g_fd = r;
        g_outstanding = 0;
        g_need_inject = 1;
        g_pend_2008 = g_pend_200a = g_pend_0406 = 0;
        g_drop_handle = g_disc_handle = -1;
        g_enabled = 1;
        if (!__sync_lock_test_and_set(&g_started, 1)) { ulong tid; pthread_create(&tid, 0, injector, 0); }
    }
    return r;
}

EXPORT int mtk_bt_disable(int a, int b, int c, int d) {
    g_enabled = 0;
    if (!resolve()) return -1;
    int r = r_disable(a, b, c, d);
    g_fd = -1;
    return r;
}

EXPORT int mtk_bt_op(int a, int b, int c, int d) {
    return resolve() ? r_op(a, b, c, d) : -1;
}

EXPORT int mtk_bt_write(int fd, const void *buf, int len) {
    if (!resolve()) return -1;
    const u8 *p = (const u8 *) buf;
    g_fd = fd;
    if (len >= 4 && p[0] == 1) {                 // an HCI command from the host
        if ((p[1] | p[2] << 8) == 0x0c03) {      // reset: advertising is gone, and nothing is outstanding any more
            g_need_inject = 1;
            g_outstanding = 0;
        }
        __sync_fetch_and_add(&g_outstanding, 1);
        g_last_host_write = now();
    }
    lock();
    int r = r_write(fd, buf, len);
    unlock();
    return r;
}

// A whole HCI packet at a time, so events of ours can be dropped; mtkbt is then served from it as a plain byte stream.
static u8 st[2200];
static int st_len, st_pos;

static int read_full(int fd, u8 *dst, int n, int patient) {
    int total = 0, idle = 0;
    while (total < n) {
        int r = r_read(fd, dst + total, n - total);
        if (r > 0) { total += r; idle = 0; continue; }
        if (!patient && total == 0) return r;    // nothing yet: the host's own idle handling applies
        if (++idle > 1500) return -1;            // a packet that stopped halfway for 3 s
        usleep(2000);
    }
    return total;
}

static int swallow(const u8 *p, int len) {       // p: the whole packet including its type byte
    if (p[0] != 4 || len < 3) return 0;
    int code = p[1], pl = len - 3;
    const u8 *e = p + 3;
    if (code == 0x0e && pl >= 4) {               // Command Complete: num, opcode(2), status...
        int op = e[1] | e[2] << 8;
        if (op == 0x2008 && g_pend_2008 > 0) { __sync_fetch_and_sub(&g_pend_2008, 1); return 1; }
        if (op == 0x200a && g_pend_200a > 0) { __sync_fetch_and_sub(&g_pend_200a, 1); return 1; }
    } else if (code == 0x0f && pl >= 4) {        // Command Status: status, num, opcode(2)
        int op = e[2] | e[3] << 8;
        if (op == 0x0406 && g_pend_0406 > 0) { __sync_fetch_and_sub(&g_pend_0406, 1); return 1; }
    } else if (code == 0x3e && pl >= 4 && (e[0] == 1 || e[0] == 0x0a)) {   // LE connection complete: only we advertise
        if (e[1] == 0) {
            int h = (e[2] | e[3] << 8) & 0x0fff;
            g_drop_handle = h;
            g_disc_handle = h;
        }
        return 1;
    } else if (code == 0x05 && pl >= 4 && g_drop_handle >= 0) {            // disconnection complete: status, handle(2), reason
        if (((e[1] | e[2] << 8) & 0x0fff) == g_drop_handle) { g_drop_handle = -1; return 1; }
    }
    return 0;
}

static int fill(int fd) {
    for (;;) {
        u8 t;
        int r = read_full(fd, &t, 1, 0);
        if (r <= 0) return r;
        int hl = t == 4 ? 2 : t == 2 ? 4 : t == 3 ? 3 : 0;
        st[0] = t;
        int len = 1;
        if (hl) {
            if (read_full(fd, st + 1, hl, 1) < 0) return -1;
            len += hl;
            int pl = t == 4 ? st[2] : t == 2 ? (st[3] | st[4] << 8) : st[3];
            if (len + pl > (int) sizeof st) pl = (int) sizeof st - len;
            if (pl > 0 && read_full(fd, st + len, pl, 1) < 0) return -1;
            len += pl;
        }
        if (swallow(st, len)) continue;
        if (t == 4 && len >= 3 && (st[1] == 0x0e || st[1] == 0x0f) && g_outstanding > 0) __sync_fetch_and_sub(&g_outstanding, 1);
        st_len = len;
        st_pos = 0;
        return 1;
    }
}

EXPORT int mtk_bt_read(int fd, void *buf, int len) {
    if (!resolve()) return -1;
    u8 *out = (u8 *) buf;
    int got = 0;
    if (len <= 0) return r_read(fd, buf, len);
    while (got < len) {
        if (st_pos < st_len) {
            int n = st_len - st_pos < len - got ? st_len - st_pos : len - got;
            memcpy(out + got, st + st_pos, n);
            st_pos += n;
            got += n;
            continue;
        }
        if (got > 0) break;                      // never wait for more once something is ready
        int r = fill(fd);
        if (r <= 0) return r;
    }
    return got;
}
