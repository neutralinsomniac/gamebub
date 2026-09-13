--------------------------------------------------------------
-- Vendor-neutral replacement for the MiSTer "mlab.vhd" wrapper.
--
-- Simple dual port RAM with a registered write port and an
-- asynchronous (unregistered) read port, matching the upstream
-- altdpram configuration (rdaddress_reg => "UNREGISTERED",
-- ram_block_type => "MLAB"). Infers distributed (LUT) RAM on Xilinx.
--------------------------------------------------------------
LIBRARY ieee;
USE ieee.std_logic_1164.all;
USE ieee.numeric_std.all;

ENTITY mlab IS
	generic (
		addr_width    : integer := 8;
		data_width    : integer := 8
	);
	PORT
	(
		clock   		: in  STD_LOGIC;
		rdaddress 	: in  STD_LOGIC_VECTOR (addr_width-1 DOWNTO 0);
		wraddress 	: in  STD_LOGIC_VECTOR (addr_width-1 DOWNTO 0);
		data			: in  STD_LOGIC_VECTOR (data_width-1 DOWNTO 0) := (others => '0');
		wren    		: in  STD_LOGIC := '0';
		q       		: out STD_LOGIC_VECTOR (data_width-1 DOWNTO 0);
		cs      		: in  std_logic := '1'
	);
END ENTITY;

ARCHITECTURE SYN OF mlab IS
	type ram_t is array (0 to 2**addr_width-1) of std_logic_vector(data_width-1 downto 0);
	signal ram : ram_t := (others => (others => '0'));
BEGIN
	q <= ram(to_integer(unsigned(rdaddress))) when cs = '1' else (others => '1');

	process(clock)
	begin
		if rising_edge(clock) then
			if wren = '1' then
				ram(to_integer(unsigned(wraddress))) <= data;
			end if;
		end if;
	end process;
END SYN;
