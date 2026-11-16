// SPDX-License-Identifier: MIT
// Copyright (c) 2026 the Glassworks contributors
//
// Written clean-room from docs/research/kiln-cleanroom/bootpatch-spec.md ("The WebAssembly module").
//
// The compressors Kiln's boot patch needs, over a small C-like ABI that src/compress.ts calls. The
// output has to match the CLI's magiskboot byte for byte, so this uses the same public crates at the
// versions pinned in Cargo.lock: flate2 on zlib-rs, and lzma-rust2.

use std::cell::RefCell;
use std::io::{Read, Write};

use flate2::Compression;
use flate2::read::{DeflateDecoder, MultiGzDecoder};
use flate2::write::GzEncoder;
use lzma_rust2::{CheckType, FilterType, XzOptions, XzReader, XzWriter};

thread_local! {
    static OUT: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
}

fn input<'a>(ptr: *const u8, len: usize) -> &'a [u8] {
    if len == 0 { &[] } else { unsafe { std::slice::from_raw_parts(ptr, len) } }
}

fn finish(r: Option<Vec<u8>>) -> u32 {
    match r {
        Some(v) => {
            OUT.with(|o| *o.borrow_mut() = v);
            0
        }
        None => 1,
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn bp_alloc(len: usize) -> *mut u8 {
    let mut v = Vec::<u8>::with_capacity(len);
    let p = v.as_mut_ptr();
    std::mem::forget(v);
    p
}

#[unsafe(no_mangle)]
pub extern "C" fn bp_free(ptr: *mut u8, len: usize) {
    unsafe { drop(Vec::from_raw_parts(ptr, 0, len)) }
}

#[unsafe(no_mangle)]
pub extern "C" fn bp_out_ptr() -> *const u8 {
    OUT.with(|o| o.borrow().as_ptr())
}

#[unsafe(no_mangle)]
pub extern "C" fn bp_out_len() -> usize {
    OUT.with(|o| o.borrow().len())
}

#[unsafe(no_mangle)]
pub extern "C" fn bp_out_free() {
    OUT.with(|o| *o.borrow_mut() = Vec::new());
}

/// The BCJ filter for `d`: 0 none, 1 x86, 2 ARM, 3 ARM-Thumb, 4 ARM64, 5 RISC-V. The rules and
/// thresholds are the spec's, which pins them to what magiskboot picks.
fn detect_bcj(d: &[u8]) -> u32 {
    if d.len() >= 20 && d.starts_with(b"\x7fELF") {
        let machine = match d[5] {
            1 => u16::from_le_bytes([d[18], d[19]]),
            2 => u16::from_be_bytes([d[18], d[19]]),
            _ => return 0,
        };
        return match machine {
            0x03 | 0x3e => 1,
            0x28 => 3,
            0xb7 => 4,
            0xf3 => 5,
            _ => 0,
        };
    }
    if d.len() >= 0x3c && &d[0x38..0x3c] == b"ARM\x64" {
        return 4;
    }
    if d.len() >= 0x34 && &d[0x30..0x34] == b"RSC\x05" {
        return 5;
    }
    let window = &d[..d.len().min(256)];
    if window.len() < 64 {
        return 0;
    }
    let words = window.len() / 4;
    let a32 = window.chunks_exact(4).filter(|w| w[3] >> 4 >= 0xe).count();
    if a32 * 100 / words >= 75 { 2 } else { 0 }
}

#[unsafe(no_mangle)]
pub extern "C" fn bp_bcj(ptr: *const u8, len: usize) -> u32 {
    detect_bcj(input(ptr, len))
}

#[unsafe(no_mangle)]
pub extern "C" fn bp_gzip(ptr: *const u8, len: usize) -> u32 {
    let d = input(ptr, len);
    finish((|| {
        let mut e = GzEncoder::new(Vec::new(), Compression::best());
        e.write_all(d).ok()?;
        e.finish().ok()
    })())
}

fn read_all(mut r: impl Read) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    r.read_to_end(&mut out).ok()?;
    Some(out)
}

#[unsafe(no_mangle)]
pub extern "C" fn bp_gunzip(ptr: *const u8, len: usize) -> u32 {
    finish(read_all(MultiGzDecoder::new(input(ptr, len))))
}

#[unsafe(no_mangle)]
pub extern "C" fn bp_inflate(ptr: *const u8, len: usize) -> u32 {
    finish(read_all(DeflateDecoder::new(input(ptr, len))))
}

#[unsafe(no_mangle)]
pub extern "C" fn bp_xz(ptr: *const u8, len: usize, sniff: u32) -> u32 {
    let d = input(ptr, len);
    let mut opts = XzOptions::with_preset(6);
    opts.set_check_sum_type(CheckType::Crc32);
    let filter = match detect_bcj(&d[..d.len().min(sniff as usize)]) {
        1 => Some(FilterType::BcjX86),
        2 => Some(FilterType::BcjArm),
        3 => Some(FilterType::BcjArmThumb),
        4 => Some(FilterType::BcjArm64),
        5 => Some(FilterType::BcjRiscv),
        _ => None,
    };
    if let Some(f) = filter {
        opts.prepend_pre_filter(f, 0);
    }
    finish((|| {
        let mut w = XzWriter::new(Vec::new(), opts).ok()?;
        w.write_all(d).ok()?;
        w.finish().ok()
    })())
}

#[unsafe(no_mangle)]
pub extern "C" fn bp_unxz(ptr: *const u8, len: usize) -> u32 {
    finish(read_all(XzReader::new(input(ptr, len), true)))
}
