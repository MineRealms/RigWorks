/* Compile the public header as C as well as C++. */
#include "rigworks_ysm.h"

int rwym_c_header_test(void) {
    return rwym_abi_version() == RWYM_ABI_VERSION && rwym_version() != 0;
}
