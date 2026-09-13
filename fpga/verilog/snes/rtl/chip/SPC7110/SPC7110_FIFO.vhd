-- Vendor-neutral replacement for the Quartus-generated SPC7110_FIFO.vhd
-- (scfifo, 32 x 8, show-ahead mode, synchronous clear, over/underflow
-- checking). In show-ahead mode q always presents the head of the FIFO
-- and rdreq acknowledges (pops) it.
LIBRARY ieee;
USE ieee.std_logic_1164.all;
USE ieee.numeric_std.all;

ENTITY SPC7110_FIFO IS
	PORT
	(
		clock		: IN STD_LOGIC ;
		data		: IN STD_LOGIC_VECTOR (7 DOWNTO 0);
		rdreq		: IN STD_LOGIC ;
		sclr		: IN STD_LOGIC ;
		wrreq		: IN STD_LOGIC ;
		empty		: OUT STD_LOGIC ;
		full		: OUT STD_LOGIC ;
		q		: OUT STD_LOGIC_VECTOR (7 DOWNTO 0)
	);
END SPC7110_FIFO;

ARCHITECTURE SYN OF spc7110_fifo IS
	constant DEPTH : integer := 32;
	type mem_t is array (0 to DEPTH-1) of std_logic_vector(7 downto 0);
	signal mem   : mem_t := (others => (others => '0'));
	signal rdptr : unsigned(4 downto 0) := (others => '0');
	signal wrptr : unsigned(4 downto 0) := (others => '0');
	signal count : unsigned(5 downto 0) := (others => '0');
	signal empty_i : std_logic;
	signal full_i  : std_logic;
BEGIN
	empty_i <= '1' when count = 0 else '0';
	full_i  <= '1' when count = DEPTH else '0';
	empty <= empty_i;
	full  <= full_i;
	q <= mem(to_integer(rdptr));

	process(clock)
		variable do_rd, do_wr : boolean;
	begin
		if rising_edge(clock) then
			if sclr = '1' then
				rdptr <= (others => '0');
				wrptr <= (others => '0');
				count <= (others => '0');
			else
				do_rd := rdreq = '1' and empty_i = '0';
				do_wr := wrreq = '1' and full_i = '0';
				if do_wr then
					mem(to_integer(wrptr)) <= data;
					wrptr <= wrptr + 1;
				end if;
				if do_rd then
					rdptr <= rdptr + 1;
				end if;
				if do_wr and not do_rd then
					count <= count + 1;
				elsif do_rd and not do_wr then
					count <= count - 1;
				end if;
			end if;
		end if;
	end process;
END SYN;
