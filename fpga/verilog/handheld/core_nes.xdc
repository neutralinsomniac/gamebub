########################################
# SDRAM interface timing (NES core)
########################################
# Single W9825G6KH-6 chip (chip 0; chip 1 on rev 4 is held in power-down),
# run at clk4x (85.909 MHz, 11.640 ns; PLL output 1). Every output comes from
# an IOB register (`psx.SdramEngine`), and the chip's clock is PLL output 2
# (clk4x delayed by 264 deg = 8.536 ns), forwarded non-inverted through an
# ODDR (`handheld_top/core/sdramClockOut`).
#
# Output timing: launched at t=0 on clk4x rising edge, sampled by the chip at
# t=8.536 ns (setup margin > 5.5 ns, hold margin > 0.7 ns against W9825G6KH-6
# 2.0 ns setup / 1.0 ns hold).
#
# Read timing (CAS latency 2, captureLatency = 4): chip launches beat 0 from
# chip edge 1 (t=20.176 ns), valid at the FPGA pin by 20.176 + 6.5 = 26.676 ns
# and held until 31.816 + 3.0 = 34.816 ns. Accounting for ODDR+OBUF clock
# forwarding (~3.45 ns) and IBUF input delay (~1.49 ns), the beat arrives at
# the ILOGIC register well before clk4x edge 3 (t=34.920 ns, one clk4x cycle
# later than Vivado's single-cycle default -> multicycle -setup 2) and holds
# past it (> 2.8 ns setup, > 0.8 ns hold).
create_generated_clock -name sdram_clk_pin -source [get_pins handheld_top/core/sdramClockOut/inner/C] -divide_by 1 [get_ports sdram_clk]
set sdram_outputs [get_ports {sdram_a[*] sdram_bs[*] sdram_dq[*] sdram_cs_n[0] sdram_cas_n sdram_ras_n sdram_we_n sdram_ldqm sdram_udqm}]
set_output_delay -clock sdram_clk_pin -max  2.0 $sdram_outputs
set_output_delay -clock sdram_clk_pin -min -1.0 $sdram_outputs
set_input_delay  -clock sdram_clk_pin -max  6.5 [get_ports {sdram_dq[*]}]
set_input_delay  -clock sdram_clk_pin -min  3.0 [get_ports {sdram_dq[*]}]
set_multicycle_path -setup 2 -from [get_clocks sdram_clk_pin]
set_property IOB TRUE [get_ports {sdram_a[*] sdram_bs[*] sdram_dq[*] sdram_cs_n[0] sdram_cas_n sdram_ras_n sdram_we_n sdram_ldqm sdram_udqm}]
# Keep the 16 per-pin tristate FDREs separate so each packs into its OLOGIC.
set_property DONT_TOUCH TRUE [get_cells -hier -filter {NAME =~ handheld_top/core/memory/engine/dqTristatePin*}]
set_property SLEW FAST [get_ports {sdram_clk sdram_a[*] sdram_bs[*] sdram_dq[*] sdram_cs_n[*] sdram_cke[*] sdram_cas_n sdram_ras_n sdram_we_n sdram_ldqm sdram_udqm}]

# Chip 1 select and clock enables are constant.
set_false_path -to [get_ports {sdram_cs_n[1] sdram_cke[*]}]

########################################
# Synchronous 4:1 clk_sys -> clk4x capture boundary (NesMemory)
########################################
# clk_sys (21.477 MHz) and clk4x (85.909 MHz) come from the same PLL with a
# fixed 4:1 phase relationship. NesMemory detects each clk_sys edge via
# `toggle1x -> toggle4x` (1 clk4x cycle) and samples all clk_sys-driven
# request/address/data/control signals on `first = toggle4x != toggle4xPrev`,
# which is active only on the 2nd clk4x edge (23.280 ns) after each clk_sys
# edge. Give those capture registers their architectural 2-cycle setup window
# while keeping `toggle4x_reg` itself strictly single-cycle.
set clk4x [get_clocks -of_objects [get_pins handheld_top/core/pll/pll/CLKOUT1]]
set_multicycle_path -setup 2 -end -from [get_clocks clk_sys] -to $clk4x
set_multicycle_path -hold  1 -end -from [get_clocks clk_sys] -to $clk4x
set_multicycle_path -setup 1 -end -from [get_clocks clk_sys] -to [get_cells handheld_top/core/memory/toggle4x_reg]
set_multicycle_path -hold  0 -end -from [get_clocks clk_sys] -to [get_cells handheld_top/core/memory/toggle4x_reg]
