-- Vendor-neutral replacement for the Quartus-generated SA1MULT.vhd
-- (lpm_mult, 16x16 signed, combinational).
LIBRARY ieee;
USE ieee.std_logic_1164.all;
USE ieee.numeric_std.all;

ENTITY SA1MULT IS
	PORT
	(
		dataa		: IN STD_LOGIC_VECTOR (15 DOWNTO 0);
		datab		: IN STD_LOGIC_VECTOR (15 DOWNTO 0);
		result		: OUT STD_LOGIC_VECTOR (31 DOWNTO 0)
	);
END SA1MULT;

ARCHITECTURE SYN OF sa1mult IS
BEGIN
	result <= std_logic_vector(signed(dataa) * signed(datab));
END SYN;
