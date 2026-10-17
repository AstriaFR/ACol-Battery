#define _GNU_SOURCE
#include <jni.h>
#include <sys/timerfd.h>
#include <sys/socket.h>
#include <sys/eventfd.h>
#include <sys/inotify.h>
#include <linux/netlink.h>
#include <poll.h>
#include <time.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <limits.h>

static int timer=-1, events=-1, notify=-1, module_events=-1, wake_alarm=0, wake_hold=0;
static int write_node(const char *path,const char *value) {
    int fd=open(path,O_WRONLY|O_CLOEXEC);
    if(fd<0) return 0;
    size_t n=strlen(value); int ok=write(fd,value,n)==(ssize_t)n;
    close(fd); return ok;
}
static void hold(void) {
    wake_hold=write_node("/sys/power/wake_lock","aco_battery_app 10000000000");
}
JNIEXPORT jint JNICALL Java_cn_astriafr_acobattery_daemon_Main_initWait(JNIEnv *env,jclass cls) {
    (void)env;(void)cls;
    timer=timerfd_create(CLOCK_BOOTTIME_ALARM,TFD_CLOEXEC|TFD_NONBLOCK);
    if(timer>=0) {
        // Creating the fd alone does not prove CAP_WAKE_ALARM; arm it as well.
        struct itimerspec test={0}; test.it_value.tv_sec=1;
        if(timerfd_settime(timer,0,&test,NULL)==0) wake_alarm=1;
        else { close(timer); timer=-1; }
    }
    if(timer<0) timer=timerfd_create(CLOCK_BOOTTIME,TFD_CLOEXEC|TFD_NONBLOCK);
    notify=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
    events=socket(AF_NETLINK,SOCK_DGRAM|SOCK_CLOEXEC|SOCK_NONBLOCK,NETLINK_KOBJECT_UEVENT);
    if(events>=0) {
        struct sockaddr_nl address={0};address.nl_family=AF_NETLINK;address.nl_pid=getpid();address.nl_groups=1;
        if(bind(events,(struct sockaddr*)&address,sizeof(address))<0){close(events);events=-1;}
    }
    hold();
    return (wake_alarm?1:0)|(events>=0?2:0)|(wake_hold?4:0)|(timer>=0?8:0)|(notify>=0?16:0);
}
JNIEXPORT void JNICALL Java_cn_astriafr_acobattery_daemon_Main_interruptWait(JNIEnv *env,jclass cls) {
    (void)env;(void)cls;uint64_t count=1;
    if(notify>=0) {ssize_t result;do {result=write(notify,&count,sizeof(count));} while(result<0&&errno==EINTR);}
}
JNIEXPORT jboolean JNICALL Java_cn_astriafr_acobattery_daemon_Main_watchModule(JNIEnv *env,jclass cls,jstring path) {
    (void)cls;
    const char *directory=(*env)->GetStringUTFChars(env,path,NULL);
    if(!directory)return JNI_FALSE;
    module_events=inotify_init1(IN_CLOEXEC|IN_NONBLOCK);
    if(module_events>=0&&inotify_add_watch(module_events,directory,IN_CREATE|IN_MOVED_TO|IN_ATTRIB|IN_DELETE_SELF|IN_MOVE_SELF)<0) {
        close(module_events);module_events=-1;
    }
    (*env)->ReleaseStringUTFChars(env,path,directory);
    return module_events>=0?JNI_TRUE:JNI_FALSE;
}
JNIEXPORT void JNICALL Java_cn_astriafr_acobattery_daemon_Main_releaseWait(JNIEnv *env,jclass cls) {
    (void)env;(void)cls;write_node("/sys/power/wake_unlock","aco_battery_app");
}
JNIEXPORT jint JNICALL Java_cn_astriafr_acobattery_daemon_Main_waitEvent(JNIEnv *env,jclass cls,jlong delay) {
    (void)env;(void)cls;
    if(delay<1)delay=1;
    struct itimerspec spec={0};spec.it_value.tv_sec=delay/1000;spec.it_value.tv_nsec=(delay%1000)*1000000;
    if(timer>=0&&timerfd_settime(timer,0,&spec,NULL)<0)return -1;
    struct pollfd fds[4]={{timer,POLLIN,0},{events,POLLIN,0},{notify,POLLIN,0},{module_events,POLLIN,0}};
    for(;;) {
        int n=poll(fds,4,timer>=0?-1:(int)(delay>INT_MAX?INT_MAX:delay));
        if(n<0){if(errno==EINTR)continue;return -1;}
        if(n==0){hold();return 1;}
        if(fds[2].revents&POLLIN){uint64_t count;read(notify,&count,sizeof(count));hold();return 4;}
        if(fds[3].revents&POLLIN){char buf[4096];while(read(module_events,buf,sizeof(buf))>0){}hold();return 8;}
        if(fds[0].revents&POLLIN){uint64_t count;read(timer,&count,sizeof(count));hold();return 1;}
        if(events>=0 && (fds[1].revents&POLLIN)) {
            char buf[8192];ssize_t len;
            while((len=recv(events,buf,sizeof(buf)-1,0))>0) {
                buf[len]=0;
                int power=0;
                for(ssize_t i=0;i<len;) {
                    if(strstr(buf+i,"power_supply")||strstr(buf+i,"oplus_chg"))power=1;
                    i+=(ssize_t)strlen(buf+i)+1;
                }
                if(power){hold();return 2;}
            }
        }
    }
}
