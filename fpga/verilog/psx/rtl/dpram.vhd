-- Xilinx (Vivado-inferable) replacements for the altsyncram wrappers of the
-- upstream rtl/dpram.vhd (Game Bub port; see ../README.md).
--
-- Semantics kept from the altsyncram configuration: BIDIR_DUAL_PORT, one
-- clock per port, the clock enable gates the input (address / data / write
-- enable) registers, unregistered output (one cycle read latency), and a
-- port reading the address it writes sees the new data
-- (NEW_DATA_NO_NBE_READ). Mixed widths use Altera's ordering: narrow word i
-- of wide word w lives at narrow address w * ratio + i, in bits
-- (i+1)*narrow-1 downto i*narrow of the wide word.
--
-- The block RAM itself is read-first (Vivado's asymmetric true dual-port
-- template), with a per-port bypass for the written word: in write-first
-- mode a Xilinx block RAM returns garbage on the other port when it reads
-- the address being written, whereas the Cyclone V returns the old data.

--------------------------------------------------------------
-- Dual port RAM, different widths on the ports
--------------------------------------------------------------
library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

entity dpram_dif is
   generic (
      addr_width_a : integer := 8;
      data_width_a : integer := 8;
      addr_width_b : integer := 8;
      data_width_b : integer := 8
   );
   port (
      clock_a   : in  std_logic;
      address_a : in  std_logic_vector(addr_width_a-1 downto 0);
      data_a    : in  std_logic_vector(data_width_a-1 downto 0) := (others => '0');
      clken_a   : in  std_logic := '1';
      wren_a    : in  std_logic := '0';
      q_a       : out std_logic_vector(data_width_a-1 downto 0);
      cs_a      : in  std_logic := '1';
      clock_b   : in  std_logic;
      address_b : in  std_logic_vector(addr_width_b-1 downto 0) := (others => '0');
      data_b    : in  std_logic_vector(data_width_b-1 downto 0) := (others => '0');
      clken_b   : in  std_logic := '1';
      wren_b    : in  std_logic := '0';
      q_b       : out std_logic_vector(data_width_b-1 downto 0);
      cs_b      : in  std_logic := '1'
   );
end entity;

architecture rtl of dpram_dif is
   function max(a, b : integer) return integer is
   begin
      if (a > b) then return a; else return b; end if;
   end function;
   function min(a, b : integer) return integer is
   begin
      if (a < b) then return a; else return b; end if;
   end function;
   constant minWIDTH  : integer := min(data_width_a, data_width_b);
   constant maxADDR   : integer := max(addr_width_a, addr_width_b);
   constant RATIO     : integer := max(data_width_a, data_width_b) / minWIDTH;
   constant RATIOBITS : integer := maxADDR - min(addr_width_a, addr_width_b);
   type ram_t is array (0 to 2**maxADDR-1) of std_logic_vector(minWIDTH-1 downto 0);
   shared variable ram : ram_t := (others => (others => '0'));
   signal rd_a, wr_a : std_logic_vector(data_width_a-1 downto 0) := (others => '0');
   signal rd_b, wr_b : std_logic_vector(data_width_b-1 downto 0) := (others => '0');
   signal bypass_a, bypass_b : std_logic := '0';
   signal we_a, we_b : std_logic;
begin
   we_a <= wren_a and cs_a;
   we_b <= wren_b and cs_b;
   q_a <= (others => '1') when cs_a = '0' else wr_a when bypass_a = '1' else rd_a;
   q_b <= (others => '1') when cs_b = '0' else wr_b when bypass_b = '1' else rd_b;

   gnarrow_a : if data_width_a <= data_width_b generate
      process (clock_a)
      begin
         if rising_edge(clock_a) then
            if (clken_a = '1') then
               rd_a <= ram(to_integer(unsigned(address_a)));
               if (we_a = '1') then
                  ram(to_integer(unsigned(address_a))) := data_a;
               end if;
               wr_a <= data_a;
               bypass_a <= we_a;
            end if;
         end if;
      end process;
      process (clock_b)
      begin
         if rising_edge(clock_b) then
            for i in 0 to RATIO-1 loop
               if (clken_b = '1') then
                  rd_b((i+1)*minWIDTH-1 downto i*minWIDTH) <= ram(to_integer(unsigned(address_b) & to_unsigned(i, RATIOBITS)));
                  if (we_b = '1') then
                     ram(to_integer(unsigned(address_b) & to_unsigned(i, RATIOBITS))) := data_b((i+1)*minWIDTH-1 downto i*minWIDTH);
                  end if;
               end if;
            end loop;
            if (clken_b = '1') then
               wr_b <= data_b;
               bypass_b <= we_b;
            end if;
         end if;
      end process;
   end generate;

   gnarrow_b : if data_width_a > data_width_b generate
      process (clock_a)
      begin
         if rising_edge(clock_a) then
            for i in 0 to RATIO-1 loop
               if (clken_a = '1') then
                  rd_a((i+1)*minWIDTH-1 downto i*minWIDTH) <= ram(to_integer(unsigned(address_a) & to_unsigned(i, RATIOBITS)));
                  if (we_a = '1') then
                     ram(to_integer(unsigned(address_a) & to_unsigned(i, RATIOBITS))) := data_a((i+1)*minWIDTH-1 downto i*minWIDTH);
                  end if;
               end if;
            end loop;
            if (clken_a = '1') then
               wr_a <= data_a;
               bypass_a <= we_a;
            end if;
         end if;
      end process;
      process (clock_b)
      begin
         if rising_edge(clock_b) then
            if (clken_b = '1') then
               rd_b <= ram(to_integer(unsigned(address_b)));
               if (we_b = '1') then
                  ram(to_integer(unsigned(address_b))) := data_b;
               end if;
               wr_b <= data_b;
               bypass_b <= we_b;
            end if;
         end if;
      end process;
   end generate;
end rtl;

--------------------------------------------------------------
-- Dual port RAM, same width on both ports
--------------------------------------------------------------
library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

entity dpram is
   generic (
      addr_width : integer := 8;
      data_width : integer := 8
   );
   port (
      clock_a   : in  std_logic;
      clken_a   : in  std_logic := '1';
      address_a : in  std_logic_vector(addr_width-1 downto 0);
      data_a    : in  std_logic_vector(data_width-1 downto 0);
      wren_a    : in  std_logic := '0';
      q_a       : out std_logic_vector(data_width-1 downto 0);

      clock_b   : in  std_logic;
      clken_b   : in  std_logic := '1';
      address_b : in  std_logic_vector(addr_width-1 downto 0);
      data_b    : in  std_logic_vector(data_width-1 downto 0) := (others => '0');
      wren_b    : in  std_logic := '0';
      q_b       : out std_logic_vector(data_width-1 downto 0)
   );
end entity;

architecture rtl of dpram is
begin
   ram : entity work.dpram_dif
   generic map (
      addr_width_a => addr_width,
      data_width_a => data_width,
      addr_width_b => addr_width,
      data_width_b => data_width
   )
   port map (
      clock_a   => clock_a,
      address_a => address_a,
      data_a    => data_a,
      clken_a   => clken_a,
      wren_a    => wren_a,
      q_a       => q_a,
      clock_b   => clock_b,
      address_b => address_b,
      data_b    => data_b,
      clken_b   => clken_b,
      wren_b    => wren_b,
      q_b       => q_b
   );
end rtl;
