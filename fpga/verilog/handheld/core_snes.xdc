########################################
# SDRAM interface timing (SNES core)
########################################
# The SDRAM runs at 2x the system clock (42.955 MHz, 23.28 ns) on PLL output
# 1. Its clock pin carries PLL output 2: the same clock delayed by 14 ns
# (`HandheldSnes.SdramClockPhaseNs`), forwarded through a BUFG and an OBUF.
# The controller launches commands, address and write data from plain flops
# on the rising edge of output 1, and captures read data on its FALLING
# edge (`readCaptureFalling` in HandheldSnes), in a register placed in the
# IOB below so that the pin path does not move with the placement.
#
# Why 2x: the interface has no source-synchronous I/O, so its timing is the
# routed pin paths, which spread from about 2 to 13 ns (outputs) and 0.5 to
# 6 ns (inputs) across the corners, with the pin clock lagging the flop
# clock by 3-5.5 ns. At 4x (11.64 ns) that is most of a period and no clock
# phase gives every pin the same edge. At 2x with the 14 ns phase the chip's
# edge sits 4-13 ns after the outputs settle and 8-12 ns before they change
# again, and the falling-edge capture sits 5-8 ns inside the read data
# window, at both corners.
#
# Chip budget (PC133-class SDR SDRAM): input setup 2.0 ns, input hold 1.0 ns,
# clock-to-data 6.0 ns, data hold 2.5 ns. Vivado's default edge relationship
# is the intended one for every path: an output launched on a rising edge is
# sampled at the next pin-clock edge (nominally 14 ns later), and read data
# launched on a pin-clock edge (with CAS latency 2 the chip drives a beat
# from the edge after the READ, to be valid at the second) is captured at
# the next falling flop edge (nominally 20.9 ns later); on the board the
# beats change within the first quarter cycle after the controller's rising
# edge. Check the sdram_clk_pin rows of the inter-clock table in the timing
# summary after every build.
create_generated_clock -name sdram_clk_pin -source [get_pins handheld_top/core/pll/pll/CLKOUT2] -divide_by 1 [get_ports sdram_clk]
set sdram_outputs [get_ports {sdram_a[*] sdram_bs[*] sdram_dq[*] sdram_cke[*] sdram_cs_n[*] sdram_cas_n sdram_ras_n sdram_we_n sdram_ldqm sdram_udqm}]
set_output_delay -clock sdram_clk_pin -max  2.0 $sdram_outputs
set_output_delay -clock sdram_clk_pin -min -1.0 $sdram_outputs
set_input_delay  -clock sdram_clk_pin -max  6.0 [get_ports {sdram_dq[*]}]
set_input_delay  -clock sdram_clk_pin -min  2.5 [get_ports {sdram_dq[*]}]
# The falling-edge capture register in the IOB: a fixed pad-to-flop path.
set_property IOB TRUE [get_cells -hier -filter {NAME =~ *sdram/dataIn_reg*}]

# Asynchronous SRAM (WRAM / BSRAM, IS61WV25616BLL-10). The controller gives
# every access a full 46.56 ns cycle, with the write pulse (an ODDR in the
# OLOGIC) in the second half, so the chip's own timing is met with more
# than 15 ns to spare on paper. What is not fine is skew between the
# controller's output registers: left in the fabric they reach the pins
# anywhere from 2 to 9 ns after the clock, differently on every placement,
# and one placement whose upper byte enable arrived a few hundred
# picoseconds later than the rest corrupted WRAM writes on every boot
# (Lufia II's intro, September 2026; found by bisecting cell moves down to
# that one register). The controller therefore has a second register stage
# for the pins (`registeredOutputs`), packed into the I/O blocks here, so
# that address, data and control all have the same fixed delay as the
# write-enable ODDR, and the read data is captured in the ILOGIC. The
# budgets below (system clock edge, insertion delay included) fail a build
# in which a register ever leaves its I/O block: outputs within 11.5 ns of
# the edge (an I/O block register makes it in about 9.5 ns, a fabric one
# takes 12 or more); the write enable is excluded, being launched from
# both edges by the ODDR. The read data is valid from 22 ns after the edge
# (address out, 10 ns access, trace) and holds past 10 ns after the next
# one (output hold from the address change).
set sram_clock [get_clocks -of_objects [get_pins handheld_top/core/pll/pll/CLKOUT0]]
set_property IOB TRUE [get_ports {sram_a[*] sram_io[*] sram_ub_n sram_lb_n sram_oe_n}]
set_output_delay -clock $sram_clock -max 35.0 [get_ports {sram_a[*] sram_ub_n sram_lb_n sram_oe_n}]
set_output_delay -clock $sram_clock -max 33.0 [get_ports {sram_io[*]}]
set_output_delay -clock $sram_clock -min -1.0 [get_ports {sram_a[*] sram_io[*] sram_ub_n sram_lb_n sram_oe_n}]
set_input_delay  -clock $sram_clock -max 22.0 [get_ports {sram_io[*]}]
set_input_delay  -clock $sram_clock -min 10.0 [get_ports {sram_io[*]}]
