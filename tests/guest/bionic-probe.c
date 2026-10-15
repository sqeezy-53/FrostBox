// Original ABI qualification fixture: dynamic Bionic, pthread and per-thread TLS.
#include <pthread.h>
#include <stdio.h>
static __thread int marker;
static unsigned total;
static void *worker(void *unused) {
    (void)unused; marker=77;
    for(unsigned i=1;i<=100;i++) total+=i;
    return (void *)(marker==77 ? 1L : 0L);
}
int main(void) {
    marker=12;pthread_t thread;void *answer=0;
    if(pthread_create(&thread,0,worker,0)||pthread_join(thread,&answer)||answer!=(void *)1L||marker!=12)return 1;
    printf("FROSTBOX_BIONIC_RESULT=%u\n",total);
    return total==5050?42:2;
}
