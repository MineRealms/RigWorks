# Headless MMD dependencies

The checked-in files are a subset of these pinned upstream sources. They are
build inputs, not a viewer or a second gameplay renderer. No dependency is
downloaded when rebuilding this module.

| Directory | Upstream revision | Included subset | License |
| --- | --- | --- | --- |
| `saba` | benikabocha/saba `29b8efa8b31c8e746f9a88020fb0ad9dcdcf3332` | Base, MMD model/animation/solver, matching GLM/spdlog/JSON headers | MIT; dependency notices included |
| `bullet` | bulletphysics/bullet3 2.89 `830f0a9565b1829a07e21e2f16be2aa9966bd28c` | LinearMath, BulletCollision, BulletDynamics and common headers | zlib |

Upstream sources:

- https://github.com/benikabocha/saba
- https://github.com/bulletphysics/bullet3

Saba's original copyright headers remain in the copied files. Its bundled
dependency license texts are under `saba/licenses/`. Bullet's license is at
`bullet/LICENSE.txt`. CMake stages all five notices next to the shared library.
OpenGL, GLFW, rendering, examples, Lua and GPU compute are not built or included.

`toolchain-licenses/` retains the Zig 0.13.0 and LLVM libc++/libc++abi/libunwind
notices for the statically linked standard runtimes in the optional Zig
cross-build. The standard CMake build stages these notices too, so both local
prebuilt and CI-produced packages carry a consistent notice set.
