/* Original, freestanding Linux x86-64 guest: integer computation + write/exit syscalls.
 * Not a Windows PE, not a libc/Wine test. Intended to isolate Box64 execution first. */
.global _start
.section .text
_start:
    xor %ebx,%ebx
    mov $1,%ecx
1:  add %ecx,%ebx
    inc %ecx
    cmp $101,%ecx
    jne 1b
    cmp $5050,%ebx
    jne failed
    mov $1,%eax
    mov $1,%edi
    lea output(%rip),%rsi
    mov $(output_end-output),%edx
    syscall
    cmp $(output_end-output),%rax
    jne failed
    mov $60,%eax
    mov $42,%edi
    syscall
failed:
    mov $60,%eax
    mov $1,%edi
    syscall
.section .rodata
output: .ascii "FROSTBOX_ELF_RESULT=5050\n"
output_end:
