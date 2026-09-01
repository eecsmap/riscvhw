# Assemble co-simulation programs. Requires the Chipyard toolchain:
#   cd ~/github/chipyard && source env.sh
GCC     := riscv64-unknown-elf-gcc
OBJCOPY := riscv64-unknown-elf-objcopy
OBJDUMP := riscv64-unknown-elf-objdump

CFLAGS  := -march=rv64i_zicsr -mabi=lp64 -nostdlib -nostartfiles -Wl,-Ttext=0x80000000

SRCS := $(wildcard *.S)
BINS := $(SRCS:.S=.bin)

all: $(BINS)

%.elf: %.S
	$(GCC) $(CFLAGS) $< -o $@

%.bin: %.elf
	$(OBJCOPY) -O binary $< $@
	$(OBJDUMP) -d $< > $*.dump

clean:
	rm -f *.elf *.bin *.dump *.trace

.PHONY: all clean
