-- Vendor-neutral replacement for the Quartus-generated cx4cache.vhd
-- (altdpram, MLAB, registered write / asynchronous read).
LIBRARY ieee;
USE ieee.std_logic_1164.all;

ENTITY cx4cache IS
	PORT
	(
		clock		: IN STD_LOGIC ;
		data		: IN STD_LOGIC_VECTOR (7 DOWNTO 0);
		rdaddress		: IN STD_LOGIC_VECTOR (8 DOWNTO 0);
		wraddress		: IN STD_LOGIC_VECTOR (8 DOWNTO 0);
		wren		: IN STD_LOGIC  := '0';
		q		: OUT STD_LOGIC_VECTOR (7 DOWNTO 0)
	);
END cx4cache;

ARCHITECTURE SYN OF cx4cache IS
BEGIN
	ram : entity work.mlab generic map(9, 8)
	port map(
		clock => clock,
		rdaddress => rdaddress,
		wraddress => wraddress,
		data => data,
		wren => wren,
		q => q,
		cs => '1'
	);
END SYN;
