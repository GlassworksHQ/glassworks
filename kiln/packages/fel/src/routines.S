@ SPDX-License-Identifier: MIT
@ Copyright (c) 2026 Glassworks contributors
@ Written clean-room from docs/research/kiln-cleanroom/fel-spec.md (#355), with the encodings taken
@ from the ARM Architecture Reference Manual, ARMv7-A/R (DDI 0406C), sections A5 and A8.
@
@ The routines Kiln runs on the A133 in FEL, in A32 (ARM state). Each one is uploaded on its own to
@ the scratch area at 0x21000 and started with a FEL execute there. The boot ROM calls it in
@ Supervisor mode with lr pointing back into the ROM. Parameters are the literal words after the
@ code, and results are stored next to them for the host to read back. Only r0..r2 are used, so
@ sp and r4..r11 stay intact.
@
@ src/routines.ts holds the assembled words. To check that they match this file:
@   kiln/packages/fel/scripts/check-routines.sh

	.syntax unified
	.arm
	.text

@ Read a word: result = *(u32 *)addr.
	.global read_word
read_word:
	add	r1, pc, #12		@ r1 = &rw_addr (pc reads as this instruction + 8)
	ldr	r0, [r1]		@ r0 = addr
	ldr	r0, [r0]		@ r0 = *addr
	str	r0, [r1, #4]		@ rw_result = r0
	bx	lr
rw_addr:
	.word	0			@ parameter: the address to read
rw_result:
	.word	0			@ result

@ Read SCTLR (CP15 c1, c0, opc1 0, opc2 0).
	.global read_sctlr
read_sctlr:
	mrc	p15, 0, r0, c1, c0, 0	@ r0 = SCTLR
	add	r1, pc, #4		@ r1 = &rs_result
	str	r0, [r1]
	bx	lr
rs_result:
	.word	0			@ result

@ Warm reset into AArch64 at `entry`. Never returns: the core resets from WFI.
	.global warm_reset64
warm_reset64:
	add	r1, pc, #40		@ r1 = &wr_rvbar
	ldr	r0, [r1]		@ r0 = RVBAR's address
	ldr	r2, [r1, #4]		@ r2 = entry
	str	r2, [r0]		@ RVBAR = entry
	dsb	sy			@ the RVBAR write completes
	isb	sy
	mrc	p15, 0, r0, c12, c0, 2	@ r0 = RMR (AArch32)
	orr	r0, r0, #3		@ RR (bit 1): request a warm reset; AA64 (bit 0): into AArch64
	mcr	p15, 0, r0, c12, c0, 2	@ RMR = r0
	isb	sy
1:	wfi
	b	1b
wr_rvbar:
	.word	0			@ parameter: RVBAR's address (0x08100040 on the A133)
wr_entry:
	.word	0			@ parameter: the AArch64 entry point
