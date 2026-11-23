#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/fiemap.h>
#include <linux/fs.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>

JNIEXPORT jstring JNICALL
Java_dev_glassworks_stockprobe_Fiemap_queryJson(JNIEnv *env, jclass clazz, jstring jpath) {
    (void)clazz;
    if (jpath == NULL) return (*env)->NewStringUTF(env, "{\"error\":\"null path\"}");
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) return (*env)->NewStringUTF(env, "{\"error\":\"oom\"}");

    char out[4096];
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        snprintf(out, sizeof(out), "{\"error\":\"open failed: %s\",\"errno\":%d}", strerror(errno), errno);
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return (*env)->NewStringUTF(env, out);
    }

    struct stat st;
    memset(&st, 0, sizeof(st));
    fstat(fd, &st);

    const unsigned max_extents = 32;
    size_t sz = sizeof(struct fiemap) + max_extents * sizeof(struct fiemap_extent);
    struct fiemap *fm = (struct fiemap *)calloc(1, sz);
    if (fm == NULL) {
        close(fd);
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return (*env)->NewStringUTF(env, "{\"error\":\"oom\"}");
    }

    fm->fm_start = 0;
    fm->fm_length = ~0ULL;
    fm->fm_flags = 0;
    fm->fm_extent_count = max_extents;

    if (ioctl(fd, FS_IOC_FIEMAP, fm) < 0) {
        snprintf(out, sizeof(out), "{\"error\":\"FS_IOC_FIEMAP failed: %s\",\"errno\":%d}", strerror(errno), errno);
    } else {
        int pos = snprintf(out, sizeof(out),
                           "{\"size\":%lld,\"ino\":%llu,\"extents\":[",
                           (long long)st.st_size, (unsigned long long)st.st_ino);
        for (unsigned i = 0; i < fm->fm_mapped_extents && pos < (int)sizeof(out) - 160; i++) {
            struct fiemap_extent *e = &fm->fm_extents[i];
            pos += snprintf(out + pos, sizeof(out) - (size_t)pos,
                            "%s{\"logical\":%llu,\"physical\":%llu,\"block\":%llu,\"length\":%llu,\"blocks\":%llu,\"flags\":%u}",
                            i == 0 ? "" : ",",
                            (unsigned long long)e->fe_logical,
                            (unsigned long long)e->fe_physical,
                            (unsigned long long)(e->fe_physical / 4096ULL),
                            (unsigned long long)e->fe_length,
                            (unsigned long long)(e->fe_length / 4096ULL),
                            e->fe_flags);
        }
        snprintf(out + pos, sizeof(out) - (size_t)pos, "]}");
    }

    free(fm);
    close(fd);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return (*env)->NewStringUTF(env, out);
}
