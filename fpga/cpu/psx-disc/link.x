/* The sub-CPU's RAM (PsxDisc.RamBytes): the program, its data and the stack.
   The RAM holds the program from configuration on, so nothing is copied at
   start; .bss is zeroed by _start. */
MEMORY
{
  RAM (rwx) : ORIGIN = 0x00000000, LENGTH = 16K
}

ENTRY(_start)

SECTIONS
{
  .text : { KEEP(*(.text.start)) *(.text .text.*) } > RAM
  .rodata : { *(.rodata .rodata.* .srodata .srodata.*) } > RAM
  .data : { *(.data .data.* .sdata .sdata.*) } > RAM
  .bss (NOLOAD) : ALIGN(4)
  {
    _sbss = .;
    *(.sbss .sbss.* .bss .bss.* COMMON)
    . = ALIGN(4);
    _ebss = .;
  } > RAM
  /* At least this much is left for the stack. */
  ASSERT(_ebss <= ORIGIN(RAM) + LENGTH(RAM) - 4K, "the program leaves less than 4 KiB for the stack")
  _stack_top = ORIGIN(RAM) + LENGTH(RAM);
  /DISCARD/ : { *(.eh_frame .eh_frame_hdr) }
}
