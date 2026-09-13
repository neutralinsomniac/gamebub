--------------------------------------------------------------
-- Vendor-neutral replacements for the MiSTer "bram.vhd" wrappers.
--
-- The upstream file instantiates Altera altsyncram; these versions use
-- plain inferrable VHDL so they synthesize on Xilinx (Vivado) and
-- analyze under GHDL. Interfaces and behavior match upstream:
--
--   * synchronous read, unregistered output (1 cycle latency)
--   * same-port read-during-write returns NEW data (write-first)
--   * q is forced to all-ones when cs = '0'; writes are gated by cs
--   * dpram_dif supports mixed port widths (power-of-two ratio); the
--     narrow word at the lowest address maps to the LSBs of the wide word
--
-- mem_init_file is accepted for interface compatibility but ignored;
-- ROM contents that upstream loads from .mif files are provided by the
-- generated entities in rtl/generated (see tools/gen_roms.py).
--------------------------------------------------------------
--
-- Units are ordered bottom-up because Vivado analyzes them in file order
-- and direct entity instantiation needs the target analyzed first.

--------------------------------------------------------------
-- Single port RAM with specific size
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
-- Single port RAM
--------------------------------------------------------------
LIBRARY ieee;
USE ieee.std_logic_1164.all;
USE ieee.numeric_std.all;

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
-- True dual port RAM bank (one write per port), the template Vivado infers.
--
-- The RAM itself is read-first: on Xilinx block RAM this is the only mode in
-- which a write on one port and a read of the same address on the other
-- port in the same cycle returns valid (old) data on the reading port; in
-- write-first mode the reading port returns garbage. Altera M10K returns old
-- data on such a collision, and the DSP (BRR_BUF, REGRAM) relies on it.
--
-- The write-first (new data) same-port behavior of altsyncram is restored
-- with a bypass register per port.
--------------------------------------------------------------
LIBRARY ieee;
USE ieee.std_logic_1164.all;
USE ieee.numeric_std.all;

entity tdp_ram_bank is
	generic (
		addr_width : integer := 8;
		data_width : integer := 8
	);
	port (
		clk0  : in  std_logic;
		en0   : in  std_logic;
		we0   : in  std_logic;
		addr0 : in  unsigned(addr_width-1 downto 0);
		di0   : in  std_logic_vector(data_width-1 downto 0);
		do0   : out std_logic_vector(data_width-1 downto 0);
		clk1  : in  std_logic;
		en1   : in  std_logic;
		we1   : in  std_logic;
		addr1 : in  unsigned(addr_width-1 downto 0);
		di1   : in  std_logic_vector(data_width-1 downto 0);
		do1   : out std_logic_vector(data_width-1 downto 0)
	);
end entity;

architecture SYN of tdp_ram_bank is
	type ram_t is array (0 to 2**addr_width-1) of std_logic_vector(data_width-1 downto 0);
	shared variable ram : ram_t := (others => (others => '0'));
	signal rd0, rd1 : std_logic_vector(data_width-1 downto 0) := (others => '0');
	signal wr0, wr1 : std_logic_vector(data_width-1 downto 0) := (others => '0');
	signal bypass0, bypass1 : std_logic := '0';
begin
	port0 : process(clk0)
	begin
		if rising_edge(clk0) then
			if en0 = '1' then
				rd0 <= ram(to_integer(addr0));
				if we0 = '1' then
					ram(to_integer(addr0)) := di0;
				end if;
				wr0 <= di0;
				bypass0 <= we0;
			end if;
		end if;
	end process;
	do0 <= wr0 when bypass0 = '1' else rd0;

	port1 : process(clk1)
	begin
		if rising_edge(clk1) then
			if en1 = '1' then
				rd1 <= ram(to_integer(addr1));
				if we1 = '1' then
					ram(to_integer(addr1)) := di1;
				end if;
				wr1 <= di1;
				bypass1 <= we1;
			end if;
		end if;
	end process;
	do1 <= wr1 when bypass1 = '1' else rd1;
end SYN;

--------------------------------------------------------------
-- Dual port RAM, different parameters and clocks on the ports
--------------------------------------------------------------
LIBRARY ieee;
USE ieee.std_logic_1164.all;
USE ieee.numeric_std.all;

entity dpram_difclk is
	generic (
		addr_width_a  : integer := 8;
		data_width_a  : integer := 8;
		addr_width_b  : integer := 8;
		data_width_b  : integer := 8;
		mem_init_file : string := " "
	);
	PORT
	(
		clock0		: in  STD_LOGIC;
		clock1		: in  STD_LOGIC;
		address_a	: in  STD_LOGIC_VECTOR (addr_width_a-1 DOWNTO 0);
		data_a		: in  STD_LOGIC_VECTOR (data_width_a-1 DOWNTO 0) := (others => '0');
		enable_a		: in  STD_LOGIC := '1';
		wren_a		: in  STD_LOGIC := '0';
		q_a			: out STD_LOGIC_VECTOR (data_width_a-1 DOWNTO 0);
		cs_a        : in  std_logic := '1';
		address_b	: in  STD_LOGIC_VECTOR (addr_width_b-1 DOWNTO 0) := (others => '0');
		data_b		: in  STD_LOGIC_VECTOR (data_width_b-1 DOWNTO 0) := (others => '0');
		enable_b		: in  STD_LOGIC := '1';
		wren_b		: in  STD_LOGIC := '0';
		q_b			: out STD_LOGIC_VECTOR (data_width_b-1 DOWNTO 0);
		cs_b        : in  std_logic := '1'
	);
end entity;

ARCHITECTURE SYN OF dpram_difclk IS
	function min_int(a, b : integer) return integer is
	begin
		if a < b then return a; else return b; end if;
	end function;
	function log2_int(v : integer) return integer is
		variable r : integer := 0;
	begin
		while 2**r < v loop r := r + 1; end loop;
		return r;
	end function;

	-- The storage is split into RATIO banks of narrow words, so that each
	-- port writes at most one word per bank per clock (the only dual-port
	-- RAM template Vivado infers). Narrow address a maps to bank a mod RATIO,
	-- index a / RATIO; wide address n maps to index n in every bank, with
	-- bank k holding bits [W*(k+1)-1:W*k] of the wide word.
	constant W       : integer := min_int(data_width_a, data_width_b);
	constant RATIO_A : integer := data_width_a / W;
	constant RATIO_B : integer := data_width_b / W;
	constant RATIO   : integer := RATIO_A * RATIO_B; -- one of them is 1
	constant BANK_ADDR_WIDTH : integer := min_int(addr_width_a, addr_width_b);
	constant SEL_WIDTH : integer := log2_int(RATIO);

	type bank_out_t is array (0 to RATIO-1) of std_logic_vector(W-1 downto 0);
	signal a_douts, b_douts : bank_out_t;
	-- Bank selected by the address registered at the last read edge
	signal a_sel_r, b_sel_r : integer range 0 to RATIO-1 := 0;

	signal q0 : std_logic_vector(data_width_a-1 downto 0) := (others => '0');
	signal q1 : std_logic_vector(data_width_b-1 downto 0) := (others => '0');
BEGIN
	assert (2**addr_width_a) * RATIO_A = (2**addr_width_b) * RATIO_B
		report "dpram_difclk: port sizes are inconsistent" severity failure;

	q_a <= q0 when cs_a = '1' else (others => '1');
	q_b <= q1 when cs_b = '1' else (others => '1');

	banks : for k in 0 to RATIO-1 generate
		-- Port A view of this bank
		signal a_index : unsigned(BANK_ADDR_WIDTH-1 downto 0);
		signal a_sel   : boolean;
		signal a_din   : std_logic_vector(W-1 downto 0);
		signal a_dout  : std_logic_vector(W-1 downto 0);
		signal a_we    : std_logic;
		-- Port B view of this bank
		signal b_index : unsigned(BANK_ADDR_WIDTH-1 downto 0);
		signal b_sel   : boolean;
		signal b_din   : std_logic_vector(W-1 downto 0);
		signal b_dout  : std_logic_vector(W-1 downto 0);
		signal b_we    : std_logic;
	begin
		-- Address / data decode for port A
		narrow_a : if RATIO_A = 1 and RATIO > 1 generate
			a_index <= unsigned(address_a(addr_width_a-1 downto SEL_WIDTH));
			a_sel   <= to_integer(unsigned(address_a(SEL_WIDTH-1 downto 0))) = k;
			a_din   <= data_a;
		end generate;
		a_douts(k) <= a_dout;
		wide_a : if RATIO_A > 1 or RATIO = 1 generate
			a_index <= unsigned(address_a);
			a_sel   <= true;
			a_din   <= data_a(W*(k+1)-1 downto W*k);
			q0(W*(k+1)-1 downto W*k) <= a_dout;
		end generate;

		-- Address / data decode for port B
		narrow_b : if RATIO_B = 1 and RATIO > 1 generate
			b_index <= unsigned(address_b(addr_width_b-1 downto SEL_WIDTH));
			b_sel   <= to_integer(unsigned(address_b(SEL_WIDTH-1 downto 0))) = k;
			b_din   <= data_b;
		end generate;
		b_douts(k) <= b_dout;
		wide_b : if RATIO_B > 1 or RATIO = 1 generate
			b_index <= unsigned(address_b);
			b_sel   <= true;
			b_din   <= data_b(W*(k+1)-1 downto W*k);
			q1(W*(k+1)-1 downto W*k) <= b_dout;
		end generate;

		a_we <= '1' when wren_a = '1' and cs_a = '1' and a_sel else '0';
		b_we <= '1' when wren_b = '1' and cs_b = '1' and b_sel else '0';

		bank : entity work.tdp_ram_bank generic map(BANK_ADDR_WIDTH, W)
		port map(
			clk0 => clock0, en0 => enable_a, we0 => a_we, addr0 => a_index, di0 => a_din, do0 => a_dout,
			clk1 => clock1, en1 => enable_b, we1 => b_we, addr1 => b_index, di1 => b_din, do1 => b_dout
		);
	end generate;
	narrow_a_mux : if RATIO_A = 1 and RATIO > 1 generate
		process(clock0)
		begin
			if rising_edge(clock0) then
				if enable_a = '1' then
					a_sel_r <= to_integer(unsigned(address_a(SEL_WIDTH-1 downto 0)));
				end if;
			end if;
		end process;
		q0 <= a_douts(a_sel_r);
	end generate;

	narrow_b_mux : if RATIO_B = 1 and RATIO > 1 generate
		process(clock1)
		begin
			if rising_edge(clock1) then
				if enable_b = '1' then
					b_sel_r <= to_integer(unsigned(address_b(SEL_WIDTH-1 downto 0)));
				end if;
			end if;
		end process;
		q1 <= b_douts(b_sel_r);
	end generate;
END SYN;
--------------------------------------------------------------
-- Dual port RAM, different parameters on the ports
--------------------------------------------------------------
LIBRARY ieee;
USE ieee.std_logic_1164.all;
USE ieee.numeric_std.all;

entity dpram_dif is
	generic (
		addr_width_a  : integer := 8;
		data_width_a  : integer := 8;
		addr_width_b  : integer := 8;
		data_width_b  : integer := 8;
		mem_init_file : string := " "
	);
	PORT
	(
		clock			: in  STD_LOGIC;
		address_a	: in  STD_LOGIC_VECTOR (addr_width_a-1 DOWNTO 0);
		data_a		: in  STD_LOGIC_VECTOR (data_width_a-1 DOWNTO 0) := (others => '0');
		enable_a		: in  STD_LOGIC := '1';
		wren_a		: in  STD_LOGIC := '0';
		q_a			: out STD_LOGIC_VECTOR (data_width_a-1 DOWNTO 0);
		cs_a        : in  std_logic := '1';
		address_b	: in  STD_LOGIC_VECTOR (addr_width_b-1 DOWNTO 0) := (others => '0');
		data_b		: in  STD_LOGIC_VECTOR (data_width_b-1 DOWNTO 0) := (others => '0');
		enable_b		: in  STD_LOGIC := '1';
		wren_b		: in  STD_LOGIC := '0';
		q_b			: out STD_LOGIC_VECTOR (data_width_b-1 DOWNTO 0);
		cs_b        : in  std_logic := '1'
	);
end entity;

ARCHITECTURE SYN OF dpram_dif IS
BEGIN
	ram : entity work.dpram_difclk generic map(addr_width_a,data_width_a,addr_width_b,data_width_b,mem_init_file)
	port map(clock,clock,address_a,data_a,enable_a,wren_a,q_a,cs_a,address_b,data_b,enable_b,wren_b,q_b,cs_b);
END SYN;

--------------------------------------------------------------
-- Dual port RAM, same parameters on both ports
--------------------------------------------------------------
LIBRARY ieee;
USE ieee.std_logic_1164.all;
USE ieee.numeric_std.all;

entity dpram is
	generic (
		addr_width    : integer := 8;
		data_width    : integer := 8;
		mem_init_file : string := " "
	);
	PORT
	(
		clock			: in  STD_LOGIC;
		address_a	: in  STD_LOGIC_VECTOR (addr_width-1 DOWNTO 0);
		data_a		: in  STD_LOGIC_VECTOR (data_width-1 DOWNTO 0) := (others => '0');
		enable_a		: in  STD_LOGIC := '1';
		wren_a		: in  STD_LOGIC := '0';
		q_a			: out STD_LOGIC_VECTOR (data_width-1 DOWNTO 0);
		cs_a        : in  std_logic := '1';
		address_b	: in  STD_LOGIC_VECTOR (addr_width-1 DOWNTO 0) := (others => '0');
		data_b		: in  STD_LOGIC_VECTOR (data_width-1 DOWNTO 0) := (others => '0');
		enable_b		: in  STD_LOGIC := '1';
		wren_b		: in  STD_LOGIC := '0';
		q_b			: out STD_LOGIC_VECTOR (data_width-1 DOWNTO 0);
		cs_b        : in  std_logic := '1'
	);
end entity;

ARCHITECTURE SYN OF dpram IS
BEGIN
	ram : entity work.dpram_dif generic map(addr_width,data_width,addr_width,data_width,mem_init_file)
	port map(clock,address_a,data_a,enable_a,wren_a,q_a,cs_a,address_b,data_b,enable_b,wren_b,q_b,cs_b);
END SYN;

