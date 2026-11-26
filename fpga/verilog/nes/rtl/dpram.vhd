--------------------------------------------------------------
-- Vendor-neutral inferrable RAM wrappers for MiSTer NES.
--
-- Replaces Altera altsyncram with plain Vivado-inferrable VHDL.
-- Uses read-first storage with a per-port write-first bypass so
-- same-port read-during-write returns NEW data while cross-port
-- read-during-write returns the old value (matching Cyclone V M10K).
--------------------------------------------------------------

--------------------------------------------------------------
-- Single port Block RAM with specific size
--------------------------------------------------------------
LIBRARY ieee;
USE ieee.std_logic_1164.all;
USE ieee.numeric_std.all;

ENTITY spram_sz IS
	generic (
		addr_width    : integer := 8;
		data_width    : integer := 8;
		numwords      : integer := 2**8;
		mem_init_file : string := " ";
		mem_name      : string := "MEM"
	);
	PORT
	(
		clock   : in  STD_LOGIC;
		address : in  STD_LOGIC_VECTOR (addr_width-1 DOWNTO 0);
		data    : in  STD_LOGIC_VECTOR (data_width-1 DOWNTO 0) := (others => '0');
		enable  : in  STD_LOGIC := '1';
		wren    : in  STD_LOGIC := '0';
		q       : out STD_LOGIC_VECTOR (data_width-1 DOWNTO 0);
		cs      : in  std_logic := '1'
	);
END ENTITY;

ARCHITECTURE SYN OF spram_sz IS
	type ram_t is array (0 to numwords-1) of std_logic_vector(data_width-1 downto 0);
	signal ram : ram_t := (others => (others => '0'));
	signal q0  : std_logic_vector(data_width-1 downto 0) := (others => '0');
BEGIN
	q <= q0 when cs = '1' else (others => '1');

	process(clock)
		variable idx : integer;
	begin
		if rising_edge(clock) then
			idx := to_integer(unsigned(address));
			if idx < numwords then
				if wren = '1' and cs = '1' then
					ram(idx) <= data;
					q0 <= data;
				else
					q0 <= ram(idx);
				end if;
			end if;
		end if;
	end process;
END SYN;

--------------------------------------------------------------
-- Single port Block RAM
--------------------------------------------------------------
LIBRARY ieee;
USE ieee.std_logic_1164.all;

ENTITY spram IS
	generic (
		addr_width    : integer := 8;
		data_width    : integer := 8;
		mem_init_file : string := " ";
		mem_name      : string := "MEM"
	);
	PORT
	(
		clock   : in  STD_LOGIC;
		address : in  STD_LOGIC_VECTOR (addr_width-1 DOWNTO 0);
		data    : in  STD_LOGIC_VECTOR (data_width-1 DOWNTO 0) := (others => '0');
		enable  : in  STD_LOGIC := '1';
		wren    : in  STD_LOGIC := '0';
		q       : out STD_LOGIC_VECTOR (data_width-1 DOWNTO 0);
		cs      : in  std_logic := '1'
	);
END spram;

ARCHITECTURE SYN OF spram IS
BEGIN
	spram_sz : entity work.spram_sz
	generic map(addr_width, data_width, 2**addr_width, mem_init_file, mem_name)
	port map(clock,address,data,enable,wren,q,cs);
END SYN;

--------------------------------------------------------------
-- Dual port Block RAM
--------------------------------------------------------------
LIBRARY ieee;
USE ieee.std_logic_1164.all;
USE ieee.numeric_std.all;

ENTITY dpram IS
	GENERIC
	(
		init_file     : string := " ";
		widthad_a     : natural := 8;
		width_a       : natural := 8;
		outdata_reg_a : string := "UNREGISTERED";
		outdata_reg_b : string := "UNREGISTERED"
	);
	PORT
	(
		address_a : IN  STD_LOGIC_VECTOR (widthad_a-1 DOWNTO 0);
		address_b : IN  STD_LOGIC_VECTOR (widthad_a-1 DOWNTO 0) := (others => '0');
		clock_a   : IN  STD_LOGIC;
		clock_b   : IN  STD_LOGIC;
		data_a    : IN  STD_LOGIC_VECTOR (width_a-1 DOWNTO 0) := (others => '0');
		data_b    : IN  STD_LOGIC_VECTOR (width_a-1 DOWNTO 0) := (others => '0');
		wren_a    : IN  STD_LOGIC := '0';
		wren_b    : IN  STD_LOGIC := '0';
		byteena_a : IN  STD_LOGIC_VECTOR (width_a/8-1 DOWNTO 0) := (others => '1');
		byteena_b : IN  STD_LOGIC_VECTOR (width_a/8-1 DOWNTO 0) := (others => '1');
		q_a       : OUT STD_LOGIC_VECTOR (width_a-1 DOWNTO 0);
		q_b       : OUT STD_LOGIC_VECTOR (width_a-1 DOWNTO 0)
	);
END dpram;

ARCHITECTURE SYN OF dpram IS
	constant NUM_BYTES : natural := width_a / 8;
	type ram_t is array (0 to 2**widthad_a - 1) of std_logic_vector(width_a - 1 downto 0);
	shared variable ram : ram_t := (others => (others => '0'));

	signal rd_a, rd_b : std_logic_vector(width_a - 1 downto 0) := (others => '0');
	signal wr_a, wr_b : std_logic_vector(width_a - 1 downto 0) := (others => '0');
	signal by_a, by_b : std_logic_vector(NUM_BYTES - 1 downto 0) := (others => '0');
	signal qa_unreg, qb_unreg : std_logic_vector(width_a - 1 downto 0);
BEGIN
	port_a : process(clock_a)
		variable idx : integer;
	begin
		if rising_edge(clock_a) then
			idx := to_integer(unsigned(address_a));
			rd_a <= ram(idx);
			wr_a <= data_a;
			if wren_a = '1' then
				for b in 0 to NUM_BYTES - 1 loop
					if byteena_a(b) = '1' then
						ram(idx)(8*b+7 downto 8*b) := data_a(8*b+7 downto 8*b);
					end if;
				end loop;
				by_a <= byteena_a;
			else
				by_a <= (others => '0');
			end if;
		end if;
	end process;

	port_b : process(clock_b)
		variable idx : integer;
	begin
		if rising_edge(clock_b) then
			idx := to_integer(unsigned(address_b));
			rd_b <= ram(idx);
			wr_b <= data_b;
			if wren_b = '1' then
				for b in 0 to NUM_BYTES - 1 loop
					if byteena_b(b) = '1' then
						ram(idx)(8*b+7 downto 8*b) := data_b(8*b+7 downto 8*b);
					end if;
				end loop;
				by_b <= byteena_b;
			else
				by_b <= (others => '0');
			end if;
		end if;
	end process;

	gen_bypass : for b in 0 to NUM_BYTES - 1 generate
		qa_unreg(8*b+7 downto 8*b) <= wr_a(8*b+7 downto 8*b) when by_a(b) = '1' else rd_a(8*b+7 downto 8*b);
		qb_unreg(8*b+7 downto 8*b) <= wr_b(8*b+7 downto 8*b) when by_b(b) = '1' else rd_b(8*b+7 downto 8*b);
	end generate;

	gen_reg_a : if outdata_reg_a = "CLOCK0" generate
		process(clock_a)
		begin
			if rising_edge(clock_a) then
				q_a <= qa_unreg;
			end if;
		end process;
	end generate;
	gen_unreg_a : if outdata_reg_a /= "CLOCK0" generate
		q_a <= qa_unreg;
	end generate;

	gen_reg_b : if outdata_reg_b = "CLOCK0" or outdata_reg_b = "CLOCK1" generate
		process(clock_b)
		begin
			if rising_edge(clock_b) then
				q_b <= qb_unreg;
			end if;
		end process;
	end generate;
	gen_unreg_b : if outdata_reg_b /= "CLOCK0" and outdata_reg_b /= "CLOCK1" generate
		q_b <= qb_unreg;
	end generate;
END SYN;
