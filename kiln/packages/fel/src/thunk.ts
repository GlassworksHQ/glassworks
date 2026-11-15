// The fel-to-spl thunk from sunxi-tools, thunks/fel-to-spl-thunk.S, assembled (the word list is
// thunks/fel-to-spl-thunk.h at commit 34dfa20f8284273085666436e4cac09af19646c8,
// https://github.com/linux-sunxi/sunxi-tools). That file is MIT licensed, unlike the rest of
// sunxi-tools:
//
// Copyright © 2015 Siarhei Siamashka <siarhei.siamashka@gmail.com>
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
// associated documentation files (the "Software"), to deal in the Software without restriction,
// including without limitation the rights to use, copy, modify, merge, publish, distribute,
// sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice (including the next paragraph) shall be
// included in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
// NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
// NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
// DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
// OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
//
// What it does and how to call it: docs/research/kiln-cleanroom/fel-spec.md, "The SPL thunk".

/** 69 ARM words. Followed in memory by the SPL address and the swap table (see the spec). */
export const FEL_TO_SPL_THUNK: readonly number[] = [] = [
  0xea000015, 0xffffffff, 0xffffffff, 0xffffffff, 0xffffffff, 0xffffffff, 0xffffffff, 0xffffffff,
  0xffffffff, 0xe1a00000, 0xe28f40e8, 0xe4940004, 0xe4941004, 0xe4946004, 0xe3560000, 0x012fff1e,
  0xe5902000, 0xe5913000, 0xe2566004, 0xe4812004, 0xe4803004, 0x1afffff9, 0xeafffff3, 0xe59f80b0,
  0xe24f0044, 0xe520d004, 0xe1a0d000, 0xe10f2000, 0xe92d4004, 0xe38220c0, 0xe121f002, 0xee112f10,
  0xe3120004, 0x03120a01, 0x1a000013, 0xebffffe5, 0xe59f706c, 0xe1a00008, 0xe5905010, 0xe4902004,
  0xe2555004, 0xe0877002, 0x1afffffb, 0xe598200c, 0xe0577082, 0x1a00000b, 0xe59f2048, 0xe5882008,
  0xee072f9a, 0xee102f10, 0xe202280f, 0xe3520806, 0xce072f95, 0xe12fff38, 0xea000004, 0xe59f2028,
  0xe5882008, 0xea000002, 0xe59f2020, 0xe5882008, 0xebffffcc, 0xe8bd4004, 0xe121f002, 0xe59dd000,
  0xe12fff1e, 0x5f0a6c39, 0x4c45462e, 0x3f3f3f2e, 0x4441422e,
];
