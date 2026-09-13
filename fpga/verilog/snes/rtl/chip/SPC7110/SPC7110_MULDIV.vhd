-- Vendor-neutral replacements for the Quartus lpm_mult / lpm_divide
-- wrappers used by the SPC7110. Multipliers are combinational; the
-- dividers keep the upstream 8-cycle pipeline latency.
LIBRARY ieee;
USE ieee.std_logic_1164.all;
USE ieee.numeric_std.all;

ENTITY SPC7110_UMULT IS
	PORT
	(
		dataa		: IN STD_LOGIC_VECTOR (15 DOWNTO 0);
		datab		: IN STD_LOGIC_VECTOR (15 DOWNTO 0);
		result		: OUT STD_LOGIC_VECTOR (31 DOWNTO 0)
	);
END SPC7110_UMULT;

ARCHITECTURE SYN OF SPC7110_UMULT IS
BEGIN
	result <= std_logic_vector(unsigned(dataa) * unsigned(datab));
END SYN;

---------------------------------------------------------------------
LIBRARY ieee;
USE ieee.std_logic_1164.all;
USE ieee.numeric_std.all;

ENTITY SPC7110_SMULT IS
	PORT
	(
		dataa		: IN STD_LOGIC_VECTOR (15 DOWNTO 0);
		datab		: IN STD_LOGIC_VECTOR (15 DOWNTO 0);
		result		: OUT STD_LOGIC_VECTOR (31 DOWNTO 0)
	);
END SPC7110_SMULT;

ARCHITECTURE SYN OF SPC7110_SMULT IS
BEGIN
	result <= std_logic_vector(signed(dataa) * signed(datab));
END SYN;

---------------------------------------------------------------------
LIBRARY ieee;
USE ieee.std_logic_1164.all;
USE ieee.numeric_std.all;

ENTITY SPC7110_UDIV IS
	PORT
	(
		clock		: IN STD_LOGIC ;
		denom		: IN STD_LOGIC_VECTOR (15 DOWNTO 0);
		numer		: IN STD_LOGIC_VECTOR (31 DOWNTO 0);
		quotient		: OUT STD_LOGIC_VECTOR (31 DOWNTO 0);
		remain		: OUT STD_LOGIC_VECTOR (15 DOWNTO 0)
	);
END SPC7110_UDIV;

ARCHITECTURE SYN OF SPC7110_UDIV IS
	constant PIPELINE : integer := 8;
	type q_pipe_t is array (0 to PIPELINE-1) of unsigned(31 downto 0);
	type r_pipe_t is array (0 to PIPELINE-1) of unsigned(15 downto 0);
	signal q_pipe : q_pipe_t := (others => (others => '0'));
	signal r_pipe : r_pipe_t := (others => (others => '0'));
BEGIN
	process(clock)
		variable q : unsigned(31 downto 0);
		variable r : unsigned(31 downto 0);
	begin
		if rising_edge(clock) then
			if unsigned(denom) = 0 then
				q := (others => '1');
				r := resize(unsigned(numer), 32);
			else
				q := unsigned(numer) / unsigned(denom);
				r := unsigned(numer) rem unsigned(denom);
			end if;
			q_pipe(0) <= q;
			r_pipe(0) <= r(15 downto 0);
			for i in 1 to PIPELINE-1 loop
				q_pipe(i) <= q_pipe(i-1);
				r_pipe(i) <= r_pipe(i-1);
			end loop;
		end if;
	end process;
	quotient <= std_logic_vector(q_pipe(PIPELINE-1));
	remain <= std_logic_vector(r_pipe(PIPELINE-1));
END SYN;

---------------------------------------------------------------------
LIBRARY ieee;
USE ieee.std_logic_1164.all;
USE ieee.numeric_std.all;

ENTITY SPC7110_SDIV IS
	PORT
	(
		clock		: IN STD_LOGIC ;
		denom		: IN STD_LOGIC_VECTOR (15 DOWNTO 0);
		numer		: IN STD_LOGIC_VECTOR (31 DOWNTO 0);
		quotient		: OUT STD_LOGIC_VECTOR (31 DOWNTO 0);
		remain		: OUT STD_LOGIC_VECTOR (15 DOWNTO 0)
	);
END SPC7110_SDIV;

ARCHITECTURE SYN OF SPC7110_SDIV IS
	constant PIPELINE : integer := 8;
	type q_pipe_t is array (0 to PIPELINE-1) of signed(31 downto 0);
	type r_pipe_t is array (0 to PIPELINE-1) of signed(15 downto 0);
	signal q_pipe : q_pipe_t := (others => (others => '0'));
	signal r_pipe : r_pipe_t := (others => (others => '0'));
BEGIN
	-- LPM_REMAINDERPOSITIVE=TRUE: remainder is always non-negative
	-- (floor-style division when the numerator is negative).
	process(clock)
		variable q : signed(31 downto 0);
		variable r : signed(31 downto 0);
	begin
		if rising_edge(clock) then
			if signed(denom) = 0 then
				q := (others => '1');
				r := resize(signed(numer), 32);
			else
				q := signed(numer) / signed(denom);
				r := signed(numer) rem signed(denom);
				if r < 0 then
					if signed(denom) > 0 then
						q := q - 1;
						r := r + resize(signed(denom), 32);
					else
						q := q + 1;
						r := r - resize(signed(denom), 32);
					end if;
				end if;
			end if;
			q_pipe(0) <= q;
			r_pipe(0) <= r(15 downto 0);
			for i in 1 to PIPELINE-1 loop
				q_pipe(i) <= q_pipe(i-1);
				r_pipe(i) <= r_pipe(i-1);
			end loop;
		end if;
	end process;
	quotient <= std_logic_vector(q_pipe(PIPELINE-1));
	remain <= std_logic_vector(r_pipe(PIPELINE-1));
END SYN;
