// SPDX-License-Identifier: MIT
__declspec(dllexport) unsigned int FrostSum(void) {
    volatile unsigned int sum=0;
    for(unsigned int i=1;i<=100;++i) sum+=i;
    return sum;
}
int DllMain(void* module,unsigned long reason,void* reserved) { (void)module;(void)reason;(void)reserved;return 1; }
