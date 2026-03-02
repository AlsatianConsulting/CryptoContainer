#pragma once

#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

struct exfat_dev;

int vc_exfat_mkfs(struct exfat_dev* dev, off_t volume_size, const char* volume_label);

#ifdef __cplusplus
}
#endif
