#include "exfat_mkfs_bridge.h"

#include "mkexfat.h"
#include "vbr.h"
#include "fat.h"
#include "cbm.h"
#include "uct.h"
#include "rootdir.h"
#include <exfat.h>

#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>

const struct fs_object* objects[] =
{
    &vbr,
    &vbr,
    &fat,
    &cbm,
    &uct,
    &rootdir,
    NULL,
};

static struct
{
    int sector_bits;
    int spc_bits;
    off_t volume_size;
    le16_t volume_label[EXFAT_ENAME_MAX + 1];
    uint32_t volume_serial;
    uint64_t first_sector;
}
g_param;

int get_sector_bits(void)
{
    return g_param.sector_bits;
}

int get_spc_bits(void)
{
    return g_param.spc_bits;
}

off_t get_volume_size(void)
{
    return g_param.volume_size;
}

const le16_t* get_volume_label(void)
{
    return g_param.volume_label;
}

uint32_t get_volume_serial(void)
{
    return g_param.volume_serial;
}

uint64_t get_first_sector(void)
{
    return g_param.first_sector;
}

int get_sector_size(void)
{
    return 1 << get_sector_bits();
}

int get_cluster_size(void)
{
    return get_sector_size() << get_spc_bits();
}

static int vc_setup_spc_bits(int sector_bits, int user_defined, off_t volume_size)
{
    int i;

    if (user_defined != -1)
    {
        off_t cluster_size = 1 << sector_bits << user_defined;
        if (volume_size / cluster_size > EXFAT_LAST_DATA_CLUSTER)
            return -1;
        return user_defined;
    }

    if (volume_size < 256LL * 1024 * 1024)
        return MAX(0, 12 - sector_bits);
    if (volume_size < 32LL * 1024 * 1024 * 1024)
        return MAX(0, 15 - sector_bits);

    for (i = 17; ; i++)
        if (DIV_ROUND_UP(volume_size, 1 << i) <= EXFAT_LAST_DATA_CLUSTER)
            return MAX(0, i - sector_bits);
}

static int vc_setup_volume_label(le16_t label[EXFAT_ENAME_MAX + 1], const char* s)
{
    memset(label, 0, (EXFAT_ENAME_MAX + 1) * sizeof(le16_t));
    if (s == NULL || *s == '\0')
        return 0;
    return exfat_utf8_to_utf16(label, s, EXFAT_ENAME_MAX + 1, strlen(s));
}

static uint32_t vc_setup_volume_serial(void)
{
    struct timeval now;
    if (gettimeofday(&now, NULL) != 0)
        return 0;
    return (now.tv_sec << 20) | now.tv_usec;
}

int vc_exfat_mkfs(struct exfat_dev* dev, off_t volume_size, const char* volume_label)
{
    if (!dev)
        return 1;

    g_param.sector_bits = 9;
    g_param.first_sector = 0;
    g_param.volume_size = volume_size > 0 ? volume_size : exfat_get_size(dev);
    g_param.spc_bits = vc_setup_spc_bits(g_param.sector_bits, -1, g_param.volume_size);
    if (g_param.spc_bits < 0)
        return 1;

    if (vc_setup_volume_label(g_param.volume_label, volume_label) != 0)
        return 1;

    g_param.volume_serial = vc_setup_volume_serial();
    if (g_param.volume_serial == 0)
        return 1;

    return mkfs(dev, g_param.volume_size);
}
