-- Game Bub wrapper around the MiSTer PSX core (psx_mister).
--
-- Not part of the upstream core: it fixes the options that the Game Bub port
-- does not offer to constants, so that synthesis removes the logic behind
-- them (the core has to fit an XC7A100T), and exposes the remaining ports
-- with plain vector types for the Chisel glue
-- (net.gamebub.core.psx.HandheldPsx, through psx.PsxCore).
--
-- Fixed here:
-- * Video: the core's own video output does not fetch VRAM (videoout_on
--   off); the glue scans the display area out of VRAM itself. The timing
--   generator runs synchronously on clk1x (syncVideoOut), so clkvid is
--   unused. No 24-bit rendering, texture filtering, 480p / widescreen hacks,
--   debug overlays or crosshairs.
-- * Memory: 2 MB main RAM; SPU RAM on the external port (spuram_*, which the
--   glue puts in the SRAM). No turbo modes (the turbo data cache is not
--   synthesized).
-- * Input: one controller on port 1 (digital, analog or DualShock, chosen by
--   the glue); no multitap, mouse, light guns, wheels or SNAC.
-- * No EXE loading and no cheats.

library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

entity psx_gamebub is
   port
   (
      clk1x                 : in  std_logic;
      clk2x                 : in  std_logic;
      clk3x                 : in  std_logic;
      reset                 : in  std_logic;
      pause                 : in  std_logic;
      isPaused              : out std_logic;

      -- settings
      fastboot              : in  std_logic;
      ditherOff             : in  std_logic;
      REPRODUCIBLEGPUTIMING : in  std_logic;
      LIMITREADSPEED        : in  std_logic;
      fixedVBlank           : in  std_logic;
      SPUIRQTrigger         : in  std_logic;
      REPRODUCIBLESPUDMA    : in  std_logic;
      biosregion            : in  std_logic_vector(1 downto 0);

      -- main RAM / BIOS (SDRAM)
      ram_refresh           : out std_logic;
      ram_dataWrite         : out std_logic_vector(31 downto 0);
      ram_dataRead32        : in  std_logic_vector(31 downto 0);
      ram_Adr               : out std_logic_vector(24 downto 0);
      ram_cntDMA            : out std_logic_vector(1 downto 0);
      ram_be                : out std_logic_vector(3 downto 0);
      ram_rnw               : out std_logic;
      ram_ena               : out std_logic;
      ram_dma               : out std_logic;
      ram_cache             : out std_logic;
      ram_done              : in  std_logic;
      ram_dmafifo_adr       : out std_logic_vector(22 downto 0);
      ram_dmafifo_data      : out std_logic_vector(31 downto 0);
      ram_dmafifo_empty     : out std_logic;
      ram_dmafifo_read      : in  std_logic;
      cache_wr              : in  std_logic_vector(3 downto 0);
      cache_data            : in  std_logic_vector(31 downto 0);
      cache_addr            : in  std_logic_vector(7 downto 0);
      dma_wr                : in  std_logic;
      dma_reqprocessed      : in  std_logic;
      dma_data              : in  std_logic_vector(31 downto 0);

      -- VRAM, memory cards, save states (the MiSTer's DDR3 port)
      DDRAM_BUSY            : in  std_logic;
      DDRAM_BURSTCNT        : out std_logic_vector(7 downto 0);
      DDRAM_ADDR            : out std_logic_vector(28 downto 0);
      DDRAM_DOUT            : in  std_logic_vector(63 downto 0);
      DDRAM_DOUT_READY      : in  std_logic;
      DDRAM_RD              : out std_logic;
      DDRAM_DIN             : out std_logic_vector(63 downto 0);
      DDRAM_BE              : out std_logic_vector(7 downto 0);
      DDRAM_WE              : out std_logic;

      -- CD
      region                : in  std_logic_vector(1 downto 0);
      region_out            : out std_logic_vector(1 downto 0);
      hasCD                 : in  std_logic;
      LIDopen               : in  std_logic;
      trackinfo_data        : in  std_logic_vector(31 downto 0);
      trackinfo_addr        : in  std_logic_vector(8 downto 0);
      trackinfo_write       : in  std_logic;
      resetFromCD           : out std_logic;
      cd_hps_req            : out std_logic;
      cd_hps_lba            : out std_logic_vector(31 downto 0);
      cd_hps_ack            : in  std_logic;
      cd_hps_write          : in  std_logic;
      cd_hps_data           : in  std_logic_vector(15 downto 0);

      -- SPU RAM
      spuram_dataWrite      : out std_logic_vector(31 downto 0);
      spuram_Adr            : out std_logic_vector(18 downto 0);
      spuram_be             : out std_logic_vector(3 downto 0);
      spuram_rnw            : out std_logic;
      spuram_ena            : out std_logic;
      spuram_dataRead       : in  std_logic_vector(31 downto 0);
      spuram_done           : in  std_logic;

      -- memory cards (the MiSTer's SD block interface, 512-byte sectors)
      memcard_changed       : out std_logic;
      saving_memcard        : out std_logic;
      memcard1_load         : in  std_logic;
      memcard2_load         : in  std_logic;
      memcard_save          : in  std_logic;
      memcard1_mounted      : in  std_logic;
      memcard1_available    : in  std_logic;
      memcard1_rd           : out std_logic;
      memcard1_wr           : out std_logic;
      memcard1_lba          : out std_logic_vector(6 downto 0);
      memcard1_ack          : in  std_logic;
      memcard1_write        : in  std_logic;
      memcard1_addr         : in  std_logic_vector(8 downto 0);
      memcard1_dataIn       : in  std_logic_vector(15 downto 0);
      memcard1_dataOut      : out std_logic_vector(15 downto 0);
      memcard2_mounted      : in  std_logic;
      memcard2_available    : in  std_logic;
      memcard2_rd           : out std_logic;
      memcard2_wr           : out std_logic;
      memcard2_lba          : out std_logic_vector(6 downto 0);
      memcard2_ack          : in  std_logic;
      memcard2_write        : in  std_logic;
      memcard2_addr         : in  std_logic_vector(8 downto 0);
      memcard2_dataIn       : in  std_logic_vector(15 downto 0);
      memcard2_dataOut      : out std_logic_vector(15 downto 0);

      -- video
      isPal                 : in  std_logic;
      pal60                 : in  std_logic;
      hsync                 : out std_logic;
      vsync                 : out std_logic;
      hblank                : out std_logic;
      vblank                : out std_logic;
      DisplayWidth          : out std_logic_vector(10 downto 0);
      DisplayHeight         : out std_logic_vector( 9 downto 0);
      DisplayOffsetX        : out std_logic_vector( 9 downto 0);
      DisplayOffsetY        : out std_logic_vector( 8 downto 0);
      video_interlace       : out std_logic;
      video_isPal           : out std_logic;
      -- GPUSTAT: display disabled, 24-bit color (see gpu.vhd)
      displayDisable        : out std_logic;
      colorDepth24          : out std_logic;
      -- GP1(07h): the vertical display range (first line 9-0, end 19-10)
      vDisplayRange         : out std_logic_vector(19 downto 0);
      -- GP1(06h): the horizontal display range (start 11-0, end 23-12)
      hDisplayRange         : out std_logic_vector(23 downto 0);
      video_hResMode        : out std_logic_vector(2 downto 0);

      -- controller on port 1, active high: 0 cross, 1 circle, 2 square,
      -- 3 triangle, 4 L1, 5 R1, 6 L2, 7 R2, 8 select, 9 start, 10 up,
      -- 11 down, 12 left, 13 right, 14 L3, 15 R3
      PadPortAnalog         : in  std_logic;
      PadPortDS             : in  std_logic;
      pad_buttons           : in  std_logic_vector(15 downto 0);
      pad_lx                : in  std_logic_vector(7 downto 0);
      pad_ly                : in  std_logic_vector(7 downto 0);
      pad_rx                : in  std_logic_vector(7 downto 0);
      pad_ry                : in  std_logic_vector(7 downto 0);
      pad_rumble            : out std_logic_vector(15 downto 0);
      padMode               : out std_logic_vector(1 downto 0);

      -- sound
      sound_out_left        : out std_logic_vector(15 downto 0);
      sound_out_right       : out std_logic_vector(15 downto 0);

      -- save states
      save_state            : in  std_logic;
      load_state            : in  std_logic;
      savestate_number      : in  std_logic_vector(1 downto 0);
      state_loaded          : out std_logic;
      validSStates          : out std_logic_vector(3 downto 0);

      -- debug (see psx_top)
      debug_errors          : out std_logic_vector(15 downto 0);
      debug_pc              : out std_logic_vector(31 downto 0);
      debug_mdec            : out std_logic_vector(31 downto 0);
      debug_dma             : out std_logic_vector(31 downto 0);
      debug_flags           : out std_logic_vector(15 downto 0)
   );
end entity;

architecture arch of psx_gamebub is

   -- The core takes each button for players 4..1; only player 1 is used.
   function player1(b : std_logic) return std_logic_vector is
   begin
      return "000" & b;
   end function;

   signal DisplayWidth_u   : unsigned(10 downto 0);
   signal DisplayHeight_u  : unsigned( 9 downto 0);
   signal DisplayOffsetX_u : unsigned( 9 downto 0);
   signal DisplayOffsetY_u : unsigned( 8 downto 0);

begin

   DisplayWidth   <= std_logic_vector(DisplayWidth_u);
   DisplayHeight  <= std_logic_vector(DisplayHeight_u);
   DisplayOffsetX <= std_logic_vector(DisplayOffsetX_u);
   DisplayOffsetY <= std_logic_vector(DisplayOffsetY_u);

   ipsx : entity work.psx_mister
   port map
   (
      clk1x                 => clk1x,
      clk2x                 => clk2x,
      clk3x                 => clk3x,
      clkvid                => clk1x,
      reset                 => reset,
      isPaused              => isPaused,
      pause                 => pause,
      hps_busy              => '0',
      loadExe               => '0',
      exe_initial_pc        => (others => '0'),
      exe_initial_gp        => (others => '0'),
      exe_load_address      => (others => '0'),
      exe_file_size         => (others => '0'),
      exe_stackpointer      => (others => '0'),
      fastboot              => fastboot,
      ram8mb                => '0',
      TURBO_MEM             => '0',
      TURBO_COMP            => '0',
      TURBO_CACHE           => '0',
      TURBO_CACHE50         => '0',
      REPRODUCIBLEGPUTIMING => REPRODUCIBLEGPUTIMING,
      INSTANTSEEK           => '0',
      FORCECDSPEED          => "000",
      LIMITREADSPEED        => LIMITREADSPEED,
      IGNORECDDMATIMING     => '0',
      ditherOff             => ditherOff,
      interlaced480pHack    => '0',
      showGunCrosshairs     => '0',
      enableNeGconRumble    => '0',
      fpscountOn            => '0',
      cdslowOn              => '0',
      testSeek              => '0',
      pauseOnCDSlow         => '0',
      errorOn               => '0',
      LBAOn                 => '0',
      PATCHSERIAL           => '0',
      noTexture             => '0',
      textureFilter         => "00",
      textureFilterStrength => "00",
      textureFilter2DOff    => '0',
      dither24              => '0',
      render24              => '0',
      drawSlow              => '0',
      syncVideoOut          => '1',
      syncInterlace         => '0',
      rotate180             => '0',
      fixedVBlank           => fixedVBlank,
      vCrop                 => "00",
      hCrop                 => '0',
      SPUon                 => '1',
      SPUIRQTrigger         => SPUIRQTrigger,
      SPUSDRAM              => '1',
      REVERBOFF             => '0',
      REPRODUCIBLESPUDMA    => REPRODUCIBLESPUDMA,
      WIDESCREEN            => "00",
      oldGPU                => '0',
      -- RAM/BIOS interface
      biosregion            => biosregion,
      ram_refresh           => ram_refresh,
      ram_dataWrite         => ram_dataWrite,
      ram_dataRead32        => ram_dataRead32,
      ram_Adr               => ram_Adr,
      ram_cntDMA            => ram_cntDMA,
      ram_be                => ram_be,
      ram_rnw               => ram_rnw,
      ram_ena               => ram_ena,
      ram_dma               => ram_dma,
      ram_cache             => ram_cache,
      ram_done              => ram_done,
      ram_dmafifo_adr       => ram_dmafifo_adr,
      ram_dmafifo_data      => ram_dmafifo_data,
      ram_dmafifo_empty     => ram_dmafifo_empty,
      ram_dmafifo_read      => ram_dmafifo_read,
      cache_wr              => cache_wr,
      cache_data            => cache_data,
      cache_addr            => cache_addr,
      dma_wr                => dma_wr,
      dma_reqprocessed      => dma_reqprocessed,
      dma_data              => dma_data,
      -- vram/ddr3 interface
      DDRAM_BUSY            => DDRAM_BUSY,
      DDRAM_BURSTCNT        => DDRAM_BURSTCNT,
      DDRAM_ADDR            => DDRAM_ADDR,
      DDRAM_DOUT            => DDRAM_DOUT,
      DDRAM_DOUT_READY      => DDRAM_DOUT_READY,
      DDRAM_RD              => DDRAM_RD,
      DDRAM_DIN             => DDRAM_DIN,
      DDRAM_BE              => DDRAM_BE,
      DDRAM_WE              => DDRAM_WE,
      -- cd
      region                => region,
      region_out            => region_out,
      hasCD                 => hasCD,
      LIDopen               => LIDopen,
      fastCD                => '0',
      trackinfo_data        => trackinfo_data,
      trackinfo_addr        => trackinfo_addr,
      trackinfo_write       => trackinfo_write,
      resetFromCD           => resetFromCD,
      cd_hps_req            => cd_hps_req,
      cd_hps_lba            => cd_hps_lba,
      cd_hps_lba_sim        => open,
      cd_hps_ack            => cd_hps_ack,
      cd_hps_write          => cd_hps_write,
      cd_hps_data           => cd_hps_data,
      -- spuram
      spuram_dataWrite      => spuram_dataWrite,
      spuram_Adr            => spuram_Adr,
      spuram_be             => spuram_be,
      spuram_rnw            => spuram_rnw,
      spuram_ena            => spuram_ena,
      spuram_dataRead       => spuram_dataRead,
      spuram_done           => spuram_done,
      -- memcard
      memcard_changed       => memcard_changed,
      saving_memcard        => saving_memcard,
      memcard1_load         => memcard1_load,
      memcard2_load         => memcard2_load,
      memcard_save          => memcard_save,
      memcard1_mounted      => memcard1_mounted,
      memcard1_available    => memcard1_available,
      memcard1_rd           => memcard1_rd,
      memcard1_wr           => memcard1_wr,
      memcard1_lba          => memcard1_lba,
      memcard1_ack          => memcard1_ack,
      memcard1_write        => memcard1_write,
      memcard1_addr         => memcard1_addr,
      memcard1_dataIn       => memcard1_dataIn,
      memcard1_dataOut      => memcard1_dataOut,
      memcard2_mounted      => memcard2_mounted,
      memcard2_available    => memcard2_available,
      memcard2_rd           => memcard2_rd,
      memcard2_wr           => memcard2_wr,
      memcard2_lba          => memcard2_lba,
      memcard2_ack          => memcard2_ack,
      memcard2_write        => memcard2_write,
      memcard2_addr         => memcard2_addr,
      memcard2_dataIn       => memcard2_dataIn,
      memcard2_dataOut      => memcard2_dataOut,
      -- video
      videoout_on           => '0',
      isPal                 => isPal,
      pal60                 => pal60,
      hsync                 => hsync,
      vsync                 => vsync,
      hblank                => hblank,
      vblank                => vblank,
      DisplayWidth          => DisplayWidth_u,
      DisplayHeight         => DisplayHeight_u,
      DisplayOffsetX        => DisplayOffsetX_u,
      DisplayOffsetY        => DisplayOffsetY_u,
      video_ce              => open,
      video_interlace       => video_interlace,
      video_r               => open,
      video_g               => open,
      video_b               => open,
      video_isPal           => video_isPal,
      video_fbmode          => displayDisable,
      video_fb24            => colorDepth24,
      video_vDisplayRange   => vDisplayRange,
      video_hDisplayRange   => hDisplayRange,
      video_hResMode        => video_hResMode,
      video_frameindex      => open,
      -- Keys - all active high
      DSAltSwitchMode       => '0',
      PadPortEnable1        => '1',
      PadPortDigital1       => not (PadPortAnalog or PadPortDS),
      PadPortAnalog1        => PadPortAnalog,
      PadPortMouse1         => '0',
      PadPortGunCon1        => '0',
      PadPortneGcon1        => '0',
      PadPortWheel1         => '0',
      PadPortDS1            => PadPortDS,
      PadPortJustif1        => '0',
      PadPortStick1         => '0',
      PadPortPopn1          => '0',
      PadPortEnable2        => '0',
      PadPortDigital2       => '0',
      PadPortAnalog2        => '0',
      PadPortMouse2         => '0',
      PadPortGunCon2        => '0',
      PadPortneGcon2        => '0',
      PadPortWheel2         => '0',
      PadPortDS2            => '0',
      PadPortJustif2        => '0',
      PadPortStick2         => '0',
      PadPortPopn2          => '0',
      KeyTriangle           => player1(pad_buttons(3)),
      KeyCircle             => player1(pad_buttons(1)),
      KeyCross              => player1(pad_buttons(0)),
      KeySquare             => player1(pad_buttons(2)),
      KeySelect             => player1(pad_buttons(8)),
      KeyStart              => player1(pad_buttons(9)),
      KeyRight              => player1(pad_buttons(13)),
      KeyLeft               => player1(pad_buttons(12)),
      KeyUp                 => player1(pad_buttons(10)),
      KeyDown               => player1(pad_buttons(11)),
      KeyR1                 => player1(pad_buttons(5)),
      KeyR2                 => player1(pad_buttons(7)),
      KeyR3                 => player1(pad_buttons(15)),
      KeyL1                 => player1(pad_buttons(4)),
      KeyL2                 => player1(pad_buttons(6)),
      KeyL3                 => player1(pad_buttons(14)),
      ToggleDS              => "0000",
      Analog1XP1            => signed(pad_lx),
      Analog1YP1            => signed(pad_ly),
      Analog2XP1            => signed(pad_rx),
      Analog2YP1            => signed(pad_ry),
      Analog1XP2            => (others => '0'),
      Analog1YP2            => (others => '0'),
      Analog2XP2            => (others => '0'),
      Analog2YP2            => (others => '0'),
      Analog1XP3            => (others => '0'),
      Analog1YP3            => (others => '0'),
      Analog2XP3            => (others => '0'),
      Analog2YP3            => (others => '0'),
      Analog1XP4            => (others => '0'),
      Analog1YP4            => (others => '0'),
      Analog2XP4            => (others => '0'),
      Analog2YP4            => (others => '0'),
      multitap              => '0',
      multitapDigital       => '0',
      multitapAnalog        => '0',
      -- mouse
      MouseEvent            => '0',
      MouseLeft             => '0',
      MouseRight            => '0',
      MouseX                => (others => '0'),
      MouseY                => (others => '0'),
      RumbleDataP1          => pad_rumble,
      RumbleDataP2          => open,
      RumbleDataP3          => open,
      RumbleDataP4          => open,
      padMode               => padMode,
      -- snac
      snacPort1             => '0',
      snacPort2             => '0',
      irq10Snac             => '0',
      actionNextSnac        => '0',
      receiveValidSnac      => '0',
      ackSnac               => '0',
      snacMC                => '0',
      receiveBufferSnac     => (others => '0'),
      transmitValueSnac     => open,
      selectedPort1Snac     => open,
      selectedPort2Snac     => open,
      clk9Snac              => open,
      beginTransferSnac     => open,
      -- sound
      sound_out_left        => sound_out_left,
      sound_out_right       => sound_out_right,
      -- savestates
      increaseSSHeaderCount => '1',
      save_state            => save_state,
      load_state            => load_state,
      savestate_number      => to_integer(unsigned(savestate_number)),
      state_loaded          => state_loaded,
      validSStates          => validSStates,
      rewind_on             => '0',
      rewind_active         => '0',
      debug_errors          => debug_errors,
      debug_pc              => debug_pc,
      debug_mdec            => debug_mdec,
      debug_dma             => debug_dma,
      debug_flags           => debug_flags,
      -- cheats
      cheat_clear           => '0',
      cheats_enabled        => '0',
      cheat_on              => '0',
      cheat_in              => (others => '0'),
      cheats_active         => open,
      Cheats_BusAddr        => open,
      Cheats_BusRnW         => open,
      Cheats_BusByteEnable  => open,
      Cheats_BusWriteData   => open,
      Cheats_Bus_ena        => open,
      Cheats_BusReadData    => (others => '0'),
      Cheats_BusDone        => '0'
   );

end architecture;
