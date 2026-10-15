/* Test-only timing stub for Linux C ABI/transport tests; not packaged in the APK. */
#include "config.h"
#include <time.h>
#include "ntstatus.h"
#include "winternl.h"
NTSTATUS WINAPI NtQueryPerformanceCounter(LARGE_INTEGER *counter,LARGE_INTEGER *frequency){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);counter->QuadPart=(long long)t.tv_sec*1000000+t.tv_nsec/1000;if(frequency)frequency->QuadPart=1000000;return STATUS_SUCCESS;}
