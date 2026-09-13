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
