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
// (Zig has no block comments; the mandatory attribution header is expressed as line comments.)

const std = @import("std");
const zstd = std.compress.zstd;

const SEED_KEY_DERIVATION: u64 = 0xD017CBBA7B5D3581;
const SEED_FILE_VERIFICATION: u64 = 0x9E5599DB80C67C29;
const SEED_RES_VERIFICATION: u64 = 0xA62B1A2C43842BC3;

pub const YSP_OK: c_int = 0;
pub const YSP_WASHED_ZSTD_FRAME: c_int = 2;
pub const YSP_ERR_INVALID_ARGUMENT: c_int = -1;
pub const YSP_ERR_FILE_TOO_SHORT: c_int = -2;
pub const YSP_ERR_BAD_HEADER: c_int = -3;
pub const YSP_ERR_HASH_MISMATCH: c_int = -4;
pub const YSP_ERR_UNSUPPORTED_CRYPTO: c_int = -5;
pub const YSP_ERR_DECODE: c_int = -6;
pub const YSP_ERR_OUT_OF_MEMORY: c_int = -7;
pub const YSP_ERR_ZSTD: c_int = -8;

threadlocal var g_last_error: [256]u8 = [_]u8{0} ** 256;

fn setError(comptime fmt: []const u8, args: anytype) void {
    const msg = std.fmt.bufPrint(&g_last_error, fmt, args) catch "error message too long";
    @memset(g_last_error[msg.len..], 0);
}

fn clearError() void {
    g_last_error[0] = 0;
}

// ---------------------------------------------------------------- CityHash64

const K0: u64 = 0xE4986A230E5AAA17;
const K1: u64 = 0x91AF10802CAB25A5;
const K2: u64 = 0xAF29CE778879D9C7;
const KMUL: u64 = 0xDE0F6EE09BDBAB91;

fn fetch64(data: []const u8, pos: usize) u64 {
    var r: u64 = 0;
    var i: usize = 0;
    while (i < 8) : (i += 1) {
        r |= @as(u64, data[pos + i]) << @intCast(i * 8);
    }
    return r;
}

fn fetch32(data: []const u8, pos: usize) u32 {
    var r: u32 = 0;
    var i: usize = 0;
    while (i < 4) : (i += 1) {
        r |= @as(u32, data[pos + i]) << @intCast(i * 8);
    }
    return r;
}

inline fn rot64(x: u64, shift: u6) u64 {
    return std.math.rotr(u64, x, shift);
}

inline fn shiftMix(v: u64) u64 {
    return v ^ (v >> 47);
}

fn hashLen16mul(u: u64, v: u64, mul: u64) u64 {
    var a = (u ^ v) *% mul;
    a ^= a >> 47;
    var b = (v ^ a) *% mul;
    b ^= b >> 47;
    b *%= mul;
    return b;
}

fn hashLen16(u: u64, v: u64) u64 {
    const term1 = (u ^ v) *% KMUL;
    const term2 = (shiftMix(term1) ^ u) *% KMUL;
    return KMUL *% shiftMix(term2);
}

fn hashLen0to16(data: []const u8, base: usize, len: usize) u64 {
    if (len >= 8) {
        const mul = K2 +% (2 *% @as(u64, @intCast(len)));
        const a = fetch64(data, base) +% K2;
        const b = fetch64(data, base + len - 8);
        const c = rot64(b, 37) *% mul +% a;
        const d = (rot64(a, 25) +% b) *% mul;
        return hashLen16mul(c, d, mul);
    }
    if (len >= 4) {
        const mul = K2 +% (2 *% @as(u64, @intCast(len)));
        const a: u64 = @as(u64, fetch32(data, base));
        const b: u64 = @as(u64, fetch32(data, base + len - 4));
        return hashLen16mul(@as(u64, @intCast(len)) +% (a << 3), b, mul);
    }
    if (len > 0) {
        const a: u64 = data[base];
        const b: u64 = data[base + (len >> 1)];
        const c: u64 = data[base + len - 1];
        const y = a +% (b << 8);
        const z = @as(u64, @intCast(len)) +% (c << 2);
        return shiftMix(y *% K2 ^ z *% K0) *% K2;
    }
    return K2;
}

fn hashLen17to32(data: []const u8, base: usize, len: usize) u64 {
    const mul = K2 +% (2 *% @as(u64, @intCast(len)));
    const a = fetch64(data, base) *% K1;
    const b = fetch64(data, base + 8);
    const c = fetch64(data, base + len - 8) *% mul;
    const d = fetch64(data, base + len - 16) *% K2;
    return hashLen16mul(rot64(a +% b, 43) +% rot64(c, 30) +% d, a +% rot64(b +% K2, 18) +% c, mul);
}

fn hashLen33to64(data: []const u8, base: usize, len: usize) u64 {
    const mul = K2 +% (2 *% @as(u64, @intCast(len)));
    var a = fetch64(data, base) *% K2;
    const b0 = fetch64(data, base + 8);
    const c = fetch64(data, base + len - 24);
    const d = fetch64(data, base + len - 32);
    const e = fetch64(data, base + 16) *% K2;
    const f = fetch64(data, base + 24) *% 9;
    const g = fetch64(data, base + len - 8);
    const h = fetch64(data, base + len - 16) *% mul;
    const u = rot64(a +% g, 43) +% ((rot64(b0, 30) +% c) *% 9);
    const v = ((a +% g) ^ d) +% f +% 1;
    const w = @byteSwap((u +% v) *% mul) +% h;
    const x = rot64(e +% f, 42) +% c;
    const y = (@byteSwap((v +% w) *% mul) +% g) *% mul;
    const z = e +% f +% c;
    a = @byteSwap((x +% z) *% mul +% y) +% b0;
    const b = shiftMix((z +% a) *% mul +% d +% h) *% mul;
    return b +% x;
}

fn cityHash64(data: []const u8) u64 {
    const len = data.len;
    if (len <= 32) {
        if (len <= 16) return hashLen0to16(data, 0, len);
        return hashLen17to32(data, 0, len);
    } else if (len <= 64) {
        return hashLen33to64(data, 0, len);
    }

    var x = fetch64(data, len - 40);
    var y = fetch64(data, len - 16) +% fetch64(data, len - 56);
    var z = hashLen16(fetch64(data, len - 48) +% @as(u64, @intCast(len)), fetch64(data, len - 24));

    var v_low: u64 = undefined;
    var v_hi: u64 = undefined;
    {
        const w0 = fetch64(data, len - 64);
        const x0 = fetch64(data, len - 56);
        const y0 = fetch64(data, len - 48);
        const z0 = fetch64(data, len - 40);
        var a = @as(u64, @intCast(len)) +% w0;
        var b = rot64(z +% a +% z0, 21);
        const c = a;
        a +%= x0;
        a +%= y0;
        b +%= rot64(a, 44);
        v_low = a +% z0;
        v_hi = b +% c;
    }
    var w_low: u64 = undefined;
    var w_hi: u64 = undefined;
    {
        const w0 = fetch64(data, len - 32);
        const x0 = fetch64(data, len - 24);
        const y0 = fetch64(data, len - 16);
        const z0 = fetch64(data, len - 8);
        var a = (y +% K1) +% w0;
        var b = rot64(x +% a +% z0, 21);
        const c = a;
        a +%= x0;
        a +%= y0;
        b +%= rot64(a, 44);
        w_low = a +% z0;
        w_hi = b +% c;
    }
    x = x *% K1 +% fetch64(data, 0);

    var remaining = (len - 1) & ~@as(usize, 63);
    var pos: usize = 0;
    while (remaining != 0) {
        x = rot64(x +% y +% v_low +% fetch64(data, pos + 8), 37) *% K1;
        y = rot64(y +% v_hi +% fetch64(data, pos + 48), 42) *% K1;
        x ^= w_hi;
        y +%= v_low +% fetch64(data, pos + 40);
        z = rot64(z +% w_low, 33) *% K1;
        {
            const w0 = fetch64(data, pos);
            const x0 = fetch64(data, pos + 8);
            const y0 = fetch64(data, pos + 16);
            const z0 = fetch64(data, pos + 24);
            var a = (v_hi *% K1) +% w0;
            var b = rot64((x +% w_low) +% a +% z0, 21);
            const c = a;
            a +%= x0;
            a +%= y0;
            b +%= rot64(a, 44);
            v_low = a +% z0;
            v_hi = b +% c;
        }
        {
            const w0 = fetch64(data, pos + 32);
            const x0 = fetch64(data, pos + 40);
            const y0 = fetch64(data, pos + 48);
            const z0 = fetch64(data, pos + 56);
            var a = (z +% w_hi) +% w0;
            var b = rot64((y +% fetch64(data, pos + 16)) +% a +% z0, 21);
            const c = a;
            a +%= x0;
            a +%= y0;
            b +%= rot64(a, 44);
            w_low = a +% z0;
            w_hi = b +% c;
        }
        const swap = x;
        x = z;
        z = swap;
        pos += 64;
        remaining -= 64;
    }
    return hashLen16(hashLen16(v_low, w_low) +% shiftMix(y) *% K1 +% z, hashLen16(v_hi, w_hi) +% x);
}

fn cityHash64WithSeed(data: []const u8, seed: u64) u64 {
    return hashLen16(cityHash64(data) -% K2, seed);
}

// ------------------------------------------------------------------- MT19937

const Mt19937 = struct {
    state: [312]u64 = undefined,
    index: usize = 312,

    fn init(self: *Mt19937, seed: u64) void {
        self.state[0] = seed;
        self.index = 312;
        var i: usize = 1;
        while (i < 312) : (i += 1) {
            const prev = self.state[i - 1];
            self.state[i] = 6364136223846793005 *% (prev ^ (prev >> 62)) +% @as(u64, @intCast(i));
        }
    }

    fn twist(self: *Mt19937) void {
        var i: usize = 0;
        while (i < 312) : (i += 1) {
            const x = (self.state[i] & 0xFFFFFFFF80000000) | (self.state[(i + 1) % 312] & 0x7FFFFFFF);
            var xa = x >> 1;
            if ((x & 1) != 0) xa ^= 0xB5026F5AA96619E9;
            self.state[i] = self.state[(i + 156) % 312] ^ xa;
        }
        self.index = 0;
    }

    fn extract(self: *Mt19937) u64 {
        if (self.index >= 312) self.twist();
        var y = self.state[self.index];
        self.index += 1;
        y ^= (y >> 29) & 0x5555555555555555;
        y ^= (y << 17) & 0x71D67FFFEDA60000;
        y ^= (y << 37) & 0xFFF7EEE000000000;
        y ^= y >> 43;
        return y;
    }
};

fn mt19937XorInPlace(data: []u8, key_iv: []const u8, seed_derivation: u64) void {
    const mt_seed = cityHash64WithSeed(key_iv, seed_derivation);
    var mt: Mt19937 = undefined;
    mt.init(mt_seed);
    var i: usize = 0;
    while (i < data.len) {
        const rnd = mt.extract();
        var j: usize = 0;
        while (j < 8 and i < data.len) : (j += 1) {
            const keystream_byte: u8 = @intCast((rnd >> @intCast(j * 8)) & 0xFF);
            data[i] ^= keystream_byte;
            i += 1;
        }
    }
}

// ---------------------------------------------------------------- XChaCha20

const XChaCha20 = struct {
    state: [16]u32 = undefined,
    working: [16]u32 = undefined,
    keystream: [64]u8 = undefined,
    rounds: usize,

    fn init(key: []const u8, nonce: []const u8, rounds: usize) XChaCha20 {
        var self = XChaCha20{ .rounds = rounds };
        var key_ints: [8]u32 = undefined;
        var nonce_ints: [6]u32 = undefined;
        for (0..8) |i| key_ints[i] = fetch32(key, i * 4);
        for (0..6) |i| nonce_ints[i] = fetch32(nonce, i * 4);

        var x: [16]u32 = undefined;
        x[0] = 0x61707865;
        x[1] = 0x3320646E;
        x[2] = 0x79622D32;
        x[3] = 0x6B206574;
        @memcpy(x[4..12], &key_ints);
        x[12] = nonce_ints[0];
        x[13] = nonce_ints[1];
        x[14] = nonce_ints[2];
        x[15] = nonce_ints[3];
        shuffle(&x, rounds);
        const subkey = [8]u32{ x[0], x[1], x[2], x[3], x[12], x[13], x[14], x[15] };

        self.state[0] = 0x61707865;
        self.state[1] = 0x3320646E;
        self.state[2] = 0x79622D32;
        self.state[3] = 0x6B206574;
        @memcpy(self.state[4..12], &subkey);
        self.state[12] = 0;
        self.state[13] = 0;
        self.state[14] = nonce_ints[4];
        self.state[15] = nonce_ints[5];
        return self;
    }

    fn rotateLeft32(x: u32, n: u5) u32 {
        return std.math.rotl(u32, x, n);
    }

    fn quarterRound(x: *[16]u32, a: usize, b: usize, c: usize, d: usize) void {
        x[a] +%= x[b];
        x[d] = rotateLeft32(x[d] ^ x[a], 16);
        x[c] +%= x[d];
        x[b] = rotateLeft32(x[b] ^ x[c], 12);
        x[a] +%= x[b];
        x[d] = rotateLeft32(x[d] ^ x[a], 8);
        x[c] +%= x[d];
        x[b] = rotateLeft32(x[b] ^ x[c], 7);
    }

    fn shuffle(x: *[16]u32, rounds: usize) void {
        const half = rounds / 2;
        var i: usize = 0;
        while (i < half) : (i += 1) {
            quarterRound(x, 0, 4, 8, 12);
            quarterRound(x, 1, 5, 9, 13);
            quarterRound(x, 2, 6, 10, 14);
            quarterRound(x, 3, 7, 11, 15);
            quarterRound(x, 0, 5, 10, 15);
            quarterRound(x, 1, 6, 11, 12);
            quarterRound(x, 2, 7, 8, 13);
            quarterRound(x, 3, 4, 9, 14);
        }
    }

    fn processBlock(self: *XChaCha20) void {
        @memcpy(&self.working, &self.state);
        shuffle(&self.working, self.rounds);
        for (0..16) |i| {
            self.working[i] +%= self.state[i];
            const v = self.working[i];
            self.keystream[i * 4] = @truncate(v);
            self.keystream[i * 4 + 1] = @truncate(v >> 8);
            self.keystream[i * 4 + 2] = @truncate(v >> 16);
            self.keystream[i * 4 + 3] = @truncate(v >> 24);
        }
    }

    fn incrementCounter(self: *XChaCha20) void {
        self.state[12] +%= 1;
        if (self.state[12] == 0) self.state[13] +%= 1;
    }

    fn processBytes(self: *XChaCha20, input: []const u8, output: []u8) void {
        var in_idx: usize = 0;
        while (in_idx < input.len) {
            self.processBlock();
            const block_len = @min(@as(usize, 64), input.len - in_idx);
            for (0..block_len) |i| output[in_idx + i] = input[in_idx + i] ^ self.keystream[i];
            self.incrementCounter();
            in_idx += block_len;
        }
    }

    fn updateStateYsm(self: *XChaCha20, hash: u64) usize {
        self.rounds = 10 * @as(usize, @intCast(hash % 3)) + 10;
        const lo: u32 = @truncate(hash);
        const hi: u32 = @truncate(hash >> 32);
        var i: usize = 4;
        while (i < 16) : (i += 1) {
            if (i % 2 == 0) {
                self.state[i] ^= lo;
            } else {
                self.state[i] ^= hi;
            }
        }
        return @as(usize, @intCast(((hash & 0x3F) | 0x40) << 6));
    }
};

fn modifiedChaChaDecrypt(
    allocator: std.mem.Allocator,
    input: []const u8,
    key: []const u8,
    iv: []const u8,
    seed: u64,
) ![]u8 {
    var key_iv: [56]u8 = undefined;
    @memcpy(key_iv[0..32], key);
    @memcpy(key_iv[32..56], iv);
    const hash2 = cityHash64WithSeed(&key_iv, seed);
    var next_round_size: usize = @intCast(((hash2 & 0x3F) | 0x40) << 6);
    const initial_rounds: usize = 10 * @as(usize, @intCast(hash2 % 3)) + 10;
    var ctx = XChaCha20.init(key, iv, initial_rounds);

    const result = try allocator.alloc(u8, input.len);
    errdefer allocator.free(result);
    var block_pointer: usize = 0;
    while (block_pointer < input.len) {
        if (block_pointer + next_round_size > input.len) {
            next_round_size = input.len - block_pointer;
        }
        ctx.processBytes(input[block_pointer .. block_pointer + next_round_size], result[block_pointer .. block_pointer + next_round_size]);
        block_pointer += next_round_size;
        if (block_pointer < input.len) {
            const res_hash = cityHash64WithSeed(result[block_pointer - next_round_size .. block_pointer], seed);
            next_round_size = ctx.updateStateYsm(res_hash);
        }
    }
    return result;
}

// ---------------------------------------------------------------- YSM zstd wash

fn frameHeaderSize(fhd: u8) usize {
    const dict_id_bits = fhd & 3;
    const dict_id_size: usize = switch (dict_id_bits) {
        1 => 1,
        2 => 2,
        3 => 4,
        else => 0,
    };
    const single_segment = ((fhd >> 5) & 1) == 1;
    const fcs_bits = (fhd >> 6) & 3;
    const fcs_size: usize = switch (fcs_bits) {
        0 => if (single_segment) 1 else 0,
        1 => 2,
        2 => 4,
        3 => 8,
        else => unreachable,
    };
    const window_desc_size: usize = if (single_segment) 0 else 1;
    return 1 + window_desc_size + dict_id_size + fcs_size;
}

fn washInPlace(data: []u8, base: usize, length: usize) !void {
    if (length < 5) return error.InvalidLength;
    const magic = fetch32(data, base);
    if (magic != 0xFD2FB528) return error.BadZstdMagic;

    const fhd = data[base + 4];
    data[base + 4] = fhd & 0xFB;

    var offset = base + 4 + frameHeaderSize(fhd);
    const end = base + length;
    while (offset + 3 <= end) {
        const b0 = data[offset];
        const b1 = data[offset + 1];
        const b2 = data[offset + 2];
        const last_block = (b0 >> 7) & 1;
        const block_type_ysm = (b0 >> 5) & 3;
        const raw_size = (@as(u32, b0 & 0x1F) << 16) | (@as(u32, b1)) | (@as(u32, b2) << 8);
        const c_size = raw_size ^ 0xD4E9;
        const block_type_std: u32 = switch (block_type_ysm) {
            0 => 2,
            1 => 1,
            2 => 3,
            3 => 0,
            else => unreachable,
        };
        const std_header: u32 = @as(u32, last_block) | (block_type_std << 1) | (c_size << 3);
        data[offset] = @truncate(std_header);
        data[offset + 1] = @truncate(std_header >> 8);
        data[offset + 2] = @truncate(std_header >> 16);
        const block_data_size: usize = if (block_type_std == 1) 1 else @intCast(c_size);
        offset += 3 + block_data_size;
        if (last_block == 1) break;
    }
}

// -------------------------------------------------------------------- decode

const CryptoResult = struct {
    buffer: []u8,
    frame_offset: usize,
};

fn decodeCrypto(allocator: std.mem.Allocator, file: []const u8) !CryptoResult {
    if (file.len < 8 + 24 + 32 + 8) return error.FileTooShort;

    var header_end: usize = 0;
    while (header_end < file.len and file[header_end] != 0x00) : (header_end += 1) {}
    if (header_end >= file.len) return error.BadHeader;

    const tail = file.len - 64;
    const key = file[tail .. tail + 32];
    const iv = file[tail + 32 .. tail + 56];
    const expected_hash = readLe64(file, tail + 56);
    const actual_hash = cityHash64WithSeed(file[0 .. file.len - 8], SEED_FILE_VERIFICATION);
    if (actual_hash != expected_hash) return error.HashMismatch;

    const crypto_pos = header_end + 1;
    if (crypto_pos + 4 > tail) return error.BadHeader;
    if (readLe32(file, crypto_pos) != 3) return error.UnsupportedCrypto;
    const body_start = crypto_pos + 4;

    const body = try modifiedChaChaDecrypt(allocator, file[body_start..tail], key, iv, SEED_RES_VERIFICATION);
    errdefer allocator.free(body);

    var key_iv: [56]u8 = undefined;
    @memcpy(key_iv[0..32], key);
    @memcpy(key_iv[32..56], iv);
    mt19937XorInPlace(body, &key_iv, SEED_KEY_DERIVATION);

    if (body.len < 2) return error.BadBody;
    const padding: usize = (@as(usize, body[0]) | (@as(usize, body[1]) << 8)) & 0x3FF;
    const frame_offset = 2 + padding;
    if (frame_offset >= body.len) return error.BadBody;

    try washInPlace(body, frame_offset, body.len - frame_offset);
    return CryptoResult{ .buffer = body, .frame_offset = frame_offset };
}

inline fn readLe32(data: []const u8, pos: usize) u32 {
    return @as(u32, data[pos]) | (@as(u32, data[pos + 1]) << 8) | (@as(u32, data[pos + 2]) << 16) | (@as(u32, data[pos + 3]) << 24);
}

inline fn readLe64(data: []const u8, pos: usize) u64 {
    var r: u64 = 0;
    var i: usize = 0;
    while (i < 8) : (i += 1) r |= @as(u64, data[pos + i]) << @intCast(i * 8);
    return r;
}

fn copyOut(bytes: []const u8, out: *?[*]u8, out_len: *c_int) bool {
    const ptr = std.c.malloc(bytes.len) orelse return false;
    @memcpy(@as([*]u8, @ptrCast(ptr))[0..bytes.len], bytes);
    out.* = @ptrCast(ptr);
    out_len.* = @intCast(bytes.len);
    return true;
}

fn errorCode(err: anyerror) c_int {
    return switch (err) {
        error.FileTooShort => YSP_ERR_FILE_TOO_SHORT,
        error.BadHeader, error.BadBody, error.InvalidLength, error.BadZstdMagic => YSP_ERR_DECODE,
        error.HashMismatch => YSP_ERR_HASH_MISMATCH,
        error.UnsupportedCrypto => YSP_ERR_UNSUPPORTED_CRYPTO,
        error.OutOfMemory => YSP_ERR_OUT_OF_MEMORY,
        else => YSP_ERR_DECODE,
    };
}

fn reportError(err: anyerror) c_int {
    setError("{s}", .{@errorName(err)});
    return errorCode(err);
}

// ------------------------------------------------------------------ C ABI

pub export fn ysp_init() callconv(.C) c_int {
    clearError();
    return YSP_OK;
}

pub export fn ysp_shutdown() callconv(.C) void {
    clearError();
}

pub export fn ysp_version() callconv(.C) [*:0]const u8 {
    return "1.0.0";
}

pub export fn ysp_last_error() callconv(.C) [*:0]const u8 {
    return @ptrCast(&g_last_error);
}

pub export fn ysp_free(ptr: ?*anyopaque) callconv(.C) void {
    if (ptr) |p| std.c.free(p);
}

pub export fn ysp_zstd_decompress(src: ?[*]const u8, len: c_int, out_ref: ?*?[*]u8, out_len_ref: ?*c_int) callconv(.C) c_int {
    clearError();
    if (src == null or len <= 0 or out_ref == null or out_len_ref == null) {
        setError("invalid argument", .{});
        return YSP_ERR_INVALID_ARGUMENT;
    }
    const out = out_ref.?;
    const out_len = out_len_ref.?;
    const input = src.?[0..@intCast(len)];
    const decoded = zstd.decompress.decodeAlloc(std.heap.c_allocator, input, false, 1 << 30) catch |err| {
        setError("zstd decompress failed: {s}", .{@errorName(err)});
        return YSP_ERR_ZSTD;
    };
    defer std.heap.c_allocator.free(decoded);
    if (!copyOut(decoded, out, out_len)) {
        setError("out of memory", .{});
        return YSP_ERR_OUT_OF_MEMORY;
    }
    return YSP_OK;
}

pub export fn ysp_legacy_v3_decode(file: ?[*]const u8, len: c_int, out_ref: ?*?[*]u8, out_len_ref: ?*c_int) callconv(.C) c_int {
    clearError();
    if (file == null or len <= 0 or out_ref == null or out_len_ref == null) {
        setError("invalid argument", .{});
        return YSP_ERR_INVALID_ARGUMENT;
    }
    const out = out_ref.?;
    const out_len = out_len_ref.?;
    out.* = null;
    out_len.* = 0;

    const bytes = file.?[0..@intCast(len)];
    const crypto = decodeCrypto(std.heap.c_allocator, bytes) catch |err| return reportError(err);
    defer std.heap.c_allocator.free(crypto.buffer);
    const frame = crypto.buffer[crypto.frame_offset..];

    const decoded = zstd.decompress.decodeAlloc(std.heap.c_allocator, frame, false, 1 << 30) catch {
        if (!copyOut(frame, out, out_len)) {
            setError("out of memory", .{});
            return YSP_ERR_OUT_OF_MEMORY;
        }
        return YSP_WASHED_ZSTD_FRAME;
    };
    defer std.heap.c_allocator.free(decoded);
    if (!copyOut(decoded, out, out_len)) {
        setError("out of memory", .{});
        return YSP_ERR_OUT_OF_MEMORY;
    }
    return YSP_OK;
}
