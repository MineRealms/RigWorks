// Originally derived from:
// Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
//        and https://github.com/stevewongv/YesSteveModel (Apache-2.0)
//
// Licensed under the Apache License, Version 2.0.
// You may obtain a copy of the License at:
// https://www.apache.org/licenses/LICENSE-2.0
//
// Modified by MineRealms for RigWorks.
// Changes: Cleanroom reimplementation / package adaptation / API changes for in-mod YSM decode.

const std = @import("std");
const ysp = @import("ysmprocessor.zig");

pub fn main() !void {
    const allocator = std.heap.page_allocator;
    const args = try std.process.argsAlloc(allocator);
    if (args.len < 2) return error.MissingPath;
    const bytes = try std.fs.cwd().readFileAlloc(allocator, args[1], 64 * 1024 * 1024);

    var out: ?[*]u8 = null;
    var out_len: c_int = 0;
    const rc = ysp.ysp_legacy_v3_decode(bytes.ptr, @intCast(bytes.len), &out, &out_len);
    const stderr = std.io.getStdErr().writer();
    try stderr.print("rc={d} len={d} err={s}\n", .{ rc, out_len, std.mem.span(ysp.ysp_last_error()) });
    if (rc == 0 or rc == ysp.YSP_WASHED_ZSTD_FRAME) {
        const slice = out.?[0..@intCast(out_len)];
        var format: u32 = @as(u32, slice[0]) | (@as(u32, slice[1]) << 8) | (@as(u32, slice[2]) << 16) | (@as(u32, slice[3]) << 24);
        try stderr.print("first4={d} bytes={x} {x} {x} {x}\n", .{ format, slice[0], slice[1], slice[2], slice[3] });
        _ = &format;
        ysp.ysp_free(@ptrCast(out.?));
    }
}
