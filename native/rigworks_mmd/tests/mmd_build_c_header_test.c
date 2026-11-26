#include "rigworks_mmd.h"

int mmd_c_header_abi(void) {
    return rwmd_abi_version() == UINT32_C(0x00010000);
}
