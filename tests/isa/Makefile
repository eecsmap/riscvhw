# Flatten the installed riscv-tests ELFs into raw images this core can load.
#
# The core has no ELF loader: the scratchpad takes a flat image at memBase, and
# objcopy -O binary produces exactly that, including the gap between .text.init
# at 0x80000000 and the tohost page at 0x80001000.
ISA_DIR := $(RISCV)/riscv64-unknown-elf/share/riscv-tests/isa
OBJCOPY := riscv64-unknown-elf-objcopy

ELFS := $(filter-out %.dump,$(wildcard $(ISA_DIR)/rv64ui-p-* $(ISA_DIR)/rv64mi-p-*))
BINS := $(patsubst $(ISA_DIR)/%,%.bin,$(ELFS))

all: $(BINS)

%.bin: $(ISA_DIR)/%
	$(OBJCOPY) -O binary $< $@

clean:
	rm -f *.bin

.PHONY: all clean
