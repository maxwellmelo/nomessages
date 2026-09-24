/* Linux host test fault injection only. Never compiled into application/native production artifacts.
 * Run selected JVM tests with LD_PRELOAD and NOMESSAGES_TEST_DENY_CLEANUP=1 to deny removal of
 * post-commit encrypted backups/import ZIPs without adding test hooks to production classes.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int denied(const char *path) {
    if (!getenv("NOMESSAGES_TEST_DENY_CLEANUP")) return 0;
    if (strstr(path, ".reset-backup")) return 1;
    const char *leaf = strrchr(path, '/');
    leaf = leaf ? leaf + 1 : path;
    size_t size = strlen(leaf);
    return strstr(leaf, ".vault-import-") == leaf && size > 4 && !strcmp(leaf + size - 4, ".zip");
}

int unlink(const char *path) {
    if (denied(path)) { errno = EACCES; return -1; }
    return ((int (*)(const char *))dlsym(RTLD_NEXT, "unlink"))(path);
}

int rmdir(const char *path) {
    if (denied(path)) { errno = EACCES; return -1; }
    return ((int (*)(const char *))dlsym(RTLD_NEXT, "rmdir"))(path);
}

int unlinkat(int descriptor, const char *path, int flags) {
    char fdpath[64], parent[PATH_MAX], full[PATH_MAX];
    snprintf(fdpath, sizeof(fdpath), "/proc/self/fd/%d", descriptor);
    ssize_t count = readlink(fdpath, parent, sizeof(parent) - 1);
    if (count >= 0) {
        parent[count] = 0;
        snprintf(full, sizeof(full), "%s/%s", parent, path);
        if (denied(full)) { errno = EACCES; return -1; }
    }
    if (denied(path)) { errno = EACCES; return -1; }
    return ((int (*)(int, const char *, int))dlsym(RTLD_NEXT, "unlinkat"))(descriptor, path, flags);
}
