########################################
# SDRAM interface timing (PSX core)
########################################
# Both chips share one bus, run at the core's clk3x (101.5625 MHz, 9.846 ns;
# MMCM output 0, `HandheldPsx.Clk3xDivider`). Every output comes from a
# register in its I/O block (`IOB` attributes in `psx.SdramEngine`, and
# below), and the chips' clock is a copy of clk3x 1.6 ns later (MMCM output
# 5, `HandheldPsx.SdramClockPhaseNs`), inverted and forwarded through an
# ODDR (handheld_top/core/sdramClockOut): commands, addresses and write
# data leave on clk3x's rising edge and the chips sample them on the
# forwarded rising edge, half a cycle and 1.6 ns later, both through the
# same kind of I/O path.
#
# Read data (CAS latency 2): a beat comes out tAC after the chip's edge
# that follows the READ and holds until tOH after the next one. It is
# captured in the I/O block on clk3x's rising edge about 1.5 cycles after
# the chip's edge that launched it, one clk3x cycle later than Vivado would
# assume (the multicycle path below; the hold check stays where Vivado then
# puts it: the next beat, launched a cycle later, must not reach the
# capturing edge). `SdramEngine.Config.captureLatency` counts the cycles to
# that edge. The 1.6 ns delay of the chips' clock centers this window: with
# the clock exactly half a cycle after clk3x, the next beat came 0.8 ns too
# early for the capture (setup had 3 ns to spare), and the outputs have
# margin to give.
#
# Chip budget (W9825G6KH-6, CL2): input setup 1.5 ns, hold 0.8 ns; output
# access 6.0 ns, hold 3.0 ns. Board traces add a few hundred picoseconds
# each way (in the margins below).
create_generated_clock -name sdram_clk_pin -source [get_pins handheld_top/core/sdramClockOut/inner/C] -divide_by 1 -invert [get_ports sdram_clk]
set sdram_outputs [get_ports {sdram_a[*] sdram_bs[*] sdram_dq[*] sdram_cs_n[*] sdram_cas_n sdram_ras_n sdram_we_n sdram_ldqm sdram_udqm}]
set_output_delay -clock sdram_clk_pin -max  2.0 $sdram_outputs
set_output_delay -clock sdram_clk_pin -min -1.0 $sdram_outputs
set_input_delay  -clock sdram_clk_pin -max  6.5 [get_ports {sdram_dq[*]}]
set_input_delay  -clock sdram_clk_pin -min  3.0 [get_ports {sdram_dq[*]}]
set_multicycle_path -setup 2 -from [get_clocks sdram_clk_pin]
set_property IOB TRUE [get_ports {sdram_a[*] sdram_bs[*] sdram_dq[*] sdram_cs_n[*] sdram_cas_n sdram_ras_n sdram_we_n sdram_ldqm sdram_udqm}]
# The data pins' tristate registers (FDRE primitives, see `SdramEngine`):
# sixteen equal registers, which synthesis would otherwise merge into one.
set_property DONT_TOUCH TRUE [get_cells -hier -filter {NAME =~ handheld_top/core/memory/engine/dqTristatePin*}]
set_property SLEW FAST [get_ports {sdram_clk sdram_a[*] sdram_bs[*] sdram_dq[*] sdram_cs_n[*] sdram_cke[*] sdram_cas_n sdram_ras_n sdram_we_n sdram_ldqm sdram_udqm}]

# The clock enables are constant (high).
set_false_path -to [get_ports {sdram_cke[*]}]

########################################
# Asynchronous SRAM (SPU RAM, IS61WV25616BLL-10)
########################################
# The controller (`AsyncSramController` with `registeredOutputs`) runs at
# clk1x (33.854 MHz, 29.54 ns; MMCM output 2) and gives every access a
# full cycle with the pins driven from I/O block registers (see
# core_snes.xdc for why). The budgets fail a build in which a register
# leaves its I/O block: outputs within 11.5 ns of the edge (an I/O block
# register makes it in about 9.5 ns, a fabric one takes 12 or more); the
# write enable is excluded, being launched from both edges by the ODDR. The
# read data is valid from 22 ns after the edge (address out, 10 ns access,
# trace) and holds past 10 ns after the next one.
set sram_clock [get_clocks -of_objects [get_pins handheld_top/core/mmcm/mmcm/CLKOUT2]]
set_property IOB TRUE [get_ports {sram_a[*] sram_io[*] sram_ub_n sram_lb_n sram_oe_n}]
set_output_delay -clock $sram_clock -max 18.0 [get_ports {sram_a[*] sram_ub_n sram_lb_n sram_oe_n}]
set_output_delay -clock $sram_clock -max 16.0 [get_ports {sram_io[*]}]
set_output_delay -clock $sram_clock -min -1.0 [get_ports {sram_a[*] sram_io[*] sram_ub_n sram_lb_n sram_oe_n}]
set_input_delay  -clock $sram_clock -max 22.0 [get_ports {sram_io[*]}]
set_input_delay  -clock $sram_clock -min 10.0 [get_ports {sram_io[*]}]
